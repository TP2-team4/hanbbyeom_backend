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
}
