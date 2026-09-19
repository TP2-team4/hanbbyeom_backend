package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.domain.*;
import com.team4.hanbbyeom.matching.dto.MatchConfirmResponse;
import com.team4.hanbbyeom.matching.exception.ApplicantWithdrawnException;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotSearchingException;
import com.team4.hanbbyeom.matching.exception.NotMatchParticipantException;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class MatchDecisionServiceTest {

    @Autowired private MatchDecisionService matchDecisionService;
    @Autowired private MatchApplyService matchApplyService;
    @Autowired private MatchRequestRepository matchRequestRepository;
    @Autowired private MatchParticipantRepository matchParticipantRepository;
    @Autowired private ActivityMatchRepository activityMatchRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager entityManager;

    private Long hostUserId, applicantUserId, hostRequestId, activityMatchId;

    @BeforeEach
    void setUp() {
        hostUserId = createUser("host");
        applicantUserId = createUser("applicant");

        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");

        MatchRequest hostRequest = new MatchRequest(
                hostUserId, OffsetDateTime.now().plusHours(48), TalkLevel.LIGHT_CHAT,
                OffsetDateTime.now().plusHours(9)
        );
        hostRequestId = matchRequestRepository.save(hostRequest).getId();

        jdbcTemplate.update(
                """
                INSERT INTO run_match_condition
                    (match_request_id, course_id, meeting_point, distance_min_meters, distance_max_meters, pace_min_sec, pace_max_sec)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                hostRequestId, courseId, "뚝섬유원지역 3번 출구", 5000, 8000, 360, 400
        );

        // 신청까지 미리 진행해서 PROPOSED 상태의 activityMatch를 만들어둔다
        activityMatchId = matchApplyService.apply(applicantUserId, hostRequestId);
    }

    private Long createUser(String label) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                label + "-" + System.nanoTime() + "@example.com", "dummy-hash", label, OffsetDateTime.now()
        );
    }

    // created_at과 decision_expires_at을 DB 서버 시각(CURRENT_TIMESTAMP) 기준으로 함께
    // 과거로 밀어서, chk_activity_match_time 제약(created_at < decision_expires_at <
    // scheduled_at)을 지키면서도 "이미 지난 기한"을 확정적으로 만든다.
    // 원래는 decision_expires_at만 created_at + 1ms로 세팅했는데, 그 1ms가 지나기 전에
    // 다음 단계(서비스 호출)까지 도달할 만큼 테스트가 빠르게 실행되면 아직 "미래"로
    // 판정되어 간헐적으로 실패했다(팀원 리뷰로 발견 — 전체 실행 2건, 클래스 단독 실행
    // 4건 실패). CURRENT_TIMESTAMP - INTERVAL로 두 값 다 실제 과거 시각에 고정하면
    // 테스트 실행 속도와 무관하게 항상 "지난 기한"이 된다.
    private void makeDeadlineOverdue(Long activityMatchId) {
        jdbcTemplate.update(
                """
                UPDATE activity_match
                SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 seconds',
                    decision_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """,
                activityMatchId
        );
    }

    @Test
    void 호스트가_수락하면_CONFIRMED로_전이되고_meeting_code가_발급된다() {
        MatchConfirmResponse response = matchDecisionService.accept(hostUserId, activityMatchId);

        assertThat(response.activityMatchId()).isEqualTo(activityMatchId);
        assertThat(response.meetingCode()).hasSize(6);
        assertThat(response.confirmedAt()).isNotNull();

        ActivityMatch activityMatch = activityMatchRepository.findById(activityMatchId).orElseThrow();
        assertThat(activityMatch.getStatus()).isEqualTo(ActivityMatchStatus.CONFIRMED);

        MatchRequest updatedHost = matchRequestRepository.findById(hostRequestId).orElseThrow();
        assertThat(updatedHost.getStatus()).isEqualTo(MatchRequestStatus.MATCHED);
    }

    @Test
    void 호스트가_아닌_사람이_수락하면_예외가_발생한다() {
        assertThatThrownBy(() -> matchDecisionService.accept(applicantUserId, activityMatchId))
                .isInstanceOf(NotMatchParticipantException.class);
    }

    @Test
    void 이미_응답한_매칭을_다시_수락하면_예외가_발생한다() {
        matchDecisionService.accept(hostUserId, activityMatchId);

        assertThatThrownBy(() -> matchDecisionService.accept(hostUserId, activityMatchId))
                .isInstanceOf(MatchRequestNotSearchingException.class);
    }

    @Test
    void 호스트가_거절하면_REJECTED로_전이되고_게시글이_SEARCHING으로_복귀한다() {
        matchDecisionService.reject(hostUserId, activityMatchId);

        ActivityMatch activityMatch = activityMatchRepository.findById(activityMatchId).orElseThrow();
        assertThat(activityMatch.getStatus()).isEqualTo(ActivityMatchStatus.REJECTED);

        MatchRequest updatedHost = matchRequestRepository.findById(hostRequestId).orElseThrow();
        assertThat(updatedHost.getStatus()).isEqualTo(MatchRequestStatus.SEARCHING);
    }

    @Test
    void 거절하면_두_참여_연결_모두_해제된다() {
        matchDecisionService.reject(hostUserId, activityMatchId);

        var participants = matchParticipantRepository.findByActivityMatchId(activityMatchId);
        assertThat(participants).allSatisfy(p -> assertThat(p.getReleasedAt()).isNotNull());
    }

    @Test
    void 응답_기한이_지나면_수락할_수_없다() {
        // 상태는 여전히 PROPOSED지만(스케줄러가 아직 안 돈 상황을 흉내냄) decisionExpiresAt만
        // 이미 지난 상태를 만든다.
        makeDeadlineOverdue(activityMatchId);
        // 위 raw SQL 업데이트는 JPA 영속성 컨텍스트를 안 거치므로, @BeforeEach의 apply()가
        // 이미 로드해둔 ActivityMatch 1차 캐시가 갱신 안 된 채로 남는다. clear()로 캐시를
        // 비워야 이후 findById()가 DB의 최신 값을 다시 읽어온다(실제 운영에서는 apply()와
        // accept()/reject()가 항상 별개 트랜잭션이라 이 문제가 없음 — 테스트에서만 필요).
        entityManager.clear();

        assertThatThrownBy(() -> matchDecisionService.accept(hostUserId, activityMatchId))
                .isInstanceOf(MatchRequestNotSearchingException.class);
    }

    @Test
    void 응답_기한이_지나면_거절할_수_없다() {
        makeDeadlineOverdue(activityMatchId);
        // 위 raw SQL 업데이트는 JPA 영속성 컨텍스트를 안 거치므로, @BeforeEach의 apply()가
        // 이미 로드해둔 ActivityMatch 1차 캐시가 갱신 안 된 채로 남는다. clear()로 캐시를
        // 비워야 이후 findById()가 DB의 최신 값을 다시 읽어온다(실제 운영에서는 apply()와
        // accept()/reject()가 항상 별개 트랜잭션이라 이 문제가 없음 — 테스트에서만 필요).
        entityManager.clear();

        assertThatThrownBy(() -> matchDecisionService.reject(hostUserId, activityMatchId))
                .isInstanceOf(MatchRequestNotSearchingException.class);
    }

    @Test
    void 응답_기한이_지난_매칭은_자동으로_EXPIRED_처리된다() {
        makeDeadlineOverdue(activityMatchId);
        // 위 raw SQL 업데이트는 JPA 영속성 컨텍스트를 안 거치므로, @BeforeEach의 apply()가
        // 이미 로드해둔 ActivityMatch 1차 캐시가 갱신 안 된 채로 남는다. clear()로 캐시를
        // 비워야 이후 findById()가 DB의 최신 값을 다시 읽어온다(실제 운영에서는 apply()와
        // accept()/reject()가 항상 별개 트랜잭션이라 이 문제가 없음 — 테스트에서만 필요).
        entityManager.clear();

        matchDecisionService.expireOverdue();

        ActivityMatch activityMatch = activityMatchRepository.findById(activityMatchId).orElseThrow();
        assertThat(activityMatch.getStatus()).isEqualTo(ActivityMatchStatus.EXPIRED);

        MatchRequest updatedHost = matchRequestRepository.findById(hostRequestId).orElseThrow();
        assertThat(updatedHost.getStatus()).isEqualTo(MatchRequestStatus.SEARCHING);
    }

    @Test
    void 응답_기한이_안_지난_매칭은_자동만료_대상이_아니다() {
        matchDecisionService.expireOverdue();

        ActivityMatch activityMatch = activityMatchRepository.findById(activityMatchId).orElseThrow();
        assertThat(activityMatch.getStatus()).isEqualTo(ActivityMatchStatus.PROPOSED);
    }

    // 회귀 테스트: 같은 배치 안에 참가자 데이터가 비정상인 건(예: 신청자 행 유실)이 섞여
    // 있어도, 그 한 건 때문에 예외가 던져져 @Transactional 배치 전체가 롤백되면 안 되고
    // (원래 있었던 문제 — PR #57 리뷰 피드백), 나머지 정상 건은 그대로 처리돼야 한다.
    // 비정상 건 자체는 상태를 바꾸지 않고 건너뛰어(PROPOSED 유지) 다음 배치에서 재시도되게 한다.
    @Test
    void 자동만료_배치_중_한_건의_참가자_데이터가_비정상이어도_다른_정상_건은_처리된다() {
        Long activityMatch2Id = createSecondOverdueProposedMatch();

        makeDeadlineOverdue(activityMatchId);
        makeDeadlineOverdue(activityMatch2Id);

        // 첫 번째 건(activityMatchId)의 신청자(slot B) 참가자 행을 강제로 지워 데이터 이상을 재현
        jdbcTemplate.update(
                "DELETE FROM match_participant WHERE activity_match_id = ? AND slot = 'B'", activityMatchId);
        entityManager.clear();

        matchDecisionService.expireOverdue();

        ActivityMatch malformed = activityMatchRepository.findById(activityMatchId).orElseThrow();
        assertThat(malformed.getStatus()).isEqualTo(ActivityMatchStatus.PROPOSED);

        ActivityMatch normal = activityMatchRepository.findById(activityMatch2Id).orElseThrow();
        assertThat(normal.getStatus()).isEqualTo(ActivityMatchStatus.EXPIRED);
    }

    @Test
    void 확정된_매칭은_예정_종료_시각이_지나면_ENDED로_전이되고_참가자가_해제된다() {
        // 먼저 정상적으로 수락(CONFIRMED)까지 진행 — 이 시점엔 시각들이 전부 정상 범위라
        // accept() 내부의 ensureRespondable() 검증(응답 기한 이내)을 그대로 통과한다.
        matchDecisionService.accept(hostUserId, activityMatchId);
        // accept()가 남긴 변경(status=CONFIRMED 등)을 DB에 반영해둔다 — 안 그러면 바로 아래
        // entityManager.clear()가 아직 flush 안 된 이 변경을 DB 반영 전에 그냥 버려버린다.
        entityManager.flush();

        pushScheduledEndAtIntoThePast(activityMatchId);
        entityManager.clear();

        matchDecisionService.endOverdueActivities();

        ActivityMatch activityMatch = activityMatchRepository.findById(activityMatchId).orElseThrow();
        assertThat(activityMatch.getStatus()).isEqualTo(ActivityMatchStatus.ENDED);

        // 게시글은 CLOSED로 전이돼야 한다 — SEARCHING으로 되돌릴 이유는 없지만(활동이 정상
        // 종료된 것뿐), MATCHED로 남겨두면 uq_match_request_active_user와 /requests/me 양쪽에서
        // 계속 "활성" 게시글로 취급돼 새 게시글을 못 만들고 지나간 게시글이 계속 노출된다
        // (팀원 리뷰로 발견).
        assertThat(matchRequestRepository.findById(hostRequestId).orElseThrow().getStatus())
                .isEqualTo(MatchRequestStatus.CLOSED);

        // 반면 두 참가자는 반드시 release()돼서 다른 매칭에 다시 참여할 수 있어야 한다
        // (이게 바로 팀원 리뷰로 발견된, released_at이 영원히 안 채워지던 문제).
        var participants = matchParticipantRepository.findByActivityMatchId(activityMatchId);
        assertThat(participants).allSatisfy(p -> assertThat(p.getReleasedAt()).isNotNull());
    }

    @Test
    void 예정_종료_시각이_안_지난_확정_매칭은_자연종료_대상이_아니다() {
        matchDecisionService.accept(hostUserId, activityMatchId);

        matchDecisionService.endOverdueActivities();

        ActivityMatch activityMatch = activityMatchRepository.findById(activityMatchId).orElseThrow();
        assertThat(activityMatch.getStatus()).isEqualTo(ActivityMatchStatus.CONFIRMED);
    }

    // expireOverdue() 회귀 테스트와 동일한 이유 — 자동종료 배치에도 동일한 위험이 있었다.
    @Test
    void 자동종료_배치_중_한_건의_참가자_데이터가_비정상이어도_다른_정상_건은_처리된다() {
        Long activityMatch2Id = createSecondOverdueProposedMatch();
        Long host2UserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM match_participant WHERE activity_match_id = ? AND slot = 'A'",
                Long.class, activityMatch2Id);

        matchDecisionService.accept(hostUserId, activityMatchId);
        matchDecisionService.accept(host2UserId, activityMatch2Id);
        entityManager.flush();

        pushScheduledEndAtIntoThePast(activityMatchId);
        pushScheduledEndAtIntoThePast(activityMatch2Id);

        // 첫 번째 건(activityMatchId)의 호스트(slot A) 참가자 행을 강제로 지워 데이터 이상을 재현
        jdbcTemplate.update(
                "DELETE FROM match_participant WHERE activity_match_id = ? AND slot = 'A'", activityMatchId);
        entityManager.clear();

        matchDecisionService.endOverdueActivities();

        ActivityMatch malformed = activityMatchRepository.findById(activityMatchId).orElseThrow();
        assertThat(malformed.getStatus()).isEqualTo(ActivityMatchStatus.CONFIRMED);

        ActivityMatch normal = activityMatchRepository.findById(activityMatch2Id).orElseThrow();
        assertThat(normal.getStatus()).isEqualTo(ActivityMatchStatus.ENDED);
        var normalParticipants = matchParticipantRepository.findByActivityMatchId(activityMatch2Id);
        assertThat(normalParticipants).allSatisfy(p -> assertThat(p.getReleasedAt()).isNotNull());

        // 정상 처리된 건의 호스트 게시글도 CLOSED로 전이돼야 한다(비정상 건 스킵과 무관하게).
        Long host2RequestId = jdbcTemplate.queryForObject(
                "SELECT match_request_id FROM match_participant WHERE activity_match_id = ? AND slot = 'A'",
                Long.class, activityMatch2Id);
        assertThat(matchRequestRepository.findById(host2RequestId).orElseThrow().getStatus())
                .isEqualTo(MatchRequestStatus.CLOSED);
    }

    // 두 스케줄러 회귀 테스트가 공통으로 쓰는, @BeforeEach와 별개인 두 번째 PROPOSED 매칭 생성.
    private Long createSecondOverdueProposedMatch() {
        Long host2UserId = createUser("host2");
        Long applicant2UserId = createUser("applicant2");

        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");

        MatchRequest host2Request = new MatchRequest(
                host2UserId, OffsetDateTime.now().plusHours(48), TalkLevel.LIGHT_CHAT,
                OffsetDateTime.now().plusHours(9)
        );
        Long host2RequestId = matchRequestRepository.save(host2Request).getId();

        jdbcTemplate.update(
                """
                INSERT INTO run_match_condition
                    (match_request_id, course_id, meeting_point, distance_min_meters, distance_max_meters, pace_min_sec, pace_max_sec)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                host2RequestId, courseId, "뚝섬유원지역 3번 출구", 5000, 8000, 360, 400
        );

        return matchApplyService.apply(applicant2UserId, host2RequestId);
    }

    // makeDeadlineOverdue()와 동일한 이유(팀원 리뷰로 발견) — created_at까지 포함한 네 시각을
    // 전부 DB 서버 시각(CURRENT_TIMESTAMP) 기준 실제 과거로 고정해서, created_at <
    // decision_expires_at < scheduled_at < scheduled_end_at 순서 제약은 지키면서도 테스트
    // 실행 속도와 무관하게 항상 "이미 지난 예정 종료 시각"이 되도록 한다.
    private void pushScheduledEndAtIntoThePast(Long activityMatchId) {
        jdbcTemplate.update(
                """
                UPDATE activity_match
                SET created_at = CURRENT_TIMESTAMP - INTERVAL '4 seconds',
                    decision_expires_at = CURRENT_TIMESTAMP - INTERVAL '3 seconds',
                    scheduled_at = CURRENT_TIMESTAMP - INTERVAL '2 seconds',
                    scheduled_end_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """,
                activityMatchId
        );
    }

    // 탈퇴 상태(chk_users_account_lifecycle: 개인정보 전부 NULL + deleted_at 기록)로 변경한다.
    private void withdrawUser(Long userId) {
        jdbcTemplate.update(
                """
                UPDATE users
                SET email = NULL, password_hash = NULL, nickname = NULL,
                    email_verified_at = NULL, default_talk_level = NULL, deleted_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                userId
        );
    }

    // apply()의 "탈퇴한 호스트에게 신청 불가"와 대칭인 케이스. 신청 후 신청자가 탈퇴했는데 호스트가
    // 수락하면 존재하지 않는 사람과 매칭이 확정되고, 확정된 매칭은 만료 배치로 정리되지 않는다.
    @Test
    void 신청자가_탈퇴하면_호스트가_수락할_수_없다() {
        withdrawUser(applicantUserId);

        assertThatThrownBy(() -> matchDecisionService.accept(hostUserId, activityMatchId))
                .isInstanceOf(ApplicantWithdrawnException.class)
                .hasMessage("신청자가 탈퇴해 수락할 수 없어요. 거절하면 다시 모집할 수 있어요.");

        // 확정되지 않았고, 호스트 게시글도 MATCHED로 넘어가지 않았다
        assertThat(activityMatchRepository.findById(activityMatchId).orElseThrow().getStatus())
                .isEqualTo(ActivityMatchStatus.PROPOSED);
        assertThat(matchRequestRepository.findById(hostRequestId).orElseThrow().getStatus())
                .isEqualTo(MatchRequestStatus.PENDING_CONFIRMATION);
    }

    // 수락 불가 메시지가 "거절하면 다시 모집할 수 있다"고 안내하므로, 실제로 거절이 막히지 않아야 한다.
    @Test
    void 신청자가_탈퇴해도_호스트는_거절할_수_있고_게시글이_SEARCHING으로_복귀한다() {
        withdrawUser(applicantUserId);

        matchDecisionService.reject(hostUserId, activityMatchId);

        assertThat(activityMatchRepository.findById(activityMatchId).orElseThrow().getStatus())
                .isEqualTo(ActivityMatchStatus.REJECTED);
        assertThat(matchRequestRepository.findById(hostRequestId).orElseThrow().getStatus())
                .isEqualTo(MatchRequestStatus.SEARCHING);
    }

    // 탈퇴 검사가 ensureRespondable() 뒤에 있어야 하는 이유를 고정한다: 이미 확정된 매칭을 그 뒤 신청자가
    // 탈퇴한 상태로 다시 수락하면, "거절하라"는 (이때는 거짓인) 안내가 아니라 기존 메시지가 나가야 한다.
    @Test
    void 이미_확정된_매칭은_신청자가_탈퇴한_뒤에도_기존_메시지로_거부된다() {
        matchDecisionService.accept(hostUserId, activityMatchId);
        withdrawUser(applicantUserId);

        assertThatThrownBy(() -> matchDecisionService.accept(hostUserId, activityMatchId))
                .isInstanceOf(MatchRequestNotSearchingException.class)
                .hasMessage("이미 응답했거나 종료된 매칭이에요.");
    }
}
