package com.team4.hanbbyeom.global.config;

import com.team4.hanbbyeom.global.security.CustomUserDetailsService;
import com.team4.hanbbyeom.global.security.jwt.JwtAuthenticationEntryPoint;
import com.team4.hanbbyeom.global.security.jwt.JwtAuthenticationFilter;
import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

// Spring Security: 누가 우리 서버에 접근할 수 있는지를 관리해주는 Spring의 보안 프레임워크 (API 접근 권한 관리)
// build.gradle에 spring-boot-starter-security 의존성이 있으면,
// Spring Boot는 아무 설정 없이도 자동으로 모든 API를 막아버림
// → SecurityConfig: 어떤 API는 열어두고 어떤 API는 막을지 설정해두는 파일

// 인증(Authentication): 누구인지 확인
// 인가(Authorization): 확인한 대상의 권한 확인

// Spring Security 요청 인가 설정
// 회원가입              POST /api/auth/signup                      누구나 접근 가능
// 로그인                POST /api/auth/login                       누구나 접근 가능
// 이메일 인증 코드 발송   POST /api/auth/email-verifications          누구나 접근 가능
// 이메일 인증 코드 확인   POST /api/auth/email-verifications/confirm  누구나 접근 가능
// 러닝 코스 목록 조회     GET  /api/run/courses                      누구나 접근 가능
// 스웨거 UI 진입 경로          /swagger-ui.html                      누구나 접근 가능
// 스웨거 UI 정적 리소스        /swagger-ui/**                        누구나 접근 가능
// 스웨거 API 명세 데이터 제공   /v3/api-docs/**                       누구나 접근 가능
// 오류 최종 처리 에러 경로      /error                                누구나 접근 가능
// 그 외 API                                                        로그인/인증된 사용자만 접근 가능

@Configuration
public class SecurityConfig {

