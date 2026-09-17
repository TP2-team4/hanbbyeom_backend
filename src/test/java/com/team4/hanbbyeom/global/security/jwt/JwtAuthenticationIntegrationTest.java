package com.team4.hanbbyeom.global.security.jwt;

import com.team4.hanbbyeom.global.config.JwtProperties;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.run.dto.RunConditionCreateRequest;
import com.team4.hanbbyeom.run.service.RunConditionService;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// JWT 요청 인증의 핵심 동작을 실제 HTTP 요청으로 검증

// Service 테스트로는 검증할 수 없는 이유
// → 인증은 Controller보다 앞단(Security Filter)에서 일어나므로,
//   Service를 직접 호출하면 필터를 아예 거치지 않음
// → @AutoConfigureMockMvc로 실제 요청처럼 필터 체인을 통과시켜야 검증 가능

// @SpringBootTest: 애플리케이션 전체 컨텍스트를 띄움 (SecurityConfig의 필터 등록까지 그대로 적용)
// @AutoConfigureMockMvc: 서버를 실제 포트에 띄우지 않고 HTTP 요청을 흉내 내는 MockMvc를 준비
// @Transactional: 테스트가 끝나면 DB 변경 사항을 롤백
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JwtAuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider; // 정상 토큰 발급용

    @Autowired
    private JwtProperties jwtProperties; // 테스트에서 직접 토큰을 만들 때 비밀키·발급자를 가져오기 위해 사용

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MatchRequestRepository matchRequestRepository;

    @Autowired
    private RunConditionService runConditionService;

    private static final String PASSWORD = "test1234";
    private static final String NICKNAME = "테스트";

    // 인증 실패 시 내려가는 WWW-Authenticate 헤더 값 (JwtAuthenticationEntryPoint와 동일)
    private static final String BEARER_CHALLENGE = "Bearer";
    private static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";

    // 테스트마다 겹치지 않는 이메일 생성
    // UUID는 소문자라 users 테이블의 이메일 정규화 CHECK 제약도 그대로 만족
    private String randomEmail() {
        return "jwt-" + UUID.randomUUID() + "@example.com";
    }

    // 사용자를 JPA가 아닌 JDBC로 직접 생성
    // → 영속성 컨텍스트에 User 엔티티를 올리지 않기 위함
    //   (탈퇴 테스트에서 엔티티가 캐시에 남아 있으면 필터가 옛 상태를 볼 수 있음)
    private Long createUser(String email, String nickname) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname, email_verified_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                email,
                passwordEncoder.encode(PASSWORD), // 로그인 테스트에서 비밀번호 비교가 통과해야 하므로 실제 해시로 저장
                nickname,
                OffsetDateTime.now()
        );
    }

    // 운영 코드에서 쓰는 서명 키 (JwtTokenProvider가 만드는 토큰과 같은 키)
    private SecretKey serviceKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.secretBase64()));
    }

    // 테스트 전용 토큰 생성기
    // JwtTokenProvider는 항상 "지금 발급 + 설정된 유효기간"의 정상 토큰만 만들기 때문에,
    // 만료된 토큰처럼 비정상 상황은 발급 시각·만료 시각·서명 키를 직접 지정해서 만들어야 함
    // → 운영 코드를 테스트 때문에 고치지 않아도 되고, sleep 없이 즉시 검증 가능
    private String buildToken(Long userId, Instant issuedAt, Instant expiration, SecretKey key) {
        return buildToken(String.valueOf(userId), jwtProperties.issuer(), issuedAt, expiration, key);
    }

    // subject와 발급자까지 직접 지정하는 토큰 생성기 (경계 테스트용)
    // subject에 null을 넘기면 sub 클레임 자체를 넣지 않음
    private String buildToken(
            String subject,
            String issuer,
            Instant issuedAt,
            Instant expiration,
            SecretKey key
    ) {
        JwtBuilder builder = Jwts.builder()
                .issuer(issuer)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiration))
                .signWith(key);

        if (subject != null) {
            builder.subject(subject);
        }

        return builder.compact();
    }

    // 보호 API에 주어진 Authorization 헤더로 요청을 보내고, 401과 invalid_token 응답을 기대
    // 경계 케이스 대부분이 "토큰을 보냈지만 인증 실패"라는 같은 결과를 확인하므로 공통으로 묶음
    private void 보호_API_호출시_invalid_token_응답(String authorizationHeader) throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, authorizationHeader))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, INVALID_TOKEN_CHALLENGE));
    }

    // 러닝 조건 픽스처 생성 (사용자 위조 방지 테스트용)
    // 소유자 명의의 매칭 요청과 러닝 조건을 만들고 조건 id(=matchRequestId)를 반환
    private Long createRunCondition(Long ownerUserId) {
        // V5에서 시딩된 코스 하나를 가져다 씀
        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");

        // match_request의 시간 순서 CHECK 제약: 생성 < 게시 마감 < 활동 시작
        MatchRequest matchRequest = matchRequestRepository.save(
                new MatchRequest(
                        ownerUserId,
                        OffsetDateTime.now().plusHours(5),
                        TalkLevel.SILENT,
                        OffsetDateTime.now().plusHours(4)
                )
        );

        return runConditionService.create(
                ownerUserId,
                new RunConditionCreateRequest(
                        matchRequest.getId(), courseId, "뚝섬유원지역 3번 출구", 5000, 8000, 360, 400
                )
        );
    }

    @Test
    @DisplayName("정상 Access Token으로 보호 API 호출 시 본인 정보 반환")
    void 정상_토큰_보호_API_호출() throws Exception {
        String email = randomEmail();
        Long userId = createUser(email, NICKNAME);

        String token = jwtTokenProvider.createAccessToken(userId);

        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                // 토큰 소유자 본인의 정보가 내려오는지 확인
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.nickname").value(NICKNAME));
    }

    @Test
    @DisplayName("토큰 없이 보호 API 호출 시 401과 Bearer 요구 헤더 반환")
    void 토큰_없이_보호_API_호출() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                // 인증 정보를 아예 안 보낸 경우이므로 error 코드가 붙지 않은 순수 Bearer
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE))
                // EntryPoint가 응답 형식을 JSON으로 지정했는지 확인
                // jsonPath는 Content-Type과 상관없이 본문을 파싱하므로, 이 검증이 없으면
                // 응답 형식 지정이 빠져도 아래 message 검증만으로는 알아채지 못함
                // (charset이 붙어 application/json;charset=UTF-8로 나가므로 호환 여부로 비교)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."));
    }

    @Test
    @DisplayName("서명이 다른 토큰으로 보호 API 호출 시 401과 invalid_token 반환")
    void 잘못된_토큰_보호_API_호출() throws Exception {
        Long userId = createUser(randomEmail(), NICKNAME);

        // 우리 서버 키가 아닌 다른 키로 서명한 토큰 (위조 토큰)
        // 마지막 글자를 바꾸는 방식은 형식 오류로 깨질 수도 있어 서명 검증을 확실히 태우려고 별도 키 사용
        String forgedToken = buildToken(
                userId,
                Instant.now(),
                Instant.now().plusSeconds(1800),
                Jwts.SIG.HS256.key().build()
        );

        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + forgedToken))
                .andExpect(status().isUnauthorized())
                // 토큰을 보내긴 했으나 그 토큰이 잘못된 경우
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, INVALID_TOKEN_CHALLENGE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // 본문은 토큰 없음일 때와 동일 (실패 원인을 본문으로 구분해주지 않음)
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."));
    }

    @Test
    @DisplayName("만료된 토큰이 붙어 있어도 로그인 API는 필터 제외로 정상 동작")
    void 만료_토큰_첨부_로그인() throws Exception {
        String email = randomEmail();
        createUser(email, NICKNAME);

        // 이미 만료된 토큰 (발급 시각을 과거로 지정해 sleep 없이 만료 상태를 만듦)
        Instant past = Instant.now().minusSeconds(3600);
        String expiredToken = buildToken(
                1L,
                past,
                past.plusSeconds(1),
                serviceKey()
        );

        // JwtAuthenticationFilter의 shouldNotFilter 제외 경로라 토큰을 검사하지 않고 통과해야 함
        // → 제외 처리가 없으면 만료 토큰 때문에 로그인 자체가 401로 막힘
        mockMvc.perform(post("/api/auth/login")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "%s", "password": "%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("A의 토큰에 X-USER-ID로 B를 지정해도 토큰 소유자 A로 처리")
    void 사용자_위조_헤더_무시() throws Exception {
        Long ownerUserId = createUser(randomEmail(), "소유자");
        Long otherUserId = createUser(randomEmail(), "타인");

        // 러닝 조건은 소유자(A)만 조회할 수 있음
        Long conditionId = createRunCondition(ownerUserId);

        // A의 토큰으로 요청하면서, 예전 임시 인증 방식이던 X-USER-ID에는 B를 넣어 보냄
        // → 헤더가 무시되면 소유자 A로 처리되어 200
        // → 헤더가 다시 사용되는 회귀가 생기면 B는 소유자가 아니므로 403으로 실패
        mockMvc.perform(get("/api/run/conditions/{id}", conditionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.createAccessToken(ownerUserId))
                        .header("X-USER-ID", String.valueOf(otherUserId)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("토큰 발급 후 탈퇴한 사용자의 요청은 401과 invalid_token 반환")
    void 탈퇴_사용자_토큰_차단() throws Exception {
        Long userId = createUser(randomEmail(), NICKNAME);

        // 탈퇴 전에 발급받은, 그 자체로는 아직 유효한 토큰
        String token = jwtTokenProvider.createAccessToken(userId);

        // 탈퇴 처리
        // users 테이블의 chk_users_account_lifecycle 제약상
        // deleted_at을 채울 때 개인정보 필드는 전부 NULL이어야 함
        jdbcTemplate.update(
                """
                UPDATE users
                SET deleted_at = ?,
                    email = NULL,
                    password_hash = NULL,
                    nickname = NULL,
                    email_verified_at = NULL
                WHERE id = ?
                """,
                OffsetDateTime.now(),
                userId
        );

        // 토큰 자체는 서명·만료·발급자 검증을 통과하지만,
        // 필터가 매 요청마다 활성 사용자를 DB에서 다시 확인하므로 차단되어야 함
        // → "JWT만 믿지 않고 매번 DB를 조회한다"는 설계 결정을 지키는 테스트
        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, INVALID_TOKEN_CHALLENGE));
    }

    // ---------------------------------------------------------------------
    // 경계 케이스
    // 위 핵심 테스트가 응답 형식(Content-Type·본문)까지 확인하므로,
    // 아래에서는 "어떤 토큰이 어떤 판정을 받는가"에 집중해 상태코드와 인증 요구 헤더만 검증
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("만료된 토큰으로 보호 API 호출 시 401과 invalid_token 반환")
    void 만료된_토큰_차단() throws Exception {
        Long userId = createUser(randomEmail(), NICKNAME);

        // 발급 시각을 과거로 지정해 이미 만료된 토큰을 만듦 (sleep 없이 만료 상황 재현)
        Instant past = Instant.now().minusSeconds(3600);

        보호_API_호출시_invalid_token_응답(
                "Bearer " + buildToken(userId, past, past.plusSeconds(1), serviceKey()));
    }

    @Test
    @DisplayName("발급자가 다른 토큰으로 보호 API 호출 시 401과 invalid_token 반환")
    void 발급자_불일치_토큰_차단() throws Exception {
        Long userId = createUser(randomEmail(), NICKNAME);
        Instant now = Instant.now();

        // 서명 키는 같지만 iss가 우리 서버 값이 아님
        // → JwtTokenProvider의 requireIssuer 검증에 걸려야 함
        보호_API_호출시_invalid_token_응답("Bearer " + buildToken(
                String.valueOf(userId), "other-issuer", now, now.plusSeconds(1800), serviceKey()));
    }

    @Test
    @DisplayName("sub가 없는 토큰으로 보호 API 호출 시 401과 invalid_token 반환")
    void sub_없는_토큰_차단() throws Exception {
        Instant now = Instant.now();

        // 누구의 토큰인지 알 수 없으므로 인증할 수 없음
        보호_API_호출시_invalid_token_응답("Bearer " + buildToken(
                null, jwtProperties.issuer(), now, now.plusSeconds(1800), serviceKey()));
    }

    @Test
    @DisplayName("sub가 숫자가 아닌 토큰으로 보호 API 호출 시 401과 invalid_token 반환")
    void sub_형식_오류_토큰_차단() throws Exception {
        Instant now = Instant.now();

        // sub를 사용자 id(Long)로 변환하는 과정에서 실패해야 하며, 500이 아니라 401로 처리되어야 함
        보호_API_호출시_invalid_token_응답("Bearer " + buildToken(
                "not-a-number", jwtProperties.issuer(), now, now.plusSeconds(1800), serviceKey()));
    }

    @Test
    @DisplayName("존재하지 않는 사용자 id의 토큰으로 보호 API 호출 시 401과 invalid_token 반환")
    void 존재하지_않는_사용자_토큰_차단() throws Exception {
        // 토큰 자체는 우리 서버가 정상 발급한 형식이지만 그런 사용자가 DB에 없음
        // → 토큰만 믿지 않고 매 요청마다 사용자를 조회하기 때문에 걸러짐
        보호_API_호출시_invalid_token_응답(
                "Bearer " + jwtTokenProvider.createAccessToken(999_999_999L));
    }

    @Test
    @DisplayName("JWT 형식이 아닌 문자열로 보호 API 호출 시 401과 invalid_token 반환")
    void JWT_형식_오류_차단() throws Exception {
        // Header.Payload.Signature 구조 자체가 아님 → 파싱 단계에서 실패
        보호_API_호출시_invalid_token_응답("Bearer not-a-jwt");
    }

    @Test
    @DisplayName("토큰 없이 Bearer 스킴만 보내면 401과 invalid_token 반환")
    void 빈_Bearer_차단() throws Exception {
        // 인증을 시도하긴 했으나 토큰이 비어 있는 경우
        // → "인증 정보를 안 보낸 것"이 아니라 "잘못 보낸 것"으로 구분해서 처리
        보호_API_호출시_invalid_token_응답("Bearer ");
    }

    @Test
    @DisplayName("스킴이 소문자(bearer)여도 정상 인증")
    void 소문자_스킴_허용() throws Exception {
        String email = randomEmail();
        Long userId = createUser(email, NICKNAME);

        // HTTP 인증 스킴은 대소문자를 구분하지 않는 것이 표준
        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION,
                                "bearer " + jwtTokenProvider.createAccessToken(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    @DisplayName("Bearer가 아닌 인증 방식은 인증 미시도로 처리해 401과 Bearer 요구 헤더 반환")
    void Bearer가_아닌_스킴() throws Exception {
        // Basic 인증은 우리가 처리하는 방식이 아니므로 JWT 인증을 시도하지 않음
        // → "잘못된 토큰"이 아니라 "인증 정보를 안 보낸 것"과 같은 취급 (invalid_token이 아님)
        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNzd29yZA=="))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE));
    }

    @Test
    @DisplayName("인증된 요청 직후의 토큰 없는 요청은 401 (요청 간 인증 정보 누수 없음)")
    void 요청_간_SecurityContext_누수_없음() throws Exception {
        Long userId = createUser(randomEmail(), NICKNAME);

        // 1. 정상 토큰으로 인증에 성공
        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + jwtTokenProvider.createAccessToken(userId)))
                .andExpect(status().isOk());

        // 2. 바로 다음 요청은 토큰이 없으므로 앞 요청의 인증 정보가 남아 있으면 안 됨
        // → SecurityContext는 요청을 처리하는 스레드에 보관되고, Spring Security가 요청이 끝날 때 비움
        //   (STATELESS 설정은 이 값을 세션에 저장하지 않게 하는 별개의 설정)
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE));
    }
}
