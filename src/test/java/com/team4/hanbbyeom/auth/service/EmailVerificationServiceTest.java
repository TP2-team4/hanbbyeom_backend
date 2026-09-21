package com.team4.hanbbyeom.auth.service;

import com.team4.hanbbyeom.auth.domain.EmailVerification;
import com.team4.hanbbyeom.auth.domain.VerificationPurpose;
import com.team4.hanbbyeom.auth.exception.VerificationCodeMismatchException;
import com.team4.hanbbyeom.auth.repository.EmailVerificationRepository;
import com.team4.hanbbyeom.user.domain.DefaultTalkLevel;
import com.team4.hanbbyeom.user.domain.User;
import com.team4.hanbbyeom.user.repository.UserRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// EmailVerificationService의 발송/확인/제한 로직을 실제 PostgreSQL로 검증

@SpringBootTest // Spring Boot 애플리케이션 전체 컨텍스트를 띄워서 테스트하는 어노테이션
@Transactional // 테스트 메서드가 끝나면 DB 변경 내용을 롤백
class EmailVerificationServiceTest {

    @Autowired // Spring이 필요한 객체를 자동으로 주입해주는 어노테이션
    private EmailVerificationService emailVerificationService;

    @Autowired
    private EmailVerificationRepository emailVerificationRepository;

    @Autowired
    private UserRepository userRepository;

    // @MockitoBean: 실제 Bean 대신 가짜(Mock) 객체를 만들어서 Spring 컨텍스트에 등록해주는 어노테이션
    // 테스트 중 실제로 메일이 발송되면 안 되므로, JavaMailSender를 가짜로 교체
    @MockitoBean
    private JavaMailSender mailSender;

    @BeforeEach // 각 테스트 메서드 실행 직전에 매번 호출되는 어노테이션
    void setUpMailSender() {
        // when(...).thenAnswer(...): Mockito 문법, 이 메서드가 호출되면 이 값을 반환하라고 가짜 동작 정의
        // sendVerificationCode() 내부에서 MimeMessageHelper가 실제로 동작하는 MimeMessage 객체를 요구하므로,
        // createMimeMessage() 호출 시 진짜 빈 MimeMessage를 새로 만들어서 돌려주도록 설정 (안 하면 NPE 발생)
        when(mailSender.createMimeMessage())
                .thenAnswer(invocation -> new MimeMessage(Session.getDefaultInstance(new Properties())));
    }

    // 테스트마다 겹치지 않는 이메일 생성 (UserRepositoryTest와 동일한 방식)
    private String randomEmail() {
        return "verification-" + UUID.randomUUID() + "@example.com";
    }

    // PASSWORD_RESET 발송 테스트용 가입 사용자 생성
    private void saveUser(String email) {
        String nickname = "테스트" + UUID.randomUUID().toString().substring(0, 8);

        userRepository.save(
                new User(
                        email,
                        "encoded-password",
                        nickname,
                        DefaultTalkLevel.SILENT,
                        Instant.now()
                )
        );
    }

    // 실제 코드와 매칭될 필요 없이 "유효한 16진수 형식의 해시값"만 있으면 되는 테스트에서 사용
    // (만료, 시도 횟수 초과, 이미 사용됨은 해시 비교 전 단계에서 이미 거부되고,
    //  코드 불일치 테스트는 저장된 값과 다르기만 하면 되므로 실제 해시일 필요가 없음)
    private static final String DUMMY_CODE_HASH = "00".repeat(32);

