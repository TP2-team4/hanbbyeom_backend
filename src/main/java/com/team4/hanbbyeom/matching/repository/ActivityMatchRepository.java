package com.team4.hanbbyeom.matching.repository;

import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.ActivityMatchStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

// activity_match(신청~확정 상태의 매칭 건) 전용 리포지토리.
public interface ActivityMatchRepository extends JpaRepository<ActivityMatch, Long> {

    // feedback 도메인의 후기/노쇼 신고 제출 시 쓰는 비관적 락(SELECT ... FOR UPDATE) 조회.
    // activity_review와 no_show_report가 서로 다른 테이블이라, 각 테이블 내부의 UNIQUE
    // 제약("이 활동엔 후기 한 번만"/"이 활동엔 신고 한 번만")만으로는 "이 활동엔 후기든
    // 신고든 하나만"이라는 두 테이블을 아우르는 규칙을 DB가 보장하지 못한다 — 같은 사용자가
    // 같은 활동에 후기와 신고를 거의 동시에 보내면 existsBy 체크만으로는 둘 다 통과해 둘 다
    // 저장될 수 있다. 이 활동 행을 먼저 잠가서 같은 activityMatchId에 대한 두 요청을 강제로
    // 순서대로 처리하면, 뒤에 처리되는 쪽의 existsBy 체크가 먼저 커밋된 결과를 보고 정확히
    // 거부할 수 있다 (matching 도메인의 matching_mutex와 같은 목적이지만, 전역 잠금 행 대신
    // 이 activityMatchId 하나만 잠가서 무관한 다른 매칭의 후기/신고 제출까지 막지 않는다).
    // (PR #83 리뷰로 발견된 레이스)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM ActivityMatch m WHERE m.id = :id")
    Optional<ActivityMatch> findByIdForUpdate(@Param("id") Long id);

    // 스케줄러(MatchExpireScheduler)가 자동 만료 대상을 찾을 때 쓴다.
    // status=PROPOSED(아직 호스트 응답 대기 중)이면서 decisionExpiresAt이 now보다 과거인 건들.
    List<ActivityMatch> findByStatusAndDecisionExpiresAtBefore(ActivityMatchStatus status, OffsetDateTime now);

    // 스케줄러가 자연 종료 대상을 찾을 때 쓴다.
    // status=CONFIRMED(확정됨)이면서 scheduledEndAt이 now보다 과거인 건들 — 활동 시간이
    // 이미 지났는데 아직 ENDED로 전이 안 된 매칭들이다.
    List<ActivityMatch> findByStatusAndScheduledEndAtBefore(ActivityMatchStatus status, OffsetDateTime now);

    // 신청자 본인이 지금까지 넣은 신청 내역 목록(GET /api/matching/board/applications)용 조회.
    // activity_match 자체가 신청 시점 스냅샷(코스명/거리/일정/대화수준)을 이미 갖고 있어서
    // run_match_condition/running_course를 다시 조인할 필요가 없다 — 호스트 닉네임·신뢰도만
    // slot='A' 참가자를 거쳐 조인한다. 상태(PENDING/ACCEPTED/REJECTED/CANCELLED) 매핑과 상태
    // 필터링은 Service(MatchApplyService)가 closedByUserId까지 보고 판단한다.
    // 호스트의 노쇼횟수·최근후기는 모집 탭 목록(MatchRequestRepository.searchBoard)과 같은 방식으로
    // 조인한다 — 세 화면(목록/상세/내 신청 내역)이 같은 AuthorSummary를 쓰는데 이 화면만 비어
    // 보이는 불일치를 막기 위함 (이슈 #70).
    @Query(value = """
        SELECT am.id AS id,
               am.status AS status,
               am.closed_by_user_id AS closedByUserId,
               am.course_name AS courseName,
               am.distance_min_meters AS distanceMinMeters,
               am.distance_max_meters AS distanceMaxMeters,
               am.scheduled_at AS scheduledAt,
               am.talk_level AS talkLevel,
               host.match_request_id AS hostMatchRequestId,
               u.nickname AS hostNickname,
               tp.average_rating AS hostRating,
               tp.completed_activity_count AS hostCompletedCount,
               tp.no_show_report_count AS hostNoShowCount,
               latest_review.comment AS latestReviewComment,
               latest_review.created_at AS latestReviewCreatedAt
        FROM match_participant applicant
        JOIN activity_match am ON am.id = applicant.activity_match_id
        JOIN match_participant host ON host.activity_match_id = am.id AND host.slot = 'A'
        JOIN users u ON u.id = host.user_id
        LEFT JOIN trust_profile tp ON tp.user_id = host.user_id
        LEFT JOIN LATERAL (
            SELECT ar.comment, ar.created_at
            FROM activity_review ar
            WHERE ar.reviewee_user_id = host.user_id
            ORDER BY ar.created_at DESC, ar.id DESC
            LIMIT 1
        ) latest_review ON true
        WHERE applicant.slot = 'B' AND applicant.user_id = :applicantUserId
        ORDER BY am.created_at DESC
        """, nativeQuery = true)
    List<MyApplicationRow> findMyApplications(@Param("applicantUserId") Long applicantUserId);

