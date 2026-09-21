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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 회원가입 API를 실제 HTTP 요청으로 호출해 DTO 변환·Validation·응답 JSON과 DB 저장을 함께 검증
// Service만 직접 호출하는 AuthServiceTest와 달리 Controller부터 Repository까지 전체 요청 흐름을 확인

// SpringBootTest: 실제 애플리케이션과 동일하게 Spring Context 전체 실행
@SpringBootTest
// AutoConfigureMockMvc: 서버를 별도로 실행하지 않고 HTTP 요청·응답 계층 테스트
@AutoConfigureMockMvc
// 각 테스트에서 저장한 사용자와 이메일 인증 데이터를 종료 후 자동 롤백
@Transactional
class AuthControllerIntegrationTest {

    // 회원가입 API에 HTTP 요청을 보내고 응답 상태와 JSON body 검증
    @Autowired
    private MockMvc mockMvc;

    // 회원가입 선행조건인 이메일 인증 완료 상태 준비
    @Autowired
    private EmailVerificationRepository emailVerificationRepository;

    // API 호출 후 실제 사용자 설정이 DB에 저장됐는지 별도로 확인
    @Autowired
    private UserRepository userRepository;

    // 기존 계정 호환성 검증을 위한 비밀번호 해시 생성
    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final String PASSWORD_BYTE_ERROR =
            "비밀번호가 허용 길이를 초과했습니다. 더 짧게 작성해주세요.";

    // 테스트 간 이메일 UNIQUE 제약 충돌을 방지하기 위해 매번 다른 주소 생성
    private String randomEmail() {
        return "signup-" + UUID.randomUUID() + "@example.com";
    }

    // 회원가입 Service까지 도달하도록 사전에 완료된 이메일 인증 상태 준비
    private void completeEmailVerification(String email) {
        // 인증 코드 확인 자체가 테스트 목적이 아니므로 임의의 해시와 미래 만료 시각으로 인증 정보 생성
        EmailVerification verification = new EmailVerification(
                email,
                VerificationPurpose.SIGNUP,
                "00".repeat(32),
                Instant.now().plusSeconds(300)
        );

        // 실제 인증 코드 입력 과정은 생략하고 인증 완료 상태로 변경
        verification.markVerified(Instant.now());
        emailVerificationRepository.save(verification);
    }

