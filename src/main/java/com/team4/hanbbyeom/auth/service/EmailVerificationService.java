package com.team4.hanbbyeom.auth.service;

import com.team4.hanbbyeom.auth.domain.EmailVerification;
import com.team4.hanbbyeom.auth.domain.VerificationPurpose;
import com.team4.hanbbyeom.auth.exception.PasswordResetAuthenticationException;
import com.team4.hanbbyeom.auth.exception.VerificationCodeMismatchException;
import com.team4.hanbbyeom.auth.repository.EmailVerificationRepository;
import com.team4.hanbbyeom.global.util.EmailNormalizer;
import com.team4.hanbbyeom.user.repository.UserRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage; // HTML, 이미지, 첨부파일, 인라인 이미지 등을 포함하는 복잡한 이메일 표현 가능
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value; // Spring 설정값을 필드에 주입하기 위해 사용하는 @Value 어노테이션
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Limit;
import org.springframework.mail.javamail.JavaMailSender; // 메일 발송 인터페이스
import org.springframework.mail.javamail.MimeMessageHelper; // MimeMessage를 좀 더 쉽게 작성하게 지원
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac; // (Java 제공) MAC(Message Authentication Code)을 계산하는 클래스 - 비밀키 사용하는 HMAC 계산용
import javax.crypto.spec.SecretKeySpec; // 바이트 배열로 가지고 있는 비밀키를 Java가 HMAC 키로 사용할 수 있도록 비밀키 객체로 만드는 클래스
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException; // HMAC에 넘긴 비밀키가 올바르지 않을 때 발생할 수 있는 예외
import java.security.MessageDigest; // 타이밍 공격 방지용 정해진 시간 비교(isEqual) 제공
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64; // Base64 문자열 ↔ 바이트 배열 변환
import java.util.HexFormat;