    // private hash() 메서드를 리플렉션으로 호출해, 테스트에서 직접 만든 인증 기록의
    // codeHash와 실제로 일치하는 원본 코드를 준비하기 위해 사용 (공개 API로는 확인 불가능한 값이라 필요)
    // "확인 성공" 테스트 1곳에서만 사용하고, 나머지 테스트는 DUMMY_CODE_HASH로 리플렉션 없이 검증
    private String hash(String rawCode) throws Exception {
        // AopTestUtils.getUltimateTargetObject:
        // emailVerificationService는 @Transactional 때문에 Spring이 감싼 프록시 객체라,
        // 그 뒤에 있는 진짜 객체(필드가 실제로 채워진 인스턴스)를 꺼내옴
        // (프록시 객체에 그대로 리플렉션을 걸면 필드가 비어있는 껍데기라 NPE 발생)
        EmailVerificationService target
                = AopTestUtils.getUltimateTargetObject(emailVerificationService);

        // getDeclaredMethod: private을 포함해 클래스에 선언된 메서드를 이름과 매개변수 타입으로 찾음
        Method method = EmailVerificationService.class.getDeclaredMethod(
                "hash", // 찾을 메서드 이름
                String.class // 매개변수 타입
        );

        // setAccessible(true): private 접근 제한을 무시하고 호출할 수 있게 허용
        method.setAccessible(true);

        // invoke(대상 객체, 인자): 찾은 메서드를 실제로 실행하고 반환값을 받음
        return (String) method.invoke(target, rawCode);
    }

