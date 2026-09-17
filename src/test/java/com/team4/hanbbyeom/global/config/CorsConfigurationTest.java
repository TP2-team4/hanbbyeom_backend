package com.team4.hanbbyeom.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// CORS 설정이 실제 응답 헤더로 나가는지 검증

// CORS는 브라우저가 지키는 규칙이라 Postman·Swagger로는 확인되지 않음
// → 응답에 어떤 헤더가 실려 나가는지를 직접 확인해야 검증 가능

// properties로 허용 Origin을 고정하는 이유
// → 이 값은 .env의 CORS_ALLOWED_ORIGINS로 덮어쓸 수 있어서, 배포 주소를 설정해둔 환경에서는
//   localhost가 허용 목록에서 빠져 테스트가 실패함
// → 검증할 대상은 "설정한 Origin이 실제로 적용되는가"이므로, 설정값을 테스트가 직접 지정
@SpringBootTest(properties = "app.cors.allowed-origins=http://localhost:5173,http://localhost:3000")
@AutoConfigureMockMvc
class CorsConfigurationTest {

    @Autowired
    private MockMvc mockMvc;

    // 위 properties로 지정한 허용 목록에 포함된 주소
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    // 허용 목록에 없는 주소
    private static final String DISALLOWED_ORIGIN = "http://not-allowed.example.com";

    @Test
    @DisplayName("허용된 Origin의 preflight 요청에 허용 응답 반환")
    void 허용된_Origin_preflight() throws Exception {
        // preflight: 브라우저가 본 요청을 보내기 전에 OPTIONS로 먼저 물어보는 요청
        // Access-Control-Request-Method: "이 메서드로 보낼 건데 괜찮냐"고 알리는 헤더
        mockMvc.perform(options("/api/users/me")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                // 인증이 필요한 경로지만 preflight는 인가 판정 전에 처리되므로 401이 아님
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    @Test
    @DisplayName("허용되지 않은 Origin의 preflight 요청에 허용 헤더 미반환")
    void 허용되지_않은_Origin_preflight() throws Exception {
        mockMvc.perform(options("/api/users/me")
                        .header(HttpHeaders.ORIGIN, DISALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                // 허용 헤더가 없으면 브라우저가 응답을 JavaScript에 넘기지 않고 차단함
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("Authorization 헤더를 포함한 preflight 요청 허용")
    void Authorization_헤더_preflight() throws Exception {
        // Access-Control-Request-Headers: 본 요청에 어떤 헤더를 붙일 건지 미리 알리는 헤더
        // → Authorization이 허용되지 않으면 토큰을 실어 보내는 요청 자체가 브라우저에서 막힘
        mockMvc.perform(options("/api/users/me")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, HttpHeaders.AUTHORIZATION))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsString(HttpHeaders.AUTHORIZATION)));
    }

    @Test
    @DisplayName("교차 출처 401 응답에서 WWW-Authenticate를 읽을 수 있도록 노출")
    void 교차_출처_401_응답_헤더_노출() throws Exception {
        // 토큰 없이 보호 API를 호출해 401을 받는 상황
        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                // 노출 목록에 없으면 응답에 WWW-Authenticate가 있어도 프론트엔드에서 읽지 못해
                // "토큰 없음"과 "토큰이 잘못됨"을 구분할 수 없음
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        containsString(HttpHeaders.WWW_AUTHENTICATE)));
    }
}
