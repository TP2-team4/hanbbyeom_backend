package com.team4.hanbbyeom.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Swagger 문서에 Bearer 인증 방식이 반영됐는지 검증

// Swagger UI를 직접 눌러보는 대신 문서 데이터(/v3/api-docs)를 확인하는 이유
// → UI는 이 데이터를 그리기만 하므로, 데이터가 맞으면 Authorize 버튼도 정상 동작
// → 보호 API에는 인증이 걸리고 공개 API에는 안 걸렸는지를 자동으로 확인 가능
@SpringBootTest
@AutoConfigureMockMvc
class SwaggerSecuritySchemeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("API 문서에 bearerAuth 인증 방식이 정의됨")
    void 인증_방식_정의() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                // SwaggerConfig의 @SecurityScheme 설정이 그대로 문서에 들어갔는지 확인
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.description").value(
                        "로그인 API에서 발급받은 Access Token을 입력합니다. "
                                + "Bearer 접두사는 Swagger UI가 자동으로 추가합니다."));
    }

    @Test
    @DisplayName("보호 API에 bearerAuth 인증 요구가 표시됨")
    void 보호_API_인증_요구() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                // 내 정보 조회는 토큰이 필요한 API이므로 문서에도 인증 요구가 있어야 함
                .andExpect(jsonPath("$.paths['/api/users/me'].get.security[0].bearerAuth").isArray())
                // 기본 설정 변경도 본인만 호출할 수 있는 보호 API로 표시되어야 함
                .andExpect(jsonPath(
                        "$.paths['/api/users/me/preferences'].patch.security[0].bearerAuth").isArray())
                // 보호 Controller 5개가 모두 문서상 인증 대상으로 표시되는지 확인
                .andExpect(jsonPath(
                        "$.paths['/api/run/conditions/{id}'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath(
                        "$.paths['/api/matching/requests/{id}'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath(
                        "$.paths['/api/matching/board'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath(
                        "$.paths['/api/matching/matches/{activityMatchId}/applicant-profile']"
                                + ".get.security[0].bearerAuth").isArray());
    }

    @Test
    @DisplayName("공개 API에는 인증 요구가 표시되지 않음")
    void 공개_API_인증_미요구() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                // 로그인·회원가입은 토큰 없이 호출하는 API이므로 인증 요구가 없어야 함
                // → 있으면 Swagger UI가 Authorize 전에는 호출할 수 없는 것처럼 오해를 줌

                // 각 경로마다 문서에 존재하는지 먼저 확인하는 이유
                // → doesNotExist()는 경로 자체가 문서에 없을 때도 통과함
                //   경로가 바뀌거나 사라지면 검증이 조용히 무의미해지므로, 존재 확인을 함께 둠
                .andExpect(jsonPath("$.paths['/api/auth/login'].post").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.security").doesNotExist())

                .andExpect(jsonPath("$.paths['/api/auth/signup'].post").exists())
                .andExpect(jsonPath("$.paths['/api/auth/signup'].post.security").doesNotExist())

                .andExpect(jsonPath("$.paths['/api/auth/email-verifications'].post").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/auth/email-verifications'].post.security").doesNotExist())

                .andExpect(jsonPath("$.paths['/api/auth/email-verifications/confirm'].post").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/auth/email-verifications/confirm'].post.security").doesNotExist())

                // 공개 데이터인 러닝 코스 목록 조회도 동일
                .andExpect(jsonPath("$.paths['/api/run/courses'].get").exists())
                .andExpect(jsonPath("$.paths['/api/run/courses'].get.security").doesNotExist());
    }

    @Test
    @DisplayName("인증·사용자 DTO의 설명과 선택 가능한 값이 문서에 포함됨")
    void 인증_및_사용자_DTO_문서화() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                // 설명 문구는 앞으로 다듬을 수 있으므로 내용까지 고정하지 않고 존재 여부만 확인
                // 요청 DTO 4개
                .andExpect(jsonPath("$.components.schemas.SignUpRequest.description").exists())
                .andExpect(jsonPath("$.components.schemas.LoginRequest.description").exists())
                .andExpect(jsonPath(
                        "$.components.schemas.EmailVerificationSendRequest.description").exists())
                .andExpect(jsonPath(
                        "$.components.schemas.EmailVerificationConfirmRequest.description").exists())
                // 응답 DTO 3개
                .andExpect(jsonPath("$.components.schemas.SignUpResponse.description").exists())
                .andExpect(jsonPath("$.components.schemas.LoginResponse.description").exists())
                .andExpect(jsonPath("$.components.schemas.UserResponse.description").exists())
                // 비밀번호 필드가 OpenAPI의 password 형식으로 표시되는지 확인
                .andExpect(jsonPath(
                        "$.components.schemas.SignUpRequest.properties.password.description").exists())
                .andExpect(jsonPath(
                        "$.components.schemas.SignUpRequest.properties.password.format").value("password"))
                .andExpect(jsonPath(
                        "$.components.schemas.LoginRequest.properties.password.format").value("password"))

                // 프론트엔드가 회원가입 요청에 보낼 필드의 의미와 허용값을 문서만 보고 알 수 있는지 확인
                // enum 값을 정확히 검증해 서버와 다른 문자열을 선택지로 사용하는 것을 방지
                .andExpect(jsonPath(
                        "$.components.schemas.SignUpRequest.properties.defaultTalkLevel.description").exists())
                .andExpect(jsonPath(
                        "$.components.schemas.SignUpRequest.properties.defaultTalkLevel.enum")
                        .value(containsInAnyOrder("SILENT", "LIGHT_CHAT")))

                // 회원가입과 내 정보 조회 응답에도 저장된 기본 대화 수준의 설명이 표시되는지 확인
                .andExpect(jsonPath(
                        "$.components.schemas.SignUpResponse.properties.defaultTalkLevel.description").exists())
                .andExpect(jsonPath(
                        "$.components.schemas.UserResponse.properties.defaultTalkLevel.description").exists())
                // 설정 변경 요청과 응답에도 필드 설명과 허용 가능한 enum 값이 표시되는지 확인
                .andExpect(jsonPath(
                        "$.components.schemas.UserPreferencesUpdateRequest.description").exists())
                .andExpect(jsonPath(
                        "$.components.schemas.UserPreferencesUpdateRequest.properties.defaultTalkLevel.enum")
                        .value(containsInAnyOrder("SILENT", "LIGHT_CHAT")))
                .andExpect(jsonPath(
                        "$.components.schemas.UserPreferencesResponse.description").exists())
                .andExpect(jsonPath(
                        "$.components.schemas.UserPreferencesResponse.properties.defaultTalkLevel.description")
                        .exists())
                .andExpect(jsonPath(
                        "$.components.schemas.LoginResponse.properties.accessToken.description").exists())
                // purpose는 문서를 보지 않으면 알 수 없는 값이라, 선택지가 실제로 노출되는지 확인
                // (프론트엔드가 SIGNUP·PASSWORD_RESET을 문서만 보고 알 수 있어야 함)
                // enum은 별도 스키마로 분리되지 않고 속성 안에 그대로 들어감
                .andExpect(jsonPath(
                        "$.components.schemas.EmailVerificationSendRequest.properties.purpose.enum")
                        .value(containsInAnyOrder("SIGNUP", "PASSWORD_RESET")));
    }
}
