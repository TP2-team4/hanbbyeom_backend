package com.team4.hanbbyeom.auth.controller;

import com.team4.hanbbyeom.auth.domain.EmailVerification;
import com.team4.hanbbyeom.auth.domain.VerificationPurpose;
import com.team4.hanbbyeom.auth.repository.EmailVerificationRepository;
import com.team4.hanbbyeom.auth.service.EmailVerificationService;
import com.team4.hanbbyeom.user.domain.DefaultTalkLevel;
import com.team4.hanbbyeom.user.domain.User;
import com.team4.hanbbyeom.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 비밀번호 재설정 API의 공개 접근·요청 검증·비밀번호 변경 흐름을 실제 PostgreSQL로 검증
// 서비스 트랜잭션의 실제 커밋과 재사용 차단 확인을 위한 테스트별 직접 데이터 정리
@SpringBootTest
@AutoConfigureMockMvc
class PasswordResetControllerIntegrationTest {

    private static final String CURRENT_PASSWORD = "current1234";
    private static final String NEW_PASSWORD = "changed1234";
    private static final String VERIFICATION_CODE = "123456";
    private static final String AUTH_FAILURE_MESSAGE =
            "비밀번호 재설정 인증 정보가 올바르지 않거나 만료되었습니다.";
    private static final String PASSWORD_BYTE_ERROR =
            "비밀번호가 허용 길이를 초과했습니다. 더 짧게 작성해주세요.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationRepository emailVerificationRepository;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 테스트에서 생성한 사용자와 인증 기록의 직접 정리를 위한 이메일 목록
    private final List<String> createdEmails = new ArrayList<>();

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
        String email = "password-reset-api-" + UUID.randomUUID() + "@example.com";
        createdEmails.add(email);
        return email;
    }

    // 비밀번호 재설정 대상 사용자 생성
    private void 가입_사용자_생성(String email) {
        String nickname = "재설정API" + UUID.randomUUID().toString().substring(0, 6);

        userRepository.saveAndFlush(
                new User(
                        email,
                        passwordEncoder.encode(CURRENT_PASSWORD),
                        nickname,
                        DefaultTalkLevel.SILENT,
                        Instant.now().minusSeconds(3600)
                )
        );
    }

    // 확인을 완료한 PASSWORD_RESET 인증 기록 생성
    private void 재설정_인증_완료(String email) throws Exception {
        EmailVerification verification = new EmailVerification(
                email,
                VerificationPurpose.PASSWORD_RESET,
                인증_코드_해시(VERIFICATION_CODE),
                Instant.now().plusSeconds(300)
        );
        verification.markVerified(Instant.now());
        emailVerificationRepository.saveAndFlush(verification);
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

    // 비밀번호 재설정 요청 JSON 생성
    private String 재설정_요청_본문(String email, String code, String newPassword) {
        return """
                {
                  "email": "%s",
                  "code": "%s",
                  "newPassword": "%s"
                }
                """.formatted(email, code, newPassword);
    }

    @Test
    @DisplayName("비밀번호 재설정 성공 후 기존 비밀번호 로그인 실패 및 새 비밀번호 로그인 성공")
    void 비밀번호_재설정_성공과_로그인_비밀번호_변경() throws Exception {
        String email = 새_이메일();
        가입_사용자_생성(email);
        재설정_인증_완료(email);

        // Access Token 없이 호출 가능한 공개 API와 204 응답 확인
        mockMvc.perform(post("/api/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(재설정_요청_본문(
                                email,
                                VERIFICATION_CODE,
                                NEW_PASSWORD
                        )))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        // 변경 전 비밀번호의 로그인 실패
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(email, CURRENT_PASSWORD)))
                .andExpect(status().isUnauthorized());

        // 변경된 비밀번호의 로그인 성공과 Access Token 발급
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(email, NEW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        // 성공한 인증 기록의 재사용 차단
        mockMvc.perform(post("/api/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(재설정_요청_본문(
                                email,
                                VERIFICATION_CODE,
                                "another1234"
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(AUTH_FAILURE_MESSAGE));
    }

    @Test
    @DisplayName("가입 여부와 인증 코드 불일치의 동일한 재설정 실패 응답")
    void 가입_여부와_인증_코드_불일치의_동일한_실패_응답() throws Exception {
        String unknownEmail = 새_이메일();

        mockMvc.perform(post("/api/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(재설정_요청_본문(
                                unknownEmail,
                                VERIFICATION_CODE,
                                NEW_PASSWORD
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(AUTH_FAILURE_MESSAGE));

        String registeredEmail = 새_이메일();
        가입_사용자_생성(registeredEmail);
        재설정_인증_완료(registeredEmail);

        mockMvc.perform(post("/api/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(재설정_요청_본문(
                                registeredEmail,
                                "000000",
                                NEW_PASSWORD
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(AUTH_FAILURE_MESSAGE));
    }

    @Test
    @DisplayName("현재 비밀번호 재사용 거부 후 다른 비밀번호로 재설정 성공")
    void 현재_비밀번호_재사용_거부_후_다른_비밀번호로_재설정_성공() throws Exception {
        String email = 새_이메일();
        가입_사용자_생성(email);
        재설정_인증_완료(email);

        mockMvc.perform(post("/api/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(재설정_요청_본문(
                                email,
                                VERIFICATION_CODE,
                                CURRENT_PASSWORD
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "기존에 사용하던 비밀번호로는 변경할 수 없습니다."
                ));

        // 실패한 요청에서 인증 기록이 소비되지 않아 다른 비밀번호로 재시도 가능
        mockMvc.perform(post("/api/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(재설정_요청_본문(
                                email,
                                VERIFICATION_CODE,
                                NEW_PASSWORD
                        )))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("6자리 숫자가 아닌 인증 코드는 400")
    void 인증_코드_형식_검증_실패() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(재설정_요청_본문(
                                "runner@example.com",
                                "12AB",
                                NEW_PASSWORD
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "인증 코드는 6자리 숫자로 입력해주세요."
                ));
    }

    @Test
    @DisplayName("UTF-8 기준 72바이트 새 비밀번호 허용 및 73바이트 입력 거부")
    void 새_비밀번호_72바이트와_73바이트_경계_검증() throws Exception {
        String overLimitPassword = "a".repeat(58) + "가".repeat(5); // 63자·73바이트

        mockMvc.perform(post("/api/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(재설정_요청_본문(
                                "runner@example.com",
                                VERIFICATION_CODE,
                                overLimitPassword
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PASSWORD_BYTE_ERROR));

        String email = 새_이메일();
        String maxBytePassword = "a".repeat(60) + "가".repeat(4); // 64자·72바이트
        가입_사용자_생성(email);
        재설정_인증_완료(email);

        mockMvc.perform(post("/api/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(재설정_요청_본문(
                                email,
                                VERIFICATION_CODE,
                                maxBytePassword
                        )))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("앞뒤 공백과 대소문자가 포함된 새 비밀번호의 원문 유지")
    void 새_비밀번호_원문_유지() throws Exception {
        String email = 새_이메일();
        가입_사용자_생성(email);
        재설정_인증_완료(email);

        // 앞뒤 공백·중간 공백·대문자가 섞인 원문 (18자·18바이트)
        // @NotBlank는 공백뿐인 값만 거르므로 앞뒤 공백이 있어도 검증 통과
        String rawPassword = "  Pass Word 1234  ";

        mockMvc.perform(post("/api/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(재설정_요청_본문(
                                email,
                                VERIFICATION_CODE,
                                rawPassword
                        )))
                .andExpect(status().isNoContent());

        // 이메일과 달리 비밀번호는 공백 제거·소문자 변환 없이 원문 그대로 암호화되는지 확인
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(email, rawPassword)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        // 다듬은 값으로는 로그인 불가. 저장 시 원문이 변형되지 않았음을 반대편에서 확인
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(email, rawPassword.trim())))
                .andExpect(status().isUnauthorized());
    }
}
