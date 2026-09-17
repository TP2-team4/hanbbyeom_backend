package com.team4.hanbbyeom.user.controller;

import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.MatchRequestStatus;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.user.domain.DefaultTalkLevel;
import com.team4.hanbbyeom.user.domain.User;
import com.team4.hanbbyeom.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 기본 대화 수준 변경 API를 실제 JWT 인증·Controller·Service·DB 흐름으로 검증
// 사용자 설정만 바뀌고 다른 사용자와 기존 모집글·매칭 값은 유지되는지도 함께 확인
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserPreferencesIntegrationTest {

    // 실제 Security Filter를 포함한 HTTP 요청과 JSON 응답 검증
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MatchRequestRepository matchRequestRepository;

    @Autowired
    private ActivityMatchRepository activityMatchRepository;

    // JPA 변경 내용을 DB에 반영하고 1차 캐시를 비워 저장 결과를 다시 조회하기 위해 사용
    @Autowired
    private EntityManager entityManager;

    // ActivityMatch에 저장된 대화 수준을 DB 값으로 직접 확인하기 위해 사용
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 테스트 사용자 명의의 정상 Access Token 발급
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    // 테스트마다 겹치지 않는 활성 사용자 생성
    private User createUser(DefaultTalkLevel defaultTalkLevel) {
        return userRepository.saveAndFlush(new User(
                "preferences-" + UUID.randomUUID() + "@example.com",
                "encoded-password",
                "테스트사용자",
                defaultTalkLevel,
                Instant.now()
        ));
    }

    // Authorization 헤더에 넣을 Bearer 인증값 생성
    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(userId);
    }

    // 사용자 설정 변경 전부터 존재하던 모집글과 매칭 생성
    // 두 데이터는 사용자 기본값을 참조하지 않고 생성 당시 대화 수준을 별도로 저장
    private ExistingMatchingData createExistingMatchingData(Long userId) {
        OffsetDateTime now = OffsetDateTime.now();

        MatchRequest matchRequest = matchRequestRepository.saveAndFlush(new MatchRequest(
                userId,
                now.plusHours(48),
                TalkLevel.SILENT,
                now.plusHours(47)
        ));
        matchRequest.changeStatus(MatchRequestStatus.MATCHED);

        ActivityMatch activityMatch = new ActivityMatch(
                now.plusHours(48),
                now.plusHours(50),
                TalkLevel.SILENT,
                "뚝섬 한강공원",
                "뚝섬 코스",
                5000,
                8000,
                "한강 산책로 왕복",
                360,
                400,
                now.plusHours(1)
        );
        // 확정된 매칭의 스냅샷 값도 사용자 기본 설정 변경과 분리되는지 확인하기 위해 확정 상태로 저장
        activityMatch.confirm("123456");
        activityMatch = activityMatchRepository.saveAndFlush(activityMatch);

        // 모집글·매칭·사용자를 실제 참가 기록으로 연결해 현재 사용자의 확정 매칭 상태 구성
        jdbcTemplate.update(
                """
                INSERT INTO match_participant (
                    activity_match_id, match_request_id, user_id, slot, accept_status, responded_at
                ) VALUES (?, ?, ?, 'A', 'ACCEPTED', ?)
                """,
                activityMatch.getId(),
                matchRequest.getId(),
                userId,
                now
        );

        return new ExistingMatchingData(matchRequest.getId(), activityMatch.getId());
    }

    // 두 Entity의 ID만 묶어 테스트 검증 단계에 전달
    private record ExistingMatchingData(Long matchRequestId, Long activityMatchId) {
    }

    @Test
    @DisplayName("인증된 사용자의 기본 대화 수준만 변경하고 기존 매칭 데이터는 유지")
    void 기본_대화_수준_변경() throws Exception {
        // 준비: 설정을 변경할 사용자, 영향을 받으면 안 되는 다른 사용자와 기존 매칭 데이터 생성
        User currentUser = createUser(DefaultTalkLevel.SILENT);
        User otherUser = createUser(DefaultTalkLevel.SILENT);
        ExistingMatchingData existingData = createExistingMatchingData(currentUser.getId());

        // 실행 및 응답 검증: A의 토큰으로 LIGHT_CHAT 변경 요청
        // 예전 임시 인증 헤더에 B를 넣어도 토큰 소유자인 A의 설정만 변경되어야 함
        mockMvc.perform(patch("/api/users/me/preferences")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(currentUser.getId()))
                        .header("X-USER-ID", String.valueOf(otherUser.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"defaultTalkLevel": "LIGHT_CHAT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultTalkLevel").value("LIGHT_CHAT"));

        // Dirty Checking 결과를 DB에 반영하고 기존 Entity 캐시가 아닌 DB 저장값으로 다시 검증
        entityManager.flush();
        entityManager.clear();

        assertThat(userRepository
                .findById(currentUser.getId())
                .orElseThrow()
                .getDefaultTalkLevel())
                .isEqualTo(DefaultTalkLevel.LIGHT_CHAT);

        assertThat(userRepository
                .findById(otherUser.getId())
                .orElseThrow()
                .getDefaultTalkLevel())
                .isEqualTo(DefaultTalkLevel.SILENT);

        // 기본 설정은 앞으로 작성할 모집글의 기본 선택값이므로 기존 모집글 값은 바뀌지 않아야 함
        assertThat(matchRequestRepository
                .findById(existingData.matchRequestId())
                .orElseThrow()
                .getTalkLevel())
                .isEqualTo(TalkLevel.SILENT);

        // 이미 확정된 매칭도 생성 당시 대화 수준을 그대로 유지해야 함
        String activityMatchTalkLevel = jdbcTemplate.queryForObject(
                "SELECT talk_level FROM activity_match WHERE id = ?",
                String.class,
                existingData.activityMatchId()
        );
        assertThat(activityMatchTalkLevel).isEqualTo("SILENT");

        // 이후 내 정보 조회에서도 변경된 설정이 반환되는지 확인
        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(currentUser.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultTalkLevel").value("LIGHT_CHAT"));
    }

    @Test
    @DisplayName("LIGHT_CHAT에서 SILENT로 기본 대화 수준 변경 가능")
    void SILENT로_기본_대화_수준_변경() throws Exception {
        // 준비: LIGHT_CHAT을 기본값으로 가진 활성 사용자 생성
        User currentUser = createUser(DefaultTalkLevel.LIGHT_CHAT);

        // 실행 및 검증: 반대 방향인 SILENT 변경도 정상 처리되는지 확인
        mockMvc.perform(patch("/api/users/me/preferences")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(currentUser.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"defaultTalkLevel": "SILENT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultTalkLevel").value("SILENT"));
    }

    @Test
    @DisplayName("허용되지 않은 기본 대화 수준 변경 요청은 400")
    void 잘못된_기본_대화_수준_거부() throws Exception {
        User currentUser = createUser(DefaultTalkLevel.SILENT);

        // Enum에 없는 값은 Service에 도달하기 전에 공통 요청 형식 오류로 처리
        mockMvc.perform(patch("/api/users/me/preferences")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(currentUser.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"defaultTalkLevel": "TALKATIVE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다."));
    }

    @Test
    @DisplayName("기본 대화 수준이 누락된 변경 요청은 400")
    void 기본_대화_수준_누락_거부() throws Exception {
        User currentUser = createUser(DefaultTalkLevel.SILENT);

        // 요청 필드가 없으면 @NotNull 검증에서 Service 호출 전에 차단
        mockMvc.perform(patch("/api/users/me/preferences")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(currentUser.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("토큰 없이 기본 대화 수준 변경 요청 시 401")
    void 인증_없는_변경_거부() throws Exception {
        mockMvc.perform(patch("/api/users/me/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"defaultTalkLevel": "LIGHT_CHAT"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }
}