    @Test // 테스트 메서드
    @DisplayName("미가입 이메일의 회원가입 인증 코드 발송 성공") // 테스트 결과 화면에서 읽기 좋은 이름으로 보여줌
    void 회원가입_인증_코드_발송_성공() {
        String email = randomEmail();

        // SIGNUP 목적은 아직 가입하지 않은 이메일에도 기존과 동일하게 발송
        emailVerificationService.sendVerificationCode(email, VerificationPurpose.SIGNUP);

        // DB에 인증 기록이 저장됐는지 확인
        assertTrue(
                emailVerificationRepository
                        .findTopByEmailAndPurposeOrderByCreatedAtDescIdDesc(
                                email,
                                VerificationPurpose.SIGNUP
                        )
                        .isPresent()
        );
        // verify(mock).메서드(...): Mockito 문법, "이 가짜 객체의 이 메서드가 실제로 호출됐는지" 검증
        // any(MimeMessage.class): 어떤 MimeMessage 값이 들어오든 상관없이 호출 여부만 확인
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("가입 이메일의 비밀번호 재설정 인증 코드 발송 성공")
    void 비밀번호_재설정_인증_코드_발송_성공() {
        String email = randomEmail();
        saveUser(email);

        emailVerificationService.sendVerificationCode(
                email,
                VerificationPurpose.PASSWORD_RESET
        );

        // PASSWORD_RESET 인증 기록 저장과 실제 메일 발송 확인
        assertTrue(
                emailVerificationRepository
                        .findTopByEmailAndPurposeOrderByCreatedAtDescIdDesc(
                                email,
                                VerificationPurpose.PASSWORD_RESET
                        )
                        .isPresent()
        );
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("미가입 이메일의 비밀번호 재설정 인증 기록 및 메일 미생성")
    void 미가입_이메일의_비밀번호_재설정_인증_요청_생략() {
        String email = randomEmail();

        // 미가입 여부를 응답으로 드러내지 않도록 예외 없이 종료
        emailVerificationService.sendVerificationCode(
                email,
                VerificationPurpose.PASSWORD_RESET
        );

        // 불필요한 인증 기록과 외부 메일 발송의 미생성 확인
        assertFalse(
                emailVerificationRepository
                        .findTopByEmailAndPurposeOrderByCreatedAtDescIdDesc(
                                email,
                                VerificationPurpose.PASSWORD_RESET
                        )
                        .isPresent()
        );
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("비밀번호 재설정 재발송 제한 중 동일 응답 및 추가 발송 생략")
    void 비밀번호_재설정_재발송_제한_중_추가_발송_생략() {
        String email = randomEmail();
        saveUser(email);

        emailVerificationService.sendVerificationCode(
                email,
                VerificationPurpose.PASSWORD_RESET
        );
        EmailVerification firstVerification = emailVerificationRepository
                .findTopByEmailAndPurposeOrderByCreatedAtDescIdDesc(
                        email,
                        VerificationPurpose.PASSWORD_RESET
                )
                .orElseThrow();

        // 첫 발송 후 60초 이내 요청도 예외 없이 종료
        emailVerificationService.sendVerificationCode(
                email,
                VerificationPurpose.PASSWORD_RESET
        );

        EmailVerification latestVerification = emailVerificationRepository
                .findTopByEmailAndPurposeOrderByCreatedAtDescIdDesc(
                        email,
                        VerificationPurpose.PASSWORD_RESET
                )
                .orElseThrow();

        // 새로운 인증 기록과 두 번째 메일의 미생성 확인
        assertEquals(firstVerification.getId(), latestVerification.getId());
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("재발송 대기시간 이내 재요청 거부")
    void 회원가입_인증_코드_재발송_대기시간_이내_요청_거부() {
        String email = randomEmail();

        emailVerificationService.sendVerificationCode(
                email,
                VerificationPurpose.SIGNUP
        );

        // assertThrows: 이 코드를 실행했을 때 특정 예외가 발생해야 테스트 성공이라고 검증 (JUnit 함수)
        // 예외 발생X 또는 다른 예외 발생 시 테스트 실패
        // 방금 발송했으므로 바로 다시 요청하면 거부되어야 함
        assertThrows(
                IllegalStateException.class,
                () -> emailVerificationService.sendVerificationCode(
                        email,
                        VerificationPurpose.SIGNUP
                )
        );
    }

    @Test
    @DisplayName("인증 코드 확인 성공")
    void 인증_코드_확인_성공() throws Exception {
        String email = randomEmail();
        String rawCode = "123456";

        // 실제로 확인에 성공해야 하는 케이스라, 저장할 해시가 rawCode의 진짜 해시와 일치해야 함
        emailVerificationRepository.save(
                new EmailVerification(
                        email,
                        VerificationPurpose.SIGNUP,
                        hash(rawCode), // rawCode와 실제로 일치하는 해시
                        Instant.now().plusSeconds(300) // 아직 유효한 만료 시각
                )
        );

        emailVerificationService.confirmCode(
                email,
                VerificationPurpose.SIGNUP,
                rawCode
        );

        // 인증 완료 처리(markVerified)가 반영됐는지 isVerified()로도 함께 확인
        assertTrue(emailVerificationService.isVerified(
                email,
                VerificationPurpose.SIGNUP)
        );
    }

    @Test
    @DisplayName("발송 내역이 없는 이메일 확인 시 거부")
    void 발송_내역이_없는_인증_코드_확인_거부() {
        String email = randomEmail();

        // 발송 요청 자체를 한 적 없는 이메일로 확인을 시도하는 상황
        assertThrows(
                IllegalStateException.class,
                () -> emailVerificationService.confirmCode(
                        email,
                        VerificationPurpose.SIGNUP,
                        "000000"
                )
        );
        assertFalse(emailVerificationService.isVerified(
                email,
                VerificationPurpose.SIGNUP)
        );
    }

    @Test
    @DisplayName("만료된 인증 코드 거부")
    void 만료된_인증_코드_확인_거부() {
        String email = randomEmail();

        // 이미 만료 시각이 지난 인증 기록을 직접 저장
        // 만료 체크가 해시 비교보다 먼저 일어나므로 DUMMY_CODE_HASH로 충분
        emailVerificationRepository.save(
                new EmailVerification(
                        email,
                        VerificationPurpose.SIGNUP,
                        DUMMY_CODE_HASH, // 해시 비교 전에 거부되므로 실제 값 불필요
                        Instant.now().minusSeconds(1) // 이미 지난 만료 시각
                )
        );

        assertThrows(
                IllegalStateException.class,
                () -> emailVerificationService.confirmCode(
                        email,
                        VerificationPurpose.SIGNUP,
                        "000000"
                )
        );
    }

    @Test
    @DisplayName("잘못된 인증 코드 입력 시 거부 및 실패 횟수 증가")
    void 잘못된_인증_코드_확인_거부와_실패_횟수_증가() {
        String email = randomEmail();

        // 저장된 해시와 다르기만 하면 되므로 DUMMY_CODE_HASH로 충분 (실제 코드의 해시일 필요 없음)
        emailVerificationRepository.save(
                new EmailVerification(
                        email,
                        VerificationPurpose.SIGNUP,
                        DUMMY_CODE_HASH, // 입력할 "000000"과는 일치하지 않는 더미 해시
                        Instant.now().plusSeconds(300) // 아직 유효한 만료 시각
                )
        );

        assertThrows(
                IllegalStateException.class,
                () -> emailVerificationService.confirmCode(
                        email,
                        VerificationPurpose.SIGNUP,
                        "000000"
                )
        );

        // 틀린 시도 1회가 attemptCount에 기록됐는지 확인
        EmailVerification saved = emailVerificationRepository
                .findTopByEmailAndPurposeOrderByCreatedAtDescIdDesc(
                        email,
                        VerificationPurpose.SIGNUP
                )
                .orElseThrow();
        assertEquals(1, saved.getAttemptCount());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("잘못된 인증 코드의 실패 횟수 운영 트랜잭션 반영")
    void 인증_코드_불일치_실패_횟수의_DB_반영() {
        String email = randomEmail();

        EmailVerification verification = emailVerificationRepository.saveAndFlush(
                new EmailVerification(
                        email,
                        VerificationPurpose.SIGNUP,
                        DUMMY_CODE_HASH,
                        Instant.now().plusSeconds(300)
                )
        );

        try {
            // Service 트랜잭션의 예외 발생 이후에도 실패 횟수 저장 필요
            assertThrows(
                    VerificationCodeMismatchException.class,
                    () -> emailVerificationService.confirmCode(
                            email,
                            VerificationPurpose.SIGNUP,
                            "000000"
                    )
            );

            // 별도 조회 트랜잭션을 통한 실제 DB 반영 결과 확인
            EmailVerification saved = emailVerificationRepository
                    .findById(verification.getId())
                    .orElseThrow();
            assertEquals(1, saved.getAttemptCount());
        } finally {
            // 클래스 수준 롤백이 적용되지 않는 테스트의 직접 데이터 정리
            emailVerificationRepository.deleteById(verification.getId());
        }
    }

    @Test
    @DisplayName("인증 시도 횟수 초과 시 거부")
    void 인증_시도_횟수_초과_요청_거부() {
        String email = randomEmail();

        // 시도 횟수 체크가 해시 비교보다 먼저 일어나므로 DUMMY_CODE_HASH로 충분
        EmailVerification verification =
                new EmailVerification(
                        email,
                        VerificationPurpose.SIGNUP,
                        DUMMY_CODE_HASH, // 해시 비교까지 가지 않으므로 실제 값 불필요
                        Instant.now().plusSeconds(300) // 아직 유효한 만료 시각
                );
        // 이미 5회(허용 한도) 실패한 상태를 미리 만들어둠
        for (int i = 0; i < 5; i++) {
            verification.increaseAttemptCount();
        }
        emailVerificationRepository.save(verification);

        assertThrows(
                IllegalStateException.class,
                () -> emailVerificationService.confirmCode(
                        email,
                        VerificationPurpose.SIGNUP,
                        "000000"
                )
        );
    }

    @Test
    @DisplayName("이미 사용된 인증 코드 재사용 거부")
    void 사용한_인증_코드의_재사용_거부() {
        String email = randomEmail();

        // 이미 사용됨 체크가 해시 비교보다 먼저 일어나므로 DUMMY_CODE_HASH로 충분
        EmailVerification verification =
                new EmailVerification(
                        email,
                        VerificationPurpose.SIGNUP,
                        DUMMY_CODE_HASH, // 해시 비교까지 가지 않으므로 실제 값 불필요
                        Instant.now().plusSeconds(300) // 아직 유효한 만료 시각
                );
        // 이미 인증을 완료한 상태를 미리 만들어둠 (일회성 사용 보장 검증용)
        verification.markVerified(Instant.now());
        emailVerificationRepository.save(verification);

        assertThrows(
                IllegalStateException.class,
                () -> emailVerificationService.confirmCode(
                        email,
                        VerificationPurpose.SIGNUP,
                        "000000"
                )
        );
    }

    @Test
    @DisplayName("인증 미완료 이메일은 isVerified가 false")
    void 인증하지_않은_이메일의_인증_완료_여부_false() {
        String email = randomEmail();

        // 발송 내역 자체가 없는 이메일은 인증 완료로 취급되면 안 됨
        assertFalse(emailVerificationService.isVerified(
                email,
                VerificationPurpose.SIGNUP)
        );
    }
}