// 이메일 인증 코드 생성, 저장, 발송을 담당하는 Service
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    // 인증 코드 자릿수
    private static final int CODE_LENGTH = 6;

    // 인증 코드 유효기간
    private static final Duration CODE_EXPIRATION = Duration.ofMinutes(5);

    // 재전송 최소 대기시간
    private static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);

    // 인증 코드 입력 실패 허용 횟수 (무차별 대입 방지)
    private static final int MAX_ATTEMPT_COUNT = 5;

    // 인증 완료 후 비밀번호 변경 허용 시간
    private static final Duration PASSWORD_RESET_WINDOW = Duration.ofMinutes(10);

    // Service 동작에 필요한 도구(의존성) 선언 필드
    private final EmailVerificationRepository emailVerificationRepository; // 이메일 인증 기록을 DB에 저장하고 조회하기 위해 사용
    private final UserRepository userRepository; // 비밀번호 재설정 요청 이메일의 가입 여부 확인에 사용
    private final JavaMailSender mailSender; // (Spring 제공) 메일 발송 도구 인터페이스
    private final Clock clock; // 만료·재전송 대기 판정은 TimeConfig의 Clock 빈만 사용

    // 인증 코드 해시(HMAC-SHA256)용 서버 전용 비밀키 (application.yaml → .env)
    // DB만 유출된 경우 이 키가 없으면 인증 코드를 역산할 수 없도록 함
    @Value("${email.verification.hmac-secret-base64}")
    private String hmacSecretBase64; // .env에 있는 설정값을 이 필드에 그대로 넣음

    // @Transactional: 이 메서드 안의 DB 작업을 하나의 트랜잭션으로 처리, 하나가 실패하면 모두 롤백
    // 인증 코드 생성, 해시 저장, 메일 발송을 한 번에 처리
    @Transactional
    public void sendVerificationCode(String rawEmail, VerificationPurpose purpose) {
        String email = EmailNormalizer.normalize(rawEmail); // 이메일 정규화(공백 제거 후 소문자로)

        // 목적이 PASSWORD_RESET인 경우 - 가입 여부 비노출 처리
        if (purpose == VerificationPurpose.PASSWORD_RESET
                && !userRepository.existsByEmailAndDeletedAtIsNull(email)) {
            // 기존에는 '가입 이메일: 기존 기록 때문에 400 응답', '미가입 이메일: 기록이 없어서 계속 200 응답'
            // → 이 차이로 가입 여부 추측 가능, 차이가 드러나지 않도록 수정
            // → 미가입 이메일이어도 예외를 발생시키지 않고 return으로 정상 종료 (인증 기록과 메일은 생성X)
            return;
        }

        boolean resendAllowed = isResendAllowed(email, purpose);

        // 목적이 PASSWORD_RESET인 경우 - 재발송 제한 여부 비노출 처리
        // 60초 재발송 제한 중인 요청도 위와 같이 정상 종료, 추가 발송만 생략
        if (!resendAllowed && purpose == VerificationPurpose.PASSWORD_RESET) {
            return;
        }

        // 재발송 제한 응답: 기존 SIGNUP 목적과 동일한 문구 사용
        if (!resendAllowed) {
            throw new IllegalStateException("인증 코드는 잠시 후 다시 요청할 수 있습니다.");
        }

        String code = generateCode(); // 인증번호 생성
        String codeHash = hash(code); // 인증번호 해시 (DB에는 해시값 저장)
        Instant expiresAt = Instant.now(clock).plus(CODE_EXPIRATION); // 만료시간 계산

        // DB 저장
        emailVerificationRepository.save(
                new EmailVerification(
                        email,
                        purpose,
                        codeHash,
                        expiresAt
                )
        );

        // 이메일 발송 (해시값인 codeHash가 아니라 원본 코드 code 발송)
        sendEmail(email, code);
    }

    // @Transactional: 조회한 verification의 필드 변경(increaseAttemptCount, markVerified)이
    // 트랜잭션 커밋 시점에 JPA dirty checking으로 자동 반영됨 (별도 save() 호출 불필요)

    // 이메일 인증 코드 확인: 사용 여부·만료·시도 횟수 검증 후 코드 일치 여부 확인
    // noRollbackFor = VerificationCodeMismatchException.class:
    // 인증 코드 불일치 예외는 실패 횟수 저장 후 발생하므로 해당 변경만 롤백에서 제외, 증가한 실패 횟수 DB 저장
    @Transactional(noRollbackFor = VerificationCodeMismatchException.class)
    public void confirmCode(String rawEmail, VerificationPurpose purpose, String rawCode) {
        String email = EmailNormalizer.normalize(rawEmail); // 이메일 정규화(공백 제거 후 소문자로)

        // 이메일+목적으로 가장 최근에 생성된 인증 기록만 유효한 확인 대상으로 조회
        EmailVerification verification = emailVerificationRepository
                .findTopByEmailAndPurposeOrderByCreatedAtDescIdDesc(email, purpose)
                .orElseThrow(() -> new IllegalStateException("인증 코드 발송 내역이 없습니다. 인증 코드를 먼저 요청해주세요."));

        // 이미 인증에 성공해 사용 완료된 코드인지 확인 (일회성 사용 보장)
        if (verification.getVerifiedAt() != null) {
            throw new IllegalStateException("이미 사용된 인증 코드입니다.");
        }

        // 유효기간이 지났는지 확인
        if (Instant.now(clock) // 현재 시각
                .isAfter( // 검사: 현재 시각이 만료 시각보다 나중인가?
                        verification.getExpiresAt() // DB에 저장된 인증번호 만료 시각
                )
        ) {
            throw new IllegalStateException("인증 코드가 만료되었습니다. 인증 코드를 다시 요청해주세요.");
        }

        // 이 코드에 대한 입력 실패 횟수가 허용 범위(5회)를 넘었는지 확인 (무차별 대입 방지)
        if (verification.getAttemptCount() >= MAX_ATTEMPT_COUNT) {
            throw new IllegalStateException("인증 시도 횟수를 초과했습니다. 인증 코드를 다시 요청해주세요.");
        }

        // 입력한 코드와 저장된 해시가 일치하는지 확인
        if (!matchesHash(rawCode, verification.getCodeHash())) {
            // 불일치 시 실패 횟수 증가
            verification.increaseAttemptCount();
            throw new VerificationCodeMismatchException("인증 코드가 일치하지 않습니다.");
        }

        // 일치하면 인증 완료 처리
        verification.markVerified(Instant.now(clock));
    }

    // 조회만 하고 값을 변경하지 않으므로 readOnly = true (성능 최적화, 실수로 값 변경 시 예외 발생)
    @Transactional(readOnly = true)
    // 이 이메일이 전달받은 목적에 대해 가장 최근 인증 요청 기준으로 인증 완료됐는지 확인하는 메서드
    // 회원가입 Service가 가입 완료 처리 전에 호출해서 사용
    public boolean isVerified(String rawEmail, VerificationPurpose purpose) {
        String email = EmailNormalizer.normalize(rawEmail); // 이메일 정규화(공백 제거 후 소문자로)

        return emailVerificationRepository
                // 같은 이메일 + 같은 인증 목적 중에서 가장 최근에 생성된 인증 기록 1개를 가져옴
                .findTopByEmailAndPurposeOrderByCreatedAtDescIdDesc(email, purpose)
                // Optional 안에 EmailVerification 객체가 있다면 꺼내서 boolean 값으로 변환
                .map(verification
                        // 인증 완료 시간이 있는지 조회
                        -> verification.getVerifiedAt() != null)
                // Optional이 비어 있을 때 사용할 기본값 (발송 내역 자체가 없으면 인증 안 된 것으로 처리)
                .orElse(false);
    }

    // 이 이메일이 해당 목적으로 실제 인증에 성공한 시각을 반환하는 메서드
    // 회원가입 Service가 User.emailVerifiedAt에 정확한 인증 시각을 기록하기 위해 사용
    @Transactional(readOnly = true)
    public Instant getVerifiedAt(String rawEmail, VerificationPurpose purpose) {
        String email = EmailNormalizer.normalize(rawEmail); // 이메일 정규화(공백 제거 후 소문자로)

        return emailVerificationRepository
                // 같은 이메일 + 같은 인증 목적 중에서 가장 최근에 생성된 인증기록 1개를 가져옴(optional)
                .findTopByEmailAndPurposeOrderByCreatedAtDescIdDesc(
                        email,
                        purpose
                )
                // 인증기록에 verifiedAt(실제 인증성공 시각)이 있으면 가져옴(optional)
                .map(verification -> verification.getVerifiedAt())
                // verifiedAt이 null이면(아직 인증 안 됨) Optional을 빈 상태로 만듦
                .filter(verifiedAt -> verifiedAt != null)
                // 위 과정을 거치고도 값이 비어있으면(기록 자체가 없거나, 있어도 미인증) 예외를 던짐
                // (호출 시점엔 이미 인증됐다고 가정하는 상황이라 값이 없는 건 비정상 상태)
                .orElseThrow(() -> new IllegalStateException("이메일 인증을 먼저 완료해주세요."));
    }

    // 비밀번호를 변경하기 전에, 가장 최근의 비밀번호 재설정 인증이 유효한지 최종 확인
    // 같은 인증 기록으로 여러 요청이 동시에 처리되지 않도록 DB에서 해당 기록을 잠금 조회(Lock 걸어서 동시요청충돌 방지)
    @Transactional(noRollbackFor = VerificationCodeMismatchException.class)
    public void validatePasswordResetAuthorization(String rawEmail, String rawCode) {
        String email = EmailNormalizer.normalize(rawEmail);

        // 해당 이메일의 가장 최근 비밀번호 재설정 인증 기록을 잠금 상태로 조회
        EmailVerification verification = emailVerificationRepository
                .findLatestForUpdate(
                        email,
                        VerificationPurpose.PASSWORD_RESET,
                        Limit.of(1)
                )
                .orElseThrow(PasswordResetAuthenticationException::new);

        // 이메일 인증이 완료되지 않았거나 최대 시도 횟수를 초과했다면 비밀번호 재설정 불가
        if (verification.getVerifiedAt() == null
                || verification.getAttemptCount() >= MAX_ATTEMPT_COUNT) {
            throw new PasswordResetAuthenticationException();
        }

        // 이메일 인증 완료 후 허용된 비밀번호 재설정 시간(10분)이 지났는지 확인
        Instant resetDeadline = verification
                .getVerifiedAt()
                .plus(PASSWORD_RESET_WINDOW);

        if (Instant.now(clock).isAfter(resetDeadline)) {
            throw new PasswordResetAuthenticationException();
        }

        // 비밀번호 변경 요청에 포함된 인증 코드를 다시 확인
        // 코드가 틀리면 실패 횟수를 1 증가시키고 인증 실패 처리
        if (!matchesHash(rawCode, verification.getCodeHash())) {
            verification.increaseAttemptCount();

            throw new VerificationCodeMismatchException(
                    PasswordResetAuthenticationException.MESSAGE
            );
        }
    }

    // 비밀번호 변경이 끝난 뒤 같은 인증 기록을 다시 사용할 수 없도록, 해당 이메일의 비밀번호 재설정 인증 기록을 모두 삭제
    @Transactional
    public void consumePasswordResetVerifications(String rawEmail) {
        String email = EmailNormalizer.normalize(rawEmail);

        emailVerificationRepository.deleteAllByEmailAndPurpose(
                email,
                VerificationPurpose.PASSWORD_RESET
        );
    }

    // 같은 이메일로 같은 목적의 인증 코드를 다시 보낼 수 있는지 (최근 발송 시각 기준 재발송 가능 여부) 확인
    private boolean isResendAllowed(String email, VerificationPurpose purpose) {
        return emailVerificationRepository
                .findTopByEmailAndPurposeOrderByCreatedAtDescIdDesc(email, purpose)
                // 최근 발송 기록이 있으면 60초 경과 여부 확인
                .map(latest -> {
                    Instant nextAllowedAt = latest.getCreatedAt().plus(RESEND_INTERVAL);
                    return !Instant.now(clock).isBefore(nextAllowedAt);
                })
                // 발송 기록이 없으면 즉시 발송 허용
                .orElse(true);
    }

    // SecureRandom으로 예측 불가능한 6자리 인증 코드 생성
    private String generateCode() {
        SecureRandom random = new SecureRandom(); // 보안용 난수 생성기 생성

        // CODE_LENGTH가 6이므로 10^6 = 1,000,000
        int bound = (int) Math.pow(10, CODE_LENGTH);

        // 0 ~ 999999 중 예측하기 어려운 난수 생성
        int value = random.nextInt(bound);

        // 생성된 숫자를 6자리 문자열로 변환
        // 자릿수가 부족하면 앞에 0을 채움 (예: 1234 → "001234")
        return String.format("%0" + CODE_LENGTH + "d", value);
    }

    // 이전 (SHA-256): 코드 원문만 넣고 해시 계산, 비밀키 없음
    // 지금 (HMAC-SHA256): 해시 계산에 코드 원문뿐 아니라 서버 비밀키까지 포함 → DB 유출만으로는 원본 코드 역산 불가

    // 인증 코드 원문을 HMAC-SHA256 해시로 변환 (원문은 저장하지 않음)
    private String hash(String rawCode) {
        try {
            // .env에 Base64 문자열로 저장된 비밀키를 원래 바이트 배열로 복원
            byte[] keyBytes = Base64.getDecoder().decode(hmacSecretBase64);

            // HMAC 계산에 사용할 비밀키 객체 생성
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "HmacSHA256");

            // Mac: (Java 제공) 비밀키 기반 해시(HMAC) 계산용 클래스
            // getInstance()로 만든 시점에는 알고리즘만 정해졌을 뿐 키가 없는 상태
            Mac mac = Mac.getInstance("HmacSHA256");

            // keySpec을 실제로 장착해야 계산 가능한 상태가 됨
            // init() 없이 doFinal() 호출 시 IllegalStateException 발생
            mac.init(keySpec);

            // 1. rawCode는 String이므로 UTF-8 규칙으로 byte[]로 변환
            // 2. mac.doFinal(byte[])을 실행해 비밀키가 섞인 HMAC-SHA256 해시 계산
            // 3. 결과는 256bit = 32byte이므로 hashBytes에는 32byte짜리 해시 결과가 저장
            byte[] hashBytes = mac.doFinal(rawCode.getBytes(StandardCharsets.UTF_8));

            // 결과는 현재 byte[] 형태이므로 DB에 문자열로 저장하기 편하도록 16진수 문자열로 변환
            // 32byte → 64자리 16진수 문자열
            return HexFormat.of().formatHex(hashBytes);

        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // 해시 알고리즘을 사용할 수 없거나 비밀키 형식이 잘못된 경우 발생하는 예외
            throw new IllegalStateException("해시 알고리즘을 사용할 수 없습니다.", e);
        }
    }

    // 입력한 코드를 해시로 변환해 저장된 해시와 값이 같은지 비교
    // String.equals()는 앞에서부터 다른 지점이 나오면 즉시 비교를 멈춰서,
    // 그 소요 시간 차이로 정답에 가까운 값이 추측될 수 있음(타이밍 공격)
    // → MessageDigest.isEqual(): 일부러 끝까지 다 비교한 다음에 결과를 반환
    private boolean matchesHash(String rawCode, String expectedHash) {
        String actualHash = hash(rawCode);
        byte[] actualBytes = HexFormat.of().parseHex(actualHash);
        byte[] expectedBytes = HexFormat.of().parseHex(expectedHash);
        return MessageDigest.isEqual(actualBytes, expectedBytes);
    }

    // 로고 이미지 리소스 경로 (src/main/resources/mail/logo.png)
    private static final String LOGO_PATH = "mail/logo.png";

    // 메일 본문에서 로고를 참조할 Content-ID
    private static final String LOGO_CONTENT_ID = "logo";

    // 인증 코드 원문을 HTML 메일로 발송 (발송 후에는 코드 원문을 어디에도 저장하지 않음)
    private void sendEmail(String email, String code) {
        try {
            // MimeMessage: HTML, 이미지, 첨부파일 등을 포함할 수 있는 이메일 메시지 객체
            // mailSender를 이용해 비어 있는 MimeMessage 객체 생성
            MimeMessage message = mailSender.createMimeMessage();

            // MimeMessageHelper:MimeMessage에 받는 사람, 제목, 본문, 이미지 등을 편리하게 설정할 수 있도록 도와주는 Spring 클래스
            // 인라인 이미지(cid 참조)를 넣으려면 MULTIPART_MODE_RELATED로 생성해야 함
            MimeMessageHelper helper = new MimeMessageHelper(
                    // 이메일 메시지
                    message,
                    // multipart: HTML본문 + 인라인 이미지(로고)를 서로 연결해서 사용하기 위한 모드
                    MimeMessageHelper.MULTIPART_MODE_RELATED,
                    // // 한글이 깨지지 않도록 문자 인코딩을 UTF-8로 지정
                    StandardCharsets.UTF_8.name()
            );

            // 이메일 수신자 설정
            helper.setTo(email);

            // 이메일 제목 설정
            helper.setSubject("[한뼘] 이메일 인증");

            // 이메일 본문 설정
            // code 값이 두 메서드에 전달되므로 실제 인증번호가 본문에 포함됨
            // 텍스트 버전을 함께 제공하면 스팸 필터가 정상 메일로 인식하는 데 도움이 됨
            helper.setText(
                    // HTML을 사용할 수 없는 환경에서 보여줄 일반 텍스트 본문
                    buildPlainTextContent(code),
                    // 일반적인 메일 앱에서 보여줄 HTML 본문
                    buildHtmlContent(code)
            );

            // HTML 안에서 사용할 로고 이미지를 인라인 이미지로 등록
            // 문제해결: 콘텐츠 타입을 명시하지 않으면 일부 메일 클라이언트가 인라인 이미지 대신 첨부파일로 처리함
            helper.addInline(
                    // 이미지 식별자(Content-ID): HTML의 <img src="cid:logo">와 연결
                    LOGO_CONTENT_ID,
                    // src/main/resources/mail/logo.png 파일을 읽어옴
                    new ClassPathResource(LOGO_PATH),
                    // 이미지 MIME 타입
                    "image/png"
            );

            // 지금까지 구성한 MimeMessage를 발송
            mailSender.send(message);

        } catch (MessagingException e) {
            // 메일 메시지를 구성하는 과정에서 문제가 발생하면 처리
            throw new IllegalStateException("인증 메일 발송에 실패했습니다.", e);
        }
    }

    // 인증 코드 메일 본문 1: 디자인이 적용된 HTML 메일
    // %s 자리에 code, 즉 인증번호가 들어감
    // 대부분의 메일 클라이언트가 커스텀 웹폰트(@font-face)를 지원하지 않아 시스템 기본 글꼴만 사용
    private String buildHtmlContent(String code) {
        // """는 Java의 여러 줄짜리 Text Block 문법 - HTML 형태 그대로 작성 가능
        return """
                <div style="max-width: 480px; margin: 0 auto; font-family: -apple-system, 'Malgun Gothic', sans-serif; border: 1px solid #e5e5e5; border-top: 3px solid #B2E746;">
                  <div style="padding: 32px 32px 24px; text-align: center;">
                    <img src="cid:%s" alt="한뼘" style="height: 84px; margin-bottom: 24px;" />
                    <h2 style="margin: 0; font-size: 20px;">한뼘 인증번호</h2>
                  </div>
                  <div style="border-top: 1px solid #e5e5e5;"></div>
                  <div style="padding: 32px; text-align: center; color: #444; line-height: 1.7;">
                    <p style="margin: 0;">
                      이메일 인증을 진행합니다.<br/>
                      아래 인증번호를 입력하여 인증을 완료해 주세요.<br/>
                      개인정보 보호를 위해 인증번호는 <strong>5분간</strong> 유효합니다.
                    </p>
                    <div style="font-size: 32px; font-weight: bold; letter-spacing: 8px; background: #f5f5f5; padding: 20px; border-radius: 8px; margin: 24px 0;">
                      %s
                    </div>
                    <p style="color: #999; font-size: 13px; margin: 0;">
                      인증번호는 본인 확인 용도이니 타인에게 공유하지 마세요.
                    </p>
                  </div>
                  <div style="border-top: 1px solid #e5e5e5;"></div>
                  <div style="padding: 20px; text-align: center; color: #999; font-size: 12px;">
                    본 메일은 발신전용입니다. 본 메일로 회신하실 경우 답변이 되지 않습니다.<br/>
                    © Team 한뼘. All rights reserved.
                  </div>
                </div>
                """.formatted(LOGO_CONTENT_ID, code);
    }

    // 인증 코드 메일 본문 2: 디자인 없는 순수 텍스트 메일
    private String buildPlainTextContent(String code) {
        return """
                한뼘 인증번호

                이메일 인증을 진행합니다. 아래 인증번호를 입력하여 인증을 완료해 주세요.
                개인정보 보호를 위해 인증번호는 5분간 유효합니다.

                인증번호: %s

                인증번호는 본인 확인 용도이니 타인에게 공유하지 마세요.
                본 메일은 발신전용입니다. 본 메일로 회신하실 경우 답변이 되지 않습니다.
                """.formatted(code);
    }
}
