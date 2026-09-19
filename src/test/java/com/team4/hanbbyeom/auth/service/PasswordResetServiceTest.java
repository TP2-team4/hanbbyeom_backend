package com.team4.hanbbyeom.auth.service;

import com.team4.hanbbyeom.auth.domain.EmailVerification;
import com.team4.hanbbyeom.auth.domain.VerificationPurpose;
import com.team4.hanbbyeom.auth.dto.PasswordResetRequest;
import com.team4.hanbbyeom.auth.exception.PasswordResetAuthenticationException;
import com.team4.hanbbyeom.auth.repository.EmailVerificationRepository;
import com.team4.hanbbyeom.user.domain.DefaultTalkLevel;
import com.team4.hanbbyeom.user.domain.User;
import com.team4.hanbbyeom.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.AopTestUtils;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// PasswordResetService의 인증 검증·비밀번호 변경·인증 기록 소비 로직을 실제 PostgreSQL로 검증
// 동시 요청 검증을 위한 테스트별 독립 트랜잭션 사용과 @AfterEach 직접 데이터 정리
@SpringBootTest
@Import(PasswordResetServiceTest.FixedClockConfig.class)
class PasswordResetServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-19T12:00:00Z");
    private static final String CURRENT_PASSWORD = "current1234";
    private static final String NEW_PASSWORD = "changed1234";
    private static final String VERIFICATION_CODE = "123456";
    private static final String AUTH_FAILURE_MESSAGE =
            "비밀번호 재설정 인증 정보가 올바르지 않거나 만료되었습니다.";

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private EmailVerificationRepository emailVerificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 테스트에서 생성한 사용자와 인증 기록의 직접 정리를 위한 이메일 목록
    private final List<String> createdEmails = new ArrayList<>();

    // 재설정 유효시간과 동시 요청에서 공통으로 사용할 스레드 안전한 고정 시각
    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            // clock.instant(): 그 Clock 기준의 현재 시각을 Instant로 가져오는 메서드
            // clock.getZone(): 그 Clock이 어떤 시간대(Time Zone)를 사용하는지 가져오는 메서드
            // Clock.fixed(): (Java 제공) Clock 객체(시간이 흐르지 않고 항상 똑같은 시각을 반환하는 Clock)를 만드는 메서드

            // Mockito: 테스트용 가짜 객체를 만드는 라이브러리
            // Mock: Mockito가 만든 가짜 객체
            // Stubbing: 만든 가짜 객체가 어떻게 행동할지 설정하는 것

            // 동시성 테스트에서 가짜 Clock을 Mockito로 설정해 쓰다 보니 테스트 설정 때문에 문제 발생
            // 서비스는 현재 시각만 필요하므로 복잡하게 Mock을 만들지 말고,
            // 항상 같은 시각을 반환하는 실제 Clock.fixed()를 테스트에 넣어서 더 안정적으로 테스트하기로 결정
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @AfterEach
    void 테스트_데이터_정리() {
        for (String email : createdEmails) {
            jdbcTemplate.update(
                    "DELETE FROM email_verifications WHERE email = ?",
                    email
            );
            jdbcTemplate.update(
                    "DELETE FROM users WHERE email = ?",
                    email
            );
        }
    }

    // 테스트마다 겹치지 않는 이메일 생성
    private String 새_이메일() {
        String email = "password-reset-" + UUID.randomUUID() + "@example.com";
        createdEmails.add(email);
        return email;
    }

    // 비밀번호 재설정 대상 사용자 생성
    private User 가입_사용자_생성(String email) {
        String nickname = "재설정" + UUID.randomUUID().toString().substring(0, 8);

        return userRepository.saveAndFlush(
                new User(
                        email,
                        passwordEncoder.encode(CURRENT_PASSWORD),
                        nickname,
                        DefaultTalkLevel.SILENT,
                        FIXED_NOW.minusSeconds(3600)
                )
        );
    }

    // 지정한 목적과 인증 완료 시각을 가진 이메일 인증 기록 생성
    private EmailVerification 인증_기록_생성(
            String email,
            VerificationPurpose purpose,
            String code,
            Instant verifiedAt
    ) throws Exception {
        EmailVerification verification = new EmailVerification(
                email,
                purpose,
                인증_코드_해시(code),
                FIXED_NOW.plusSeconds(300)
        );

        if (verifiedAt != null) {
            verification.markVerified(verifiedAt);
        }

        return emailVerificationRepository.saveAndFlush(verification);
    }

    // 운영 코드와 같은 HMAC 해시 생성을 위한 private 메서드 호출
    private String 인증_코드_해시(String code) throws Exception {
        EmailVerificationService target =
                AopTestUtils.getUltimateTargetObject(emailVerificationService);
        Method method = EmailVerificationService.class
                .getDeclaredMethod("hash", String.class);
        method.setAccessible(true);
        return (String) method.invoke(target, code);
    }

    // 사용자 존재 여부와 상세 인증 실패 원인에 공통으로 적용할 예외 문구 확인
    private void 공통_인증_실패_확인(Executable request) {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                request
        );
        assertEquals(AUTH_FAILURE_MESSAGE, exception.getMessage());
    }

    private PasswordResetRequest 재설정_요청(String email, String code, String newPassword) {
        return new PasswordResetRequest(email, code, newPassword);
    }

    @Test
    @DisplayName("비밀번호 재설정 성공 및 모든 재설정 인증 기록 삭제")
    void 비밀번호_재설정_성공과_모든_인증_기록_삭제() throws Exception {
        String email = 새_이메일();
        User user = 가입_사용자_생성(email);

        // 이전 기록 재사용 방지 검증을 위한 인증 완료 기록 2건 생성
        인증_기록_생성(
                email,
                VerificationPurpose.PASSWORD_RESET,
                VERIFICATION_CODE,
                FIXED_NOW.minusSeconds(120)
        );
        인증_기록_생성(
                email,
                VerificationPurpose.PASSWORD_RESET,
                VERIFICATION_CODE,
                FIXED_NOW.minusSeconds(60)
        );

        passwordResetService.resetPassword(
                재설정_요청(email, VERIFICATION_CODE, NEW_PASSWORD)
        );

        User savedUser = userRepository.findById(user.getId()).orElseThrow();

        // 새 비밀번호 해시 적용과 기존 비밀번호 불일치 확인
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, savedUser.getPasswordHash()));
        assertFalse(passwordEncoder.matches(CURRENT_PASSWORD, savedUser.getPasswordHash()));

        // 과거 기록을 포함한 PASSWORD_RESET 인증 기록 전체 삭제 확인
        assertTrue(
                emailVerificationRepository
                        .findTopByEmailAndPurposeOrderByCreatedAtDescIdDesc(
                                email,
                                VerificationPurpose.PASSWORD_RESET
                        )
                        .isEmpty()
        );

        // 삭제된 최신 기록 뒤의 이전 인증 기록 재사용 차단 확인
        공통_인증_실패_확인(
                () -> passwordResetService.resetPassword(
                        재설정_요청(email, VERIFICATION_CODE, "another1234")
                )
        );
    }

    @Test
    @DisplayName("가입되지 않은 이메일의 비밀번호 재설정 인증 실패")
    void 가입되지_않은_이메일의_비밀번호_재설정_인증_실패() {
        String email = 새_이메일();

        공통_인증_실패_확인(
                () -> passwordResetService.resetPassword(
                        재설정_요청(email, VERIFICATION_CODE, NEW_PASSWORD)
                )
        );
    }

    @Test
    @DisplayName("회원가입 인증 기록을 이용한 비밀번호 재설정 거부")
    void 회원가입_인증_기록의_비밀번호_재설정_사용_거부() throws Exception {
        String email = 새_이메일();
        가입_사용자_생성(email);
        인증_기록_생성(
                email,
                VerificationPurpose.SIGNUP,
                VERIFICATION_CODE,
                FIXED_NOW.minusSeconds(60)
        );

        공통_인증_실패_확인(
                () -> passwordResetService.resetPassword(
                        재설정_요청(email, VERIFICATION_CODE, NEW_PASSWORD)
                )
        );
    }

    @Test
    @DisplayName("완료되지 않은 재설정 인증 기록 사용 거부")
    void 완료되지_않은_재설정_인증_기록_사용_거부() throws Exception {
        String email = 새_이메일();
        가입_사용자_생성(email);
        인증_기록_생성(
                email,
                VerificationPurpose.PASSWORD_RESET,
                VERIFICATION_CODE,
                null
        );

        공통_인증_실패_확인(
                () -> passwordResetService.resetPassword(
                        재설정_요청(email, VERIFICATION_CODE, NEW_PASSWORD)
                )
        );
    }

    @Test
    @DisplayName("인증 완료 후 10분이 지난 재설정 요청 거부")
    void 인증_완료_후_10분이_지난_재설정_요청_거부() throws Exception {
        String email = 새_이메일();
        가입_사용자_생성(email);
        인증_기록_생성(
                email,
                VerificationPurpose.PASSWORD_RESET,
                VERIFICATION_CODE,
                FIXED_NOW.minusSeconds(601)
        );

        공통_인증_실패_확인(
                () -> passwordResetService.resetPassword(
                        재설정_요청(email, VERIFICATION_CODE, NEW_PASSWORD)
                )
        );
    }

    @Test
    @DisplayName("잘못된 인증 코드 거부 및 실패 횟수 저장")
    void 잘못된_인증_코드_거부와_실패_횟수_저장() throws Exception {
        String email = 새_이메일();
        가입_사용자_생성(email);
        EmailVerification verification = 인증_기록_생성(
                email,
                VerificationPurpose.PASSWORD_RESET,
                VERIFICATION_CODE,
                FIXED_NOW.minusSeconds(60)
        );

        공통_인증_실패_확인(
                () -> passwordResetService.resetPassword(
                        재설정_요청(email, "000000", NEW_PASSWORD)
                )
        );

        EmailVerification savedVerification = emailVerificationRepository
                .findById(verification.getId())
                .orElseThrow();
        assertEquals(1, savedVerification.getAttemptCount());
    }

    @Test
    @DisplayName("인증 시도 횟수를 초과한 재설정 요청 거부")
    void 인증_시도_횟수_초과_재설정_요청_거부() throws Exception {
        String email = 새_이메일();
        가입_사용자_생성(email);
        EmailVerification verification = 인증_기록_생성(
                email,
                VerificationPurpose.PASSWORD_RESET,
                VERIFICATION_CODE,
                FIXED_NOW.minusSeconds(60)
        );
        for (int i = 0; i < 5; i++) {
            verification.increaseAttemptCount();
        }
        emailVerificationRepository.saveAndFlush(verification);

        공통_인증_실패_확인(
                () -> passwordResetService.resetPassword(
                        재설정_요청(email, VERIFICATION_CODE, NEW_PASSWORD)
                )
        );
    }

    @Test
    @DisplayName("현재 비밀번호 재사용 거부 후 다른 비밀번호로 재설정 성공")
    void 현재_비밀번호_재사용_거부_후_다른_비밀번호로_재설정_성공() throws Exception {
        String email = 새_이메일();
        가입_사용자_생성(email);
        인증_기록_생성(
                email,
                VerificationPurpose.PASSWORD_RESET,
                VERIFICATION_CODE,
                FIXED_NOW.minusSeconds(60)
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> passwordResetService.resetPassword(
                        재설정_요청(email, VERIFICATION_CODE, CURRENT_PASSWORD)
                )
        );
        assertEquals(
                "기존에 사용하던 비밀번호로는 변경할 수 없습니다.",
                exception.getMessage()
        );

        // 동일 비밀번호 요청 실패 후 인증 기록을 재사용한 정상 변경
        passwordResetService.resetPassword(
                재설정_요청(email, VERIFICATION_CODE, NEW_PASSWORD)
        );

        User savedUser = userRepository
                .findByEmailAndDeletedAtIsNull(email)
                .orElseThrow();
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, savedUser.getPasswordHash()));
    }

    @Test
    @DisplayName("동시 비밀번호 재설정 요청 중 하나만 성공")
    void 동시_비밀번호_재설정_요청_중_하나만_성공() throws Exception {
        String email = 새_이메일();
        가입_사용자_생성(email);
        인증_기록_생성(
                email,
                VerificationPurpose.PASSWORD_RESET,
                VERIFICATION_CODE,
                FIXED_NOW.minusSeconds(60)
        );

        PasswordResetRequest request =
                재설정_요청(email, VERIFICATION_CODE, NEW_PASSWORD);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Object> firstResult = new AtomicReference<>();
        AtomicReference<Object> secondResult = new AtomicReference<>();

        Callable<Void> firstTask = 재설정_작업(request, ready, start, firstResult);
        Callable<Void> secondTask = 재설정_작업(request, ready, start, secondResult);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var firstFuture = executor.submit(firstTask);
            var secondFuture = executor.submit(secondTask);

            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            firstFuture.get(5, TimeUnit.SECONDS);
            secondFuture.get(5, TimeUnit.SECONDS);
        } finally {
            // 테스트 실패 시 대기 중인 작업의 해제와 스레드 정리
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        List<Object> results = List.of(firstResult.get(), secondResult.get());
        long successCount = results.stream()
                .filter("SUCCESS"::equals)
                .count();
        long failureCount = results.stream()
                .filter(PasswordResetAuthenticationException.class::isInstance)
                .count();

        assertEquals(1, successCount, results.toString());
        assertEquals(1, failureCount, results.toString());
    }

    // 두 작업의 동시 시작과 결과 저장을 위한 비밀번호 재설정 작업 생성
    private Callable<Void> 재설정_작업(
            PasswordResetRequest request,
            CountDownLatch ready,
            CountDownLatch start,
            AtomicReference<Object> result
    ) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                passwordResetService.resetPassword(request);
                result.set("SUCCESS");
            } catch (Exception e) {
                result.set(e);
            }
            return null;
        };
    }
}
