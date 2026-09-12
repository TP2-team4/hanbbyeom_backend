package com.team4.hanbbyeom.auth.service;

import com.team4.hanbbyeom.auth.domain.EmailVerification;
import com.team4.hanbbyeom.auth.domain.VerificationPurpose;
import com.team4.hanbbyeom.auth.repository.EmailVerificationRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage; // HTML, 이미지, 첨부파일, 인라인 이미지 등을 포함하는 복잡한 이메일 표현 가능
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender; // 메일 발송 인터페이스
import org.springframework.mail.javamail.MimeMessageHelper; // MimeMessage를 좀 더 쉽게 작성하게 지원
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

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

    // Service 동작에 필요한 도구(의존성) 선언 필드
    private final EmailVerificationRepository emailVerificationRepository; // 이메일 인증 기록을 DB에 저장하고 조회하기 위해 사용
    private final JavaMailSender mailSender; // (Spring 제공) 메일 발송 도구 인터페이스

    // @Transactional: 이 메서드 안의 DB 작업을 하나의 트랜잭션으로 처리, 하나가 실패하면 모두 롤백
    // 인증 코드 생성, 해시 저장, 메일 발송을 한 번에 처리
    @Transactional
    public void sendVerificationCode(String rawEmail, VerificationPurpose purpose) {
        String email = normalize(rawEmail); // 이메일 정규화(공백 제거 후 소문자로)

        validateResendInterval(email, purpose);

        String code = generateCode(); // 인증번호 생성
        String codeHash = hash(code); // 인증번호 해시 (DB에는 해시값 저장)
        Instant expiresAt = Instant.now().plus(CODE_EXPIRATION); // 만료시간 계산

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

    // 이메일 앞뒤 공백 제거 및 소문자 변환 (users 테이블과 동일한 정규화 규칙)
    private String normalize(String email) {
        return email
                .trim() // 앞뒤 공백 제거
                .toLowerCase(Locale.ROOT); // 소문자로 변환
    }

    // 60초 재발송 제한을 검사하는 메서드
    // 동일 이메일+목적의 마지막 발송 시각과 비교해 최소 대기시간 이내 재전송 차단
    private void validateResendInterval(String email, VerificationPurpose purpose) {
        emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(email, purpose)
                .ifPresent(latest -> {

                    // 최근 인증번호의 발송시간에 RESEND_INTERVAL(60초)를 더해서 재발송 가능 시간 설정
                    Instant nextAllowedAt = latest.getCreatedAt().plus(RESEND_INTERVAL);

                    // 현재 시간이 다음 허용 시간보다 이전인지 확인
                    if (Instant.now().isBefore(nextAllowedAt)) {
                        // 아직 60초가 지나지 않았으면 예외 던져서 메서드 실행 중단
                        throw new IllegalStateException("인증 코드는 잠시 후 다시 요청할 수 있습니다.");
                    }
                });
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

    // 인증 코드 원문을 SHA-256 해시로 변환 (원문은 저장하지 않음)
    private String hash(String rawCode) {
        try {
            // MessageDigest: (Java 제공) 해시 계산용 추상 클래스
            MessageDigest digest =
                    // 문자열로 알고리즘 이름 지정 -> "SHA-256" 지정
                    // SHA-256 알고리즘을 사용하는 MessageDigest 객체를 만들어 달라고 요청
                    MessageDigest.getInstance("SHA-256");

            // 1. rawCode는 String이므로 UTF-8 규칙으로 byte[]로 변환
            // 2. digest.digest(byte[])를 실행해 SHA-256 해시 계산
            // 3. SHA-256 결과는 256bit = 32byte이므로 hashBytes에는 32byte짜리 해시 결과가 저장
            byte[] hashBytes = digest.digest(rawCode.getBytes(StandardCharsets.UTF_8));

            // SHA-256 결과는 현재 byte[] 형태이므로 DB에 문자열로 저장하기 편하도록 16진수 문자열로 변환
            // 32byte → 64자리 16진수 문자열
            return HexFormat.of().formatHex(hashBytes);

        } catch (NoSuchAlgorithmException e) {
            // getInstance("SHA-256")에서 해당 해시 알고리즘을 사용할 수 없을 경우 발생하는 예외
            throw new IllegalStateException("해시 알고리즘을 사용할 수 없습니다.", e);
        }
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
