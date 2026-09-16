package com.team4.hanbbyeom.global.security.jwt;

import com.team4.hanbbyeom.global.security.CustomUserDetails;
import com.team4.hanbbyeom.global.security.CustomUserDetailsService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

// 중요!!

// 클라이언트가 보낸 JWT를 필터링(JWT 검증 후 존재하는지 DB 조회),
// 필터 통과하면 인증 정보 객체(Authentication) 발급 후 Security Context에 저장
// → 해당 토큰의 사용자를 현재 요청의 인증된 사용자로 등록

// OncePerRequestFilter: Spring이 제공하는 필터 기반 클래스, 요청 하나당 한 번 실행
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    // 인증 스킴(scheme): Authorization 헤더에서 "어떤 방식으로 인증 정보를 보냈는지" 나타내는 앞부분 이름
    // 예) Authorization: Bearer eyJhbGci...  →  스킴은 Bearer, 그 뒤가 실제 토큰
    //     Bearer 외에 Basic(아이디·비밀번호), Digest 등이 있고, URL의 https:// 에서 https도 같은 의미의 스킴
    // (데이터 구조를 정의하는 "스키마(schema)"와는 다른 단어)
    private static final String BEARER_SCHEME = "Bearer";

    // 이 필터를 아예 실행하지 않을 요청 목록 (로그인 전에 호출해야 하는 공개 API)
    // 이 필터는 잘못된 토큰을 만나면 즉시 401을 쓰고 다음 필터로 넘기지 않기 때문에,
    // 제외하지 않으면 만료된 토큰이 헤더에 남아 있을 때 로그인 자체가 막혀 버림
    // → 공개 API는 토큰을 아예 쳐다보지 않고 통과시켜야 함

    // "/api/auth/**" 같은 넓은 패턴을 쓰지 않는 이유:
    // → 나중에 추가될 /api/auth/logout, /api/auth/refresh처럼 인증이 필요한 API까지
    //   자동으로 검사 대상에서 빠져버림 (경로와 HTTP 메서드를 하나씩 정확히 지정)

    // PathPatternRequestMatcher: (Spring Security 제공) 요청의 HTTP 메서드와 경로가 조건과 맞는지 판단하는 객체
    private static final List<RequestMatcher> JWT_FILTER_BYPASS_MATCHERS = List.of(
            // 회원가입
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/signup"),
            // 로그인 (토큰을 발급받는 API라 당연히 토큰 없이 호출 가능해야 함)
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/login"),
            // 이메일 인증 코드 발송
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/email-verifications"),
            // 이메일 인증 코드 확인
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/email-verifications/confirm"),
            // 러닝 코스 목록 조회 (로그인 없이 볼 수 있는 공개 데이터)
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/run/courses")
    );

    private final JwtTokenProvider jwtTokenProvider; // JWT의 서명·만료시간·발급자·형식 검증
    private final CustomUserDetailsService userDetailsService; // JWT에서 꺼낸 사용자 ID로 활성 사용자 조회
    private final JwtAuthenticationEntryPoint authenticationEntryPoint; // 인증 실패 시 공통 형식의 401 응답 작성

    @Override
    // shouldNotFilter: (OncePerRequestFilter 제공) true를 반환하면 이 필터의 doFilterInternal을 건너뜀
    // 필터 안에서 경로를 if문으로 검사하지 않고 이 메서드를 쓰는 이유:
    // → "검사 대상이 아니다"라는 판단을 인증 로직과 분리해서, 제외 목록만 보면 공개 API를 한눈에 알 수 있음
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 제외 목록 중 하나라도 현재 요청과 일치하면 필터를 실행하지 않음
        return JWT_FILTER_BYPASS_MATCHERS.stream()
                .anyMatch(matcher -> matcher.matches(request));
    }

    @Override
    // 요청 하나가 들어오면 이 메서드가 실행
    protected void doFilterInternal(
            HttpServletRequest request, // 클라이언트가 보낸 요청
            HttpServletResponse response,
            FilterChain filterChain // 다음 Filter로 요청을 넘기는 데 사용
    ) throws ServletException, IOException { // 실행 중 ServletException, IOException 발생 가능

        // JWT 문자열을 담을 변수(빈 값)를 선언
        String token;

        // Authorization 헤더에서 Bearer Token을 꺼내는 단계만 따로 처리 (헤더 해석 실패, 이후 필터 예외에서 분리)
        try {
            // Authorization 헤더에서 토큰 추출
            token = resolveBearerToken(request);

        } catch (AuthenticationException exception) {
            // try 안의 코드를 실행하다가 AuthenticationException 계열 예외가 발생하면 여기로

            // 인증 실패 공통 처리 메서드(이 파일 아래에 만듦)를 호출
            // 현재 요청(request)·응답(response)·인증 예외(exception)를 전달하여
            // SecurityContext를 비우고 공통 401 응답을 작성
            handleAuthenticationFailure(request, response, exception);

            // 즉시 종료
            return;

        }

        // 1. 토큰 없음·Bearer 방식 아님: 인증을 시도하지 않고 다음 필터로 이동
        // 2. 정상 Bearer 토큰: 검증과 사용자 조회 후 인증 정보를 저장하고 다음 필터로 이동
        // 3. 잘못된 Bearer 토큰: 다음 필터로 이동하지 않고 즉시 공통 401 응답 작성

        // Bearer 인증 정보가 없으면 SecurityContext에 현재 사용자의 Authentication을 등록하지 않음
        // → 공개 API는 통과하고, 보호 API는 뒤의 AuthorizationFilter에서 거부됨
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // 서명·형식·만료·발급자 검증을 통과한 Claims만 반환
            Claims claims = jwtTokenProvider.parseClaims(token);

            // JWT subject는 문자열이므로 사용자 PK 타입인 Long으로 변환
            Long userId = Long.valueOf(claims.getSubject());

            // 토큰이 유효해도 존재하지 않거나 탈퇴한 사용자는 인증하지 않음
            CustomUserDetails userDetails = userDetailsService.loadUserById(userId);

            Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                    userDetails,
                    null,
                    userDetails.getAuthorities()
            );

            // 기존 Context를 직접 수정하지 않고 현재 요청용 빈 Context를 생성
            SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(authentication);
            SecurityContextHolder.setContext(securityContext);
        } catch (AuthenticationException exception) {
            // 사용자 미존재·탈퇴 등 Spring Security 인증 예외 처리
            handleAuthenticationFailure(request, response, exception);
            return;
        } catch (JwtException | IllegalArgumentException exception) {
            // JWT 검증 실패와 subject 누락·형식 오류를 같은 잘못된 자격 증명으로 처리
            handleAuthenticationFailure(
                    request,
                    response,
                    new BadCredentialsException("유효하지 않은 Access Token입니다.", exception)
            );
            return;
        }

        // 인증이 성공한 경우에만 인증 정보가 담긴 상태로 다음 필터를 실행
        filterChain.doFilter(request, response);
    }

    private String resolveBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);

        // 헤더가 없는 경우
        if (!StringUtils.hasText(authorization)) {
            // null 반환: 이 요청은 Bearer 인증을 시도하지 않음
            return null;
        }

        int separatorIndex = authorization.indexOf(' ');

        // 스킴만 전달한 "Bearer"는 토큰을 보내려다 실패한 잘못된 인증 요청
        if (separatorIndex < 0) {
            if (BEARER_SCHEME.equalsIgnoreCase(authorization)) {
                throw new BadCredentialsException("Bearer 토큰이 비어 있습니다.");
            }
            return null;
        }

        String scheme = authorization.substring(0, separatorIndex);
        if (!BEARER_SCHEME.equalsIgnoreCase(scheme)) {
            return null;
        }

        String token = authorization.substring(separatorIndex + 1).trim();
        if (!StringUtils.hasText(token)) {
            throw new BadCredentialsException("Bearer 토큰이 비어 있습니다.");
        }

        return token;
    }

    private void handleAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        // 실패한 요청의 인증 정보가 후속 처리에 남지 않도록 명시적으로 제거
        SecurityContextHolder.clearContext();

        // 이 메서드로 오는 경우는 전부 "Bearer 토큰을 보냈는데 인증에 실패한" 상황
        // (토큰 없음은 애초에 여기로 오지 않고 다음 필터로 그냥 통과됨)
        // → 토큰 자체가 문제라는 뜻의 invalid_token 응답을 사용
        authenticationEntryPoint.commenceInvalidToken(request, response, exception);
    }
}