    // PasswordEncoder Bean 등록 (비밀번호 암호화 및 검증에 사용)
    // PasswordEncoder: (Spring Security 제공) 비밀번호 암호화/검증 인터페이스
    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCryptPasswordEncoder: (Spring Security 제공) PasswordEncoder를 BCrypt 방식으로 구현한 클래스
        // 비밀번호를 BCrypt 방식으로 해시하고, 로그인할 때 입력 비밀번호가 저장된 해시와 맞는지 비교해주는 객체
        return new BCryptPasswordEncoder();
    }

    // AuthenticationManager Bean 등록 (로그인 시 이메일·비밀번호 인증을 수행)
    // Spring이 우리가 등록한 CustomUserDetailsService와 PasswordEncoder Bean을 알아서 연결해줌

    // AuthenticationManager: (Spring Security 제공) 인증 처리의 진입점, 인증 창구(인터페이스)
    // AuthService가 이 객체에 인증을 요청하면 아래 순서로 처리됨
    // UserDetailsService로 사용자 조회 → PasswordEncoder로 비밀번호 비교 → 성공 시 Authentication 반환
    @Bean
    public AuthenticationManager authenticationManager(
            // AuthenticationConfiguration: Spring Security가 내부적으로 구성해둔 인증 설정 모음
            AuthenticationConfiguration configuration
    ) {
        // 이미 구성되어 있는 AuthenticationManager를 꺼내서 Bean으로 등록
        return configuration.getAuthenticationManager();
    }

    // 로그인 전에 호출해야 하는 API(회원가입·로그인·이메일 인증)와 공개 데이터 조회 API만 열어두고,
    // 나머지는 전부 Access Token이 있어야 접근 가능

    // "/api/auth/**"처럼 넓은 패턴을 쓰지 않고 경로와 HTTP 메서드를 하나씩 지정하는 이유
    // → 나중에 /api/auth/logout, /api/auth/refresh처럼 인증이 필요한 API가 추가됐을 때
    //   의도치 않게 자동으로 공개되어 버리는 것을 막기 위함
    //   (같은 조건 객체를 JWT 필터 제외 판단에도 그대로 전달)

    // SecurityFilterChain: 클라이언트 요청을 Controller로 보내기 전 거치는 보안필터들의 체인
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            // 아래 세 Bean은 JwtAuthenticationFilter를 직접 생성해서 체인에 끼워 넣기 위해 주입받음
            JwtTokenProvider jwtTokenProvider,
            CustomUserDetailsService customUserDetailsService,
            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint
    ) throws Exception {
        // 인증 없이 접근할 수 있고 JWT 필터도 실행하지 않을 공개 API 조건
        // 같은 RequestMatcher를 permitAll과 필터 제외 판단에 함께 사용해 두 정책의 불일치를 방지

        // 주의: 공개 API를 추가할 때는 반드시 이 목록에 넣어야 함
        // → 아래 permitAll 줄에만 따로 추가하면 인가만 열리고 JWT 필터는 계속 실행되므로,
        //   만료된 토큰이 헤더에 남아 있을 때 공개 API인데도 401이 나감
        // (swagger-ui·/v3/api-docs·/error는 이 목록 밖이지만, 브라우저가 해당 경로에
        //  Authorization 헤더를 붙이지 않고 /error는 필터가 기본적으로 건너뛰므로 문제되지 않음)
        RequestMatcher publicApiMatcher = new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/signup"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/login"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/email-verifications"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/email-verifications/confirm"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/run/courses")
        );

        http
                // CSRF 보호 비활성화
                // Access Token을 Authorization 헤더로 직접 실어 보내는 방식이라 브라우저가 자동으로
                // 인증 정보를 실어 보내지 않음 → 공격자 사이트가 요청을 흉내 낼 수 없어 CSRF 위험 없음
                // 주의(#4): Refresh Token을 HttpOnly Cookie로 전환하면 이 전제가 깨짐
                // 쿠키는 브라우저가 도메인만 보고 자동으로 실어 보내므로 CSRF 위험이 다시 생김
                // → 그 시점에 CSRF 재활성화 또는 SameSite 쿠키 속성 등 별도 대책 필요, 지금 이대로 두면 안 됨!!
                .csrf(csrf -> csrf.disable())

                // sessionManagement: Spring Security의 Session 관리 방식을 설정
                // 매 요청마다 JWT Access Token을 검증하는 Stateless 인증 방식을 위한 세팅
                .sessionManagement(session ->
                        // STATELESS: 로그인 상태를 서버의 HTTP 세션에 저장해서 관리하지 않음
                        // → 요청이 끝나면 인증 정보가 남지 않으므로, 매 요청마다 토큰으로 다시 인증해야 함
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // authorizeHttpRequests: URL별 접근 권한 설정
                // Spring이 권한 설정 객체를 넘기고, 그걸 auth라는 변수로 받아서 아래 설정들을 붙임
                .authorizeHttpRequests(auth -> auth
                        // requestMatchers(HTTP 메서드, 경로): 이 메서드와 경로에 해당하는 요청을 선택
                        // permitAll(): 인증 여부와 관계없이 모두 접근 허용
                        .requestMatchers(publicApiMatcher).permitAll()
                        .requestMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**").permitAll()
                        // 검증 실패 등으로 발생한 예외를 Spring이 내부적으로 /error로 전달(forward)하는데,
                        // 이 경로가 허용되지 않으면 GlobalExceptionHandler가 만든 400 응답이 403으로 바뀌어버림
                        .requestMatchers("/error").permitAll()
                        // anyRequest: 앞에서 지정하지 않은 나머지 모든 요청
                        // authenticated: 인증된 사용자만 접근 가능
                        .anyRequest().authenticated()
                )

                // exceptionHandling: 인증·인가 실패 시 어떤 응답을 돌려줄지 설정
                // authenticationEntryPoint: "인증이 안 된 사용자가 보호 API에 접근"했을 때 호출됨
                // → 등록하지 않으면 Spring 기본 동작(빈 본문 + 403)이 나가므로, 공통 401 형식으로 바꿔줌
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint))

                // addFilterBefore(끼워 넣을 필터, 이 필터 앞에): 보안 필터 체인의 원하는 위치에 필터를 추가
                // UsernamePasswordAuthenticationFilter 앞에 두는 이유
                // → 인가 판정(AuthorizationFilter)보다 먼저 실행되어야 SecurityContext에 인증 정보를 넣을 수 있음

                // JwtAuthenticationFilter에 @Component를 붙이지 않고 여기서 직접 생성하는 이유
                // → Filter 타입 Bean은 Spring Boot가 서블릿 컨테이너에도 자동 등록해서
                //   Security 체인 밖에서 한 번 더 실행되는 문제가 생길 수 있음
                .addFilterBefore(
                        new JwtAuthenticationFilter(
                                jwtTokenProvider,
                                customUserDetailsService,
                                jwtAuthenticationEntryPoint,
                                publicApiMatcher
                        ),
                        UsernamePasswordAuthenticationFilter.class
                );

        // 지금까지 설정한 것들을 기반으로 실제 SecurityFilterChain 객체를 만들어서 반환
        return http.build();
    }
}