    @Test
    @DisplayName("회원가입 시 선택한 기본 대화 수준을 저장하고 응답")
    void signUp_기본_대화_수준_저장과_응답() throws Exception {
        // 준비: 이메일 중복을 피할 새 주소와 완료된 회원가입용 이메일 인증
        String email = randomEmail();
        completeEmailVerification(email);

        // 실행 및 검증: LIGHT_CHAT을 포함해 회원가입하고 응답에도 같은 값이 반환되는지 확인
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "test1234",
                                  "nickname": "테스트",
                                  "defaultTalkLevel": "LIGHT_CHAT"
                                }
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email")
                        .value(email))
                .andExpect(jsonPath("$.defaultTalkLevel")
                        .value("LIGHT_CHAT"));

        // HTTP 응답뿐 아니라 User Entity에도 선택값이 그대로 저장됐는지 확인
        User savedUser = userRepository
                .findByEmailAndDeletedAtIsNull(email)
                .orElseThrow();

        assertThat(savedUser.getDefaultTalkLevel())
                .isEqualTo(DefaultTalkLevel.LIGHT_CHAT);
    }

    @Test
    @DisplayName("닉네임이 2~16자 규칙을 어기거나 공백뿐이면 한글 메시지와 함께 400")
    void signUp_닉네임_검증_실패는_한글_메시지로_400() throws Exception {
        // 검증은 Controller 단계에서 끝나므로 이메일 인증 데이터는 준비하지 않음
        String sizeMessage = "닉네임은 2자 이상 16자 이하로 입력해주세요.";

        for (String nickname : new String[]{"가", "가".repeat(17)}) {
            mockMvc.perform(post("/api/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(signUpBody(nickname)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(sizeMessage));
        }

        // 3칸 공백: 길이 규칙은 통과하지만 공백뿐이라 NotBlank에 걸린다
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signUpBody("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("닉네임을 입력해주세요."));
    }

    @Test
    @DisplayName("닉네임에 NUL 문자가 있으면 한글 메시지와 함께 400")
    void signUp_닉네임_NUL_문자_거부() throws Exception {
        // NUL 문자(0x00)는 PostgreSQL 저장 불가라 검증이 없으면 DB 단계에서 500
        // 검증은 Controller 단계에서 끝나므로 이메일 인증 데이터는 준비하지 않음
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signUpBody("a\\u0000b")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("닉네임에 사용할 수 없는 문자가 포함되어 있어요."));
    }

    // 닉네임만 바꿔 끼우는 가입 요청 바디 (나머지 필드는 검증을 통과하는 값)
    private String signUpBody(String nickname) {
        return """
                {
                  "email": "runner@example.com",
                  "password": "test1234",
                  "nickname": "%s",
                  "defaultTalkLevel": "SILENT"
                }
                """.formatted(nickname);
    }

    @Test
    @DisplayName("기본 대화 수준이 누락된 회원가입 요청은 400")
    void signUp_기본_대화_수준_누락_거부() throws Exception {
        // defaultTalkLevel을 보내지 않으면 @NotNull 검증에서 Service 호출 전에 400으로 차단
        // Controller 검증 단계에서 끝나므로 이메일 인증 데이터는 준비하지 않음
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "runner@example.com",
                                  "password": "test1234",
                                  "nickname": "테스트"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("허용되지 않은 기본 대화 수준의 회원가입 요청은 400")
    void signUp_잘못된_기본_대화_수준_거부() throws Exception {
        // Enum에 없는 TALKATIVE는 DTO 변환이 불가능하므로 HttpMessageNotReadableException 발생
        // GlobalExceptionHandler가 내부 예외를 노출하지 않고 공통 ErrorResponse 형식으로 변환하는지 검증
        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "runner@example.com",
                                  "password": "test1234",
                                  "nickname": "테스트",
                                  "defaultTalkLevel": "TALKATIVE"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다."));
    }

    @Test
    @DisplayName("UTF-8 기준 72바이트 비밀번호로 회원가입 성공")
    void signUp_72바이트_비밀번호_허용() throws Exception {
        String email = randomEmail();
        String password = "가".repeat(24); // 한글 24자 = UTF-8 기준 72바이트
        completeEmailVerification(email);

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s",
                                  "nickname": "테스트",
                                  "defaultTalkLevel": "SILENT"
                                }
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    @DisplayName("UTF-8 기준 72바이트를 초과한 회원가입 비밀번호는 400")
    void signUp_72바이트_초과_비밀번호_거부() throws Exception {
        // 한글 25자는 글자 수 정책(8~64자)은 만족하지만 UTF-8 기준으로는 75바이트
        String password = "가".repeat(25);

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "runner@example.com",
                                  "password": "%s",
                                  "nickname": "테스트",
                                  "defaultTalkLevel": "SILENT"
                                }
                                """.formatted(password)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(PASSWORD_BYTE_ERROR));
    }

    @Test
    @DisplayName("72바이트 비밀번호 로그인 성공 및 문자 추가 시 400")
    void login_72바이트_비밀번호_경계_검증() throws Exception {
        String email = randomEmail();
        // 영문 60자와 한글 4자로 총 64자·72바이트 구성
        String password = "a".repeat(60) + "가".repeat(4);
        completeEmailVerification(email);

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s",
                                  "nickname": "테스트",
                                  "defaultTalkLevel": "SILENT"
                                }
                                """.formatted(email, password)))
                .andExpect(status().isOk());

        // 정확히 72바이트인 원래 비밀번호의 정상 인증
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        // 같은 비밀번호 뒤에 한 글자를 붙인 73바이트 입력의 인증 처리 이전 차단
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(email, password + "a")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(PASSWORD_BYTE_ERROR));
    }

    @Test
    @DisplayName("기존 계정의 8자 미만 비밀번호도 로그인 허용")
    void login_기존_짧은_비밀번호_허용() throws Exception {
        String email = randomEmail();
        String password = "old1234"; // 과거 정책에서 생성됐다고 가정한 7자 비밀번호

        userRepository.save(new User(
                email,
                passwordEncoder.encode(password),
                "기존사용자",
                DefaultTalkLevel.SILENT,
                Instant.now()
        ));

        // 로그인에는 회원가입의 최소 8자 조건을 다시 적용하지 않음
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "%s"
                                }
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }
}
