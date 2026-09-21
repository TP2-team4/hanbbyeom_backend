package com.team4.hanbbyeom.trust.service;

import com.team4.hanbbyeom.matching.domain.AcceptStatus;
import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.MatchParticipant;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.MatchRequestStatus;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.trust.dto.RecentReviewResponse;
import com.team4.hanbbyeom.trust.dto.TrustProfileResponse;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TrustProfileLookupServiceTest {

    @Autowired
    private TrustProfileLookupService trustProfileLookupService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ActivityMatchRepository activityMatchRepository;
    @Autowired
    private MatchRequestRepository matchRequestRepository;
    @Autowired
    private MatchParticipantRepository matchParticipantRepository;

    private Long testUserId;

    @BeforeEach
    void setUp() {
        testUserId = createUser("신뢰유저");
    }

    private Long createUser(String label) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                label + "-" + System.nanoTime() + "@example.com", "dummy-hash", label, OffsetDateTime.now()
        );
    }

    @Test
    void trust_profile_행이_있으면_실제_값을_반환한다() {
        jdbcTemplate.update(
                "INSERT INTO trust_profile (user_id, average_rating, completed_activity_count, review_count, no_show_report_count) VALUES (?, ?, ?, ?, ?)",
                testUserId, 4.8, 31, 12, 0
        );

        TrustProfileResponse response = trustProfileLookupService.lookup(testUserId);

        assertThat(response.averageRating()).isEqualTo(4.8);
        assertThat(response.completedCount()).isEqualTo(31);
        assertThat(response.reviewCount()).isEqualTo(12);
    }

    @Test
    void trust_profile_행이_없으면_기본값을_반환한다() {
        TrustProfileResponse response = trustProfileLookupService.lookup(testUserId);

        assertThat(response.averageRating()).isNull();
        assertThat(response.completedCount()).isZero();
        assertThat(response.reviewCount()).isZero();
        assertThat(response.noShowReportCount()).isZero();
        // 후기 기능 자체가 없던 시절부터 있던 테스트라, #69로 추가된 필드도 기본값인지 같이 확인
        assertThat(response.perceivedTalkLevelMajority()).isNull();
        assertThat(response.perceivedTalkLevelMajorityCount()).isZero();
        assertThat(response.recentReviews()).isEmpty();
    }

    // 1. 다수결: SILENT 표가 더 많으면 SILENT가 나와야 한다
    @Test
    void 대화_수준_다수결이_많은_쪽으로_계산된다() {
        jdbcTemplate.update("""
                INSERT INTO trust_profile
                    (user_id, review_silent_vote_count, review_light_chat_vote_count)
                VALUES (?, 3, 1)
                """, testUserId);

        TrustProfileResponse response = trustProfileLookupService.lookup(testUserId);

        assertThat(response.perceivedTalkLevelMajority()).isEqualTo(TalkLevel.SILENT);
        assertThat(response.perceivedTalkLevelMajorityCount()).isEqualTo(3);
    }

    // 2. 동률(둘 다 2표)이면 어느 쪽으로도 못 정하니 null, count도 0
    @Test
    void 대화_수준_투표가_동률이면_다수결이_null이다() {
        jdbcTemplate.update("""
                INSERT INTO trust_profile
                    (user_id, review_silent_vote_count, review_light_chat_vote_count)
                VALUES (?, 2, 2)
                """, testUserId);

        TrustProfileResponse response = trustProfileLookupService.lookup(testUserId);

        assertThat(response.perceivedTalkLevelMajority()).isNull();
        assertThat(response.perceivedTalkLevelMajorityCount()).isZero();
    }

    // 3. 후기가 LIMIT(3)보다 많으면 최신순으로 딱 3건만 온다
    @Test
    void 최근_후기가_최신순으로_최대_N건까지_반환된다() {
        OffsetDateTime base = OffsetDateTime.now().minusDays(10);
        insertReviewFromNewMatch(testUserId, 1, base.plusDays(1), "1번(가장 오래됨)");
        insertReviewFromNewMatch(testUserId, 2, base.plusDays(2), "2번");
        insertReviewFromNewMatch(testUserId, 3, base.plusDays(3), "3번");
        insertReviewFromNewMatch(testUserId, 4, base.plusDays(4), "4번(가장 최근)");

        TrustProfileResponse response = trustProfileLookupService.lookup(testUserId);

        assertThat(response.recentReviews()).hasSize(3);
        assertThat(response.recentReviews())
                .extracting(RecentReviewResponse::comment)
                .containsExactly("4번(가장 최근)", "3번", "2번");
        assertThat(response.recentReviews().get(0).courseName()).isEqualTo("뚝섬 한강공원 코스");
    }

    // 매번 새 리뷰어 + 새 매칭을 만들어서 revieweeId에게 후기를 하나 남긴다.
    // (같은 리뷰어를 재사용하면 uq_activity_review_once에 걸리고, 같은 revieweeId를
    //  여러 매칭에 참가시키면 match_request.uq_match_request_active_user에 걸린다)
    private void insertReviewFromNewMatch(Long revieweeId, int rating, OffsetDateTime reviewCreatedAt, String comment) {
        Long reviewerId = createUser("리뷰어" + rating);
        Long activityMatchId = createActivityMatch(reviewerId, revieweeId);
        insertReview(activityMatchId, reviewerId, revieweeId, rating, reviewCreatedAt, comment);
    }

    private Long createActivityMatch(Long userA, Long userB) {
        OffsetDateTime base = OffsetDateTime.now().minusHours(2);
        ActivityMatch activityMatch = new ActivityMatch(
                base.plusMinutes(20), base.plusMinutes(30), TalkLevel.SILENT,
                "뚝섬 한강공원", "뚝섬 한강공원 코스", 5000, 12000,
                "뚝섬유원지역 3번 출구", 360, 400,
                base.plusMinutes(10), base
        );
        activityMatch.confirm("123456");
        activityMatch.end();
        Long activityMatchId = activityMatchRepository.save(activityMatch).getId();

        MatchRequest requestA = matchRequestRepository.save(
                new MatchRequest(userA, OffsetDateTime.now().plusMinutes(20), TalkLevel.SILENT, OffsetDateTime.now().plusMinutes(15))
        );
        MatchRequest requestB = matchRequestRepository.save(
                new MatchRequest(userB, OffsetDateTime.now().plusMinutes(20), TalkLevel.SILENT, OffsetDateTime.now().plusMinutes(15))
        );

        MatchParticipant participantA = matchParticipantRepository.save(
                new MatchParticipant(activityMatchId, requestA.getId(), userA, "A", AcceptStatus.ACCEPTED));
        MatchParticipant participantB = matchParticipantRepository.save(
                new MatchParticipant(activityMatchId, requestB.getId(), userB, "B", AcceptStatus.ACCEPTED));

        // 이 테스트는 매칭 라이프사이클이 아니라 후기 집계만 검증하면 되므로, activity_match를
        // end()로 이미 끝낸 것과 일관되게 참가자/신청글도 "활성" 상태에서 바로 뺀다. 그래야
        // 다음 createActivityMatch() 호출에서 같은 사용자를 또 참가시켜도
        // uq_participant_active_user/uq_match_request_active_user에 안 걸린다.
        //
        // saveAndFlush()를 쓰는 이유: Hibernate는 같은 플러시 안에서 INSERT를 UPDATE보다
        // 먼저 실행한다. save()만 쓰면 이 UPDATE(CLOSED로 변경)가 지연 실행되다가, 다음
        // createActivityMatch() 호출의 새 MatchRequest INSERT(IDENTITY라 즉시 플러시됨)가
        // 먼저 나가버려서 "아직 CLOSED로 안 바뀐 이전 행 + 방금 만든 새 행"이 동시에 활성
        // 상태로 존재하게 되어 유니크 제약을 위반한다 — 실제로 이 순서 문제 때문에 테스트가
        // 실패하는 걸 직접 재현하고 나서 고쳤다.
        participantA.release();
        participantB.release();
        matchParticipantRepository.saveAndFlush(participantA);
        matchParticipantRepository.saveAndFlush(participantB);
        requestA.changeStatus(MatchRequestStatus.CLOSED);
        requestB.changeStatus(MatchRequestStatus.CLOSED);
        matchRequestRepository.saveAndFlush(requestA);
        matchRequestRepository.saveAndFlush(requestB);

        return activityMatchId;
    }

    // ActivityReview 엔티티는 createdAt을 생성자 안에서 now()로 자동 세팅해버려서, 정렬 검증에
    // 필요한 임의의 created_at을 못 넣는다 — 그래서 여기선 엔티티 대신 JdbcTemplate으로 직접 삽입한다.
    private void insertReview(Long activityMatchId, Long reviewerId, Long revieweeId,
                              int rating, OffsetDateTime createdAt, String comment) {
        jdbcTemplate.update("""
                INSERT INTO activity_review
                    (activity_match_id, reviewer_user_id, reviewee_user_id, rating, perceived_talk_level, comment, created_at)
                VALUES (?, ?, ?, ?, 'SILENT', ?, ?)
                """, activityMatchId, reviewerId, revieweeId, rating, comment, createdAt);
    }
}
