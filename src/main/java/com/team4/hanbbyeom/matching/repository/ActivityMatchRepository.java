package com.team4.hanbbyeom.matching.repository;

import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.ActivityMatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

// activity_match(신청~확정 상태의 매칭 건) 전용 리포지토리.
public interface ActivityMatchRepository extends JpaRepository<ActivityMatch, Long> {

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
               tp.completed_activity_count AS hostCompletedCount
        FROM match_participant applicant
        JOIN activity_match am ON am.id = applicant.activity_match_id
        JOIN match_participant host ON host.activity_match_id = am.id AND host.slot = 'A'
        JOIN users u ON u.id = host.user_id
        LEFT JOIN trust_profile tp ON tp.user_id = host.user_id
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
    }
}
