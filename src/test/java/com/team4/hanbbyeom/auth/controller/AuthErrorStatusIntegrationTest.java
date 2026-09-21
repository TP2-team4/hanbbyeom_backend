package com.team4.hanbbyeom.auth.controller;

import com.team4.hanbbyeom.auth.domain.EmailVerification;
import com.team4.hanbbyeom.auth.domain.VerificationPurpose;
import com.team4.hanbbyeom.auth.repository.EmailVerificationRepository;
import com.team4.hanbbyeom.user.domain.DefaultTalkLevel;
import com.team4.hanbbyeom.user.domain.User;
import com.team4.hanbbyeom.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// auth 도메인 실패 응답의 HTTP 상태 코드 고정 (이슈 #118)
//
// 같은 4xx여도 코드가 다르면 사용자가 취할 행동이 다름
// → 400은 요청 자체가 잘못된 경우, 409는 새 인증 코드가 필요한 상태 충돌, 429는 대기 후 재시도로 해결되는 제한
// 상태 충돌을 409로 옮기면서 입력값 검증까지 휩쓸지 않았는지 양방향으로 확인
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthErrorStatusIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmailVerificationRepository emailVerificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // 인증 코드 확인 요청이 실제로 비교하는 해시와 무관하게 상태 검증에 먼저 걸리므로 임의 값 사용
    private static final String DUMMY_CODE_HASH = "00".repeat(32);
    private static final String ANY_CODE = "123456";

    // 테스트 간 이메일 UNIQUE 제약 충돌 방지
    private String randomEmail() {
        return "auth-status-" + UUID.randomUUID() + "@example.com";
    }

    // 지정한 목적의 인증 기록 생성, 만료 시각은 호출부에서 지정
    private EmailVerification saveVerification(String email, VerificationPurpose purpose, Instant expiresAt) {
        return emailVerificationRepository.saveAndFlush(
                new EmailVerification(email, purpose, DUMMY_CODE_HASH, expiresAt));
    }

    private void saveUser(String email) {
        userRepository.saveAndFlush(new User(
                email,
                passwordEncoder.encode("password1234"),
                "상태코드" + UUID.randomUUID().toString().substring(0, 6),
                DefaultTalkLevel.SILENT,
                Instant.now()));
    }

    private ResultActions sendCode(String email, VerificationPurpose purpose) throws Exception {
        return mockMvc.perform(post("/api/auth/email-verifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "purpose": "%s"}
                        """.formatted(email, purpose.name())));
    }

    private ResultActions confirmCode(String email, VerificationPurpose purpose, String code) throws Exception {
        return mockMvc.perform(post("/api/auth/email-verifications/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "purpose": "%s", "code": "%s"}
                        """.formatted(email, purpose.name(), code)));
    }

    // 409: 새 인증 코드를 요청해야 해소되는 상태 충돌

    @Test
    @DisplayName("이미 가입된 이메일의 회원가입 요청은 409")
    void 중복_가입_요청_409() throws Exception {
        String email = randomEmail();
        saveUser(email);
        EmailVerification verification = saveVerification(
                email, VerificationPurpose.SIGNUP, Instant.now().plusSeconds(300));
        verification.markVerified(Instant.now());
        emailVerificationRepository.saveAndFlush(verification);

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "password1234",
                                  "nickname": "중복가입",
                                  "defaultTalkLevel": "SILENT"
                                }
                                """.formatted(email)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 가입된 이메일입니다."));
    }

    @Test
    @DisplayName("발송 내역이 없는 이메일의 인증 코드 확인은 409")
    void 발송_내역_없는_인증_코드_확인_409() throws Exception {
        confirmCode(randomEmail(), VerificationPurpose.SIGNUP, ANY_CODE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("인증 코드 발송 내역이 없습니다. 인증 코드를 먼저 요청해주세요."));
    }

    @Test
    @DisplayName("이미 인증에 사용된 코드의 재확인은 409")
    void 이미_사용된_인증_코드_재확인_409() throws Exception {
        String email = randomEmail();
        EmailVerification verification = saveVerification(
                email, VerificationPurpose.SIGNUP, Instant.now().plusSeconds(300));
        verification.markVerified(Instant.now());
        emailVerificationRepository.saveAndFlush(verification);

        confirmCode(email, VerificationPurpose.SIGNUP, ANY_CODE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 사용된 인증 코드입니다."));
    }

    @Test
    @DisplayName("만료된 인증 코드의 확인은 409")
    void 만료된_인증_코드_확인_409() throws Exception {
        String email = randomEmail();
        saveVerification(email, VerificationPurpose.SIGNUP, Instant.now().minusSeconds(1));

        confirmCode(email, VerificationPurpose.SIGNUP, ANY_CODE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("인증 코드가 만료되었습니다. 인증 코드를 다시 요청해주세요."));
    }

    @Test
    @DisplayName("시도 횟수를 초과한 인증 코드의 확인은 409")
    void 시도_횟수_초과_인증_코드_확인_409() throws Exception {
        String email = randomEmail();
        EmailVerification verification = saveVerification(
                email, VerificationPurpose.SIGNUP, Instant.now().plusSeconds(300));
        // 허용 횟수(5회)를 모두 소진한 상태 재현
        for (int i = 0; i < 5; i++) {
            verification.increaseAttemptCount();
        }
        emailVerificationRepository.saveAndFlush(verification);

        confirmCode(email, VerificationPurpose.SIGNUP, ANY_CODE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("인증 시도 횟수를 초과했습니다. 인증 코드를 다시 요청해주세요."));
    }

    // 429: 대기 후 재요청으로 해결되는 일시적 제한

    @Test
    @DisplayName("재발송 대기시간 이내의 SIGNUP 인증 코드 재요청은 429")
    void 재발송_대기시간_이내_재요청_429() throws Exception {
        String email = randomEmail();
        // 방금 발송한 기록을 만들어 60초 제한에 해당하는 상태 재현
        saveVerification(email, VerificationPurpose.SIGNUP, Instant.now().plusSeconds(300));

        sendCode(email, VerificationPurpose.SIGNUP)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("인증 코드는 잠시 후 다시 요청할 수 있습니다."));
    }

    @Test
    @DisplayName("PASSWORD_RESET은 재발송 제한 중에도 가입 여부 비노출을 위해 200")
    void 비밀번호_재설정_재발송_제한_200_유지() throws Exception {
        String email = randomEmail();
        saveUser(email);
        saveVerification(email, VerificationPurpose.PASSWORD_RESET, Instant.now().plusSeconds(300));

        sendCode(email, VerificationPurpose.PASSWORD_RESET)
                .andExpect(status().isOk());
    }

    // 400: 잘못된 요청 값으로 발생하는 오류가 409로 처리되지 않는지 확인

    @Test
    @DisplayName("인증 코드 불일치는 400, 남은 시도 안에서 재입력 가능")
    void 인증_코드_불일치_400() throws Exception {
        String email = randomEmail();
        saveVerification(email, VerificationPurpose.SIGNUP, Instant.now().plusSeconds(300));

        confirmCode(email, VerificationPurpose.SIGNUP, "999999")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("6자리 숫자가 아닌 인증 코드는 400")
    void 인증_코드_형식_오류_400() throws Exception {
        confirmCode(randomEmail(), VerificationPurpose.SIGNUP, "12AB")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("비밀번호 재설정 인증 실패는 계정 존재 여부 비노출을 위해 400 유지")
    void 비밀번호_재설정_인증_실패_400_유지() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "code": "%s",
                                  "newPassword": "newPassword1234"
                                }
                                """.formatted(randomEmail(), ANY_CODE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("비밀번호 재설정 인증 정보가 올바르지 않거나 만료되었습니다."));
    }
}
