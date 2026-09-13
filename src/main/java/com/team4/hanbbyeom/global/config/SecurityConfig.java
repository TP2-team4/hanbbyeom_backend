package com.team4.hanbbyeom.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

// Spring Security: 누가 우리 서버에 접근할 수 있는지를 관리해주는 Spring의 보안 프레임워크 (API 접근 권한 관리)
// build.gradle에 spring-boot-starter-security 의존성이 있으면,
// Spring Boot는 아무 설정 없이도 자동으로 모든 API를 막아버림
// → SecurityConfig: 어떤 API는 열어두고 어떤 API는 막을지 설정해두는 파일

// 인증(Authentication): 누구인지 확인
// 인가(Authorization): 확인한 대상의 권한 확인

// Spring Security 요청 인가 설정
// 인증 관련 API                /api/auth/**       누구나 접근 가능
// 스웨거 UI                   /swagger-ui/**      누구나 접근 가능
// 스웨거 API 명세 데이터 제공    /v3/api-docs/**     누구나 접근 가능
// 오류 최종 처리 에러 경로       /error              누구나 접근 가능
// 그 외 API                                       로그인/인증된 사용자만 접근 가능
@Configuration
public class SecurityConfig {

    // 회원가입, 이메일 인증처럼 로그인 전에 호출해야 하는 API는 인증 없이 접근 가능해야 함
    // (임시) JWT 기반 요청 인증(#3)이 추가되기 전까지 /api/auth/** 전체를 공개 처리

    // SecurityFilterChain: 클라이언트 요청을 Controller로 보내기 전 거치는 보안필터들의 체인
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF 보호 비활성화
                // Access Token을 Authorization 헤더로 직접 실어 보내는 방식이라 브라우저가 자동으로
                // 인증 정보를 실어 보내지 않음 → 공격자 사이트가 요청을 흉내 낼 수 없어 CSRF 위험 없음
                // 주의(#4): Refresh Token을 HttpOnly Cookie로 전환하면 이 전제가 깨짐
                // 쿠키는 브라우저가 도메인만 보고 자동으로 실어 보내므로 CSRF 위험이 다시 생김
                // → 그 시점에 CSRF 재활성화 또는 SameSite 쿠키 속성 등 별도 대책 필요, 지금 이대로 두면 안 됨!!
                .csrf(csrf -> csrf.disable())

                // sessionManagement: Spring Security의 Session 관리 방식을 설정
                // 매 요청마다 JWT Access Token을 검증하는 Stateless 인증 방식 사용을 위한 세팅 (#3에서 작업할 JWT 기반 인증 방식)
                .sessionManagement(session ->
                        // STATELESS: 로그인 상태를 서버의 HTTP 세션에 저장해서 관리하지 않음
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // authorizeHttpRequests: URL별 접근 권한 설정
                // Spring이 권한 설정 객체를 넘기고, 그걸 auth라는 변수로 받아서 아래 설정들을 붙임
                .authorizeHttpRequests(auth -> auth
                        // requestMatchers(...): 이 URL 패턴에 해당하는 요청을 선택
                        // permitAll(): 인증 여부와 관계없이 모두 접근 허용
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // 검증 실패 등으로 발생한 예외를 Spring이 내부적으로 /error로 전달(forward)하는데,
                        // 이 경로가 허용되지 않으면 GlobalExceptionHandler가 만든 400 응답이 403으로 바뀌어버림
                        .requestMatchers("/error").permitAll()
                        // anyRequest: 앞에서 지정하지 않은 나머지 모든 요청
                        // authenticated: 인증된 사용자만 접근 가능
                        .anyRequest().authenticated()
                );

        // 지금까지 설정한 것들을 기반으로 실제 SecurityFilterChain 객체를 만들어서 반환
        return http.build();
    }
}
