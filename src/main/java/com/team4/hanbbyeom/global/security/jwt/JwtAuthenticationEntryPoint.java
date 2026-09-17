package com.team4.hanbbyeom.global.security.jwt;

import com.team4.hanbbyeom.global.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

// 인증되지 않은 요청에 공통 형식의 401 Unauthorized(인증실패) 응답을 작성하는 Security 계층의 진입점
// Security Filter에서 발생한 인증 실패는 Controller까지 도달하지 않으므로 핸들러로 처리 불가
// Security 영역에서의 인증실패 처리를 위해 AuthenticationEntryPoint에서 직접 응답을 작성

// AuthenticationEntryPoint: (Spring Security 제공) 인증되지 않은 사용자가 인증이 필요한 곳에 접근했을 때 어떻게 응답할지 정의
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    // WWW-Authenticate: 401 응답과 함께 "어떤 방식으로 인증해야 하는지" 알려주는 표준 헤더

    // 인증 정보를 아예 안 보낸 경우 (토큰 없이 보호 API 호출)
    private static final String BEARER_CHALLENGE = "Bearer";

    // Bearer 토큰을 보내긴 했지만 그 토큰이 잘못된 경우 (만료·위조·탈퇴 사용자 등)
    // error="invalid_token": 인증 방식이 아니라 토큰 자체가 문제라는 표준 오류 코드 (RFC 6750)
    // → 클라이언트가 "로그인 화면으로 보낼지 / 토큰만 다시 받아올지"를 구분해서 판단 가능
    private static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";

    // ObjectMapper: Java 객체와 JSON 사이를 변환해주는 객체
    // ErrorResponse 객체를 JSON 응답 본문으로 변환하기 위해 사용
    private final ObjectMapper objectMapper;

    @Override
    // void: response 객체에 직접 HTTP 응답을 작성하므로 반환값 필요 X
    public void commence(
            HttpServletRequest request, // 클라이언트가 서버로 보낸 HTTP 요청 정보
            HttpServletResponse response, // 서버가 클라이언트에게 보낼 HTTP 응답 (작성할 때 사용)
            AuthenticationException authenticationException // Spring Security의 인증 실패 관련 예외
    ) throws IOException {
        // 토큰 없이 보호 API를 호출한 경우
        // SecurityConfig에 EntryPoint로 등록되면 AuthorizationFilter가 인증 필요를 판단한 뒤 이 메서드를 호출
        writeUnauthorized(response, BEARER_CHALLENGE);
    }

    // Bearer 토큰을 받았지만 인증에 실패한 경우 (JwtAuthenticationFilter가 직접 호출)
    // 예외 메시지를 분석해서 분기하지 않고 호출하는 쪽에서 메서드를 나눠 부르는 이유:
    // → 어떤 상황인지는 실패를 감지한 필터가 이미 알고 있으므로, 문자열 해석에 의존하지 않고 명시적으로 구분
    public void commenceInvalidToken(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException
    ) throws IOException {
        writeUnauthorized(response, INVALID_TOKEN_CHALLENGE);
    }

    // 두 경우의 공통 401 응답 작성 (헤더 값만 다르고 상태코드·본문 형식은 동일)
    private void writeUnauthorized(
            HttpServletResponse response,
            String wwwAuthenticate // WWW-Authenticate 헤더에 넣을 값
    ) throws IOException {
        // response.setStatus(401)
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);

        // 문자 인코딩 설정 - UTF-8
        response.setCharacterEncoding(
                StandardCharsets.UTF_8 // (Java) UTF-8 문자 인코딩을 나타내는 객체
                        .name() // 이름을 문자열로 가져옴
        );

        // Content-Type 설정 - JSON
        // response.setContentType("application/json")
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        // WWW-Authenticate 헤더
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate);

        // 응답 본문은 두 경우 모두 동일하게 유지
        // → 토큰이 없어서 실패한 건지, 토큰이 잘못돼서 실패한 건지를 본문으로는 구분해주지 않음
        //   (공격자에게 추측 단서를 주지 않기 위함. 구분이 필요한 클라이언트는 위 헤더를 사용)
        // writeValue(어디에, 무엇을): 어떤 Java 값을 JSON으로 변환해서 특정 장소에 써주는 메서드
        objectMapper.writeValue(
                // getOutputStream(): response의 응답 Body에 데이터를 쓸 수 있는 Output Stream을 가져오는 메서드
                response.getOutputStream(),
                // JSON으로 변환할 ErrorResponse Java 객체를 생성
                new ErrorResponse("인증이 필요합니다.")
        );
    }
}