    // findMyApplications() 결과 한 행을 매핑하는 프로젝션 — MatchBoardRow와 마찬가지로
    // SELECT의 `AS 별칭`과 getter 이름이 대응한다.
    interface MyApplicationRow {
        Long getId();
        String getStatus();
        Long getClosedByUserId();
        String getCourseName();
        Integer getDistanceMinMeters();
        Integer getDistanceMaxMeters();
        Instant getScheduledAt();
        String getTalkLevel();
        Long getHostMatchRequestId();
        String getHostNickname();
        Double getHostRating();
        Integer getHostCompletedCount();
        Integer getHostNoShowCount();
        String getLatestReviewComment();
        Instant getLatestReviewCreatedAt();
    }

    // 내 활동 이력 목록(GET /api/matching/matches)용 조회. 채팅 목록(ChatMessageRepository.findChatListByUserId)과
    // 같이 "내가 참가자이면서 한 번이라도 확정된(confirmed_at IS NOT NULL) 매칭"만 대상으로 한다 —
    // released_at 조건을 쓰지 않아 끝난 매칭도 남기고, 신청 대기·거절·만료처럼 확정된 적 없는 매칭은 제외된다.
    // 확정 후 취소된 매칭(CANCELLED)은 confirmed_at이 유지되므로 이력에 포함된다.
    //
    // 후기·노쇼 신고 제출 여부는 EXISTS 서브쿼리로 한 번에 가져온다(활동마다 따로 조회하는 N+1 방지).
    // 후기와 신고는 활동당 하나만 낼 수 있어(ActivityFeedbackService 규칙) 두 CASE가 겹치는 일은 없다.
    // 상대 닉네임은 users에서 가져오며, 상대가 탈퇴했다면 NULL이 된다(참가 행은 그대로 남으므로 항목은 유지).
    // 정렬은 활동 시작 시각 최신순(id는 같은 시각의 순서를 고정하는 용도).
    @Query(value = """
        SELECT am.id AS activityMatchId,
               am.course_name AS courseName,
               am.distance_min_meters AS distanceMinMeters,
               am.distance_max_meters AS distanceMaxMeters,
               am.scheduled_at AS scheduledAt,
               am.scheduled_end_at AS scheduledEndAt,
               am.status AS status,
               am.closed_by_user_id AS closedByUserId,
               u.nickname AS counterpartNickname,
               CASE
                   WHEN EXISTS (SELECT 1 FROM activity_review r
                                WHERE r.activity_match_id = am.id AND r.reviewer_user_id = :userId)
                       THEN 'REVIEW'
                   WHEN EXISTS (SELECT 1 FROM no_show_report n
                                WHERE n.activity_match_id = am.id AND n.reporter_user_id = :userId)
                       THEN 'NO_SHOW_REPORT'
                   ELSE NULL
               END AS submittedFeedbackType
        FROM match_participant mine
        JOIN activity_match am ON am.id = mine.activity_match_id
        JOIN match_participant other
          ON other.activity_match_id = am.id
         AND other.user_id <> :userId
        JOIN users u ON u.id = other.user_id
        WHERE mine.user_id = :userId
          AND am.confirmed_at IS NOT NULL
        ORDER BY am.scheduled_at DESC, am.id DESC
        """, nativeQuery = true)
    List<MyActivityRow> findMyActivities(@Param("userId") Long userId);

    // findMyActivities() 결과 한 행을 매핑하는 프로젝션 — SELECT의 `AS 별칭`과 getter 이름이 대응한다.
    interface MyActivityRow {
        Long getActivityMatchId();
        String getCourseName();
        Integer getDistanceMinMeters();
        Integer getDistanceMaxMeters();
        Instant getScheduledAt();
        Instant getScheduledEndAt();
        String getStatus();
        Long getClosedByUserId();
        String getCounterpartNickname();
        String getSubmittedFeedbackType();
    }
}
