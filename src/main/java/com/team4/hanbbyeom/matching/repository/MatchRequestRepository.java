package com.team4.hanbbyeom.matching.repository;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

public interface MatchRequestRepository extends JpaRepository<MatchRequest, Long> {

    // 모집 탭 목록(GET /api/matching/board) 조회용 쿼리.
    // match_request 하나당 코스명/거리/페이스(run_match_condition, running_course)와 작성자
    // 닉네임·신뢰도(users, trust_profile)까지 한 번에 조인해서 화면에 필요한 걸 통째로 가져온다
    // — N+1 없이 목록 화면 하나를 한 번의 쿼리로 채우려는 목적. trust_profile은 아직 활동 이력이
    // 없는 신규 유저면 행 자체가 없을 수 있어 LEFT JOIN(없으면 rating/count는 null로 나옴).
    // WHERE절의 파라미터들은 전부 "값이 없으면(:xxx IS NULL) 그 조건은 무시"하는 선택적 필터이고,
    // 거리/페이스는 정확히 일치가 아니라 "게시글의 범위와 필터 범위가 겹치는지"로 판단한다
    // (예: 필터 minDistance=9000인데 게시글이 5000~8000이면 겹치지 않으므로 제외).
    @Query(value = """
    SELECT mr.id AS id,
           co.name AS courseName,
           rc.distance_min_meters AS distanceMinMeters,
           rc.distance_max_meters AS distanceMaxMeters,
           rc.pace_min_sec AS paceMinSec,
           rc.pace_max_sec AS paceMaxSec,
           mr.talk_level AS talkLevel,
           mr.scheduled_at AS scheduledAt,
           mr.user_id AS userId,
           u.nickname AS authorNickname,
           tp.average_rating AS authorRating,
           tp.completed_activity_count AS authorCompletedCount
    FROM match_request mr
    JOIN run_match_condition rc ON rc.match_request_id = mr.id
    JOIN running_course co ON co.id = rc.course_id
    JOIN users u ON u.id = mr.user_id
    LEFT JOIN trust_profile tp ON tp.user_id = mr.user_id
    WHERE mr.status = 'SEARCHING'
      AND mr.activity_type = 'RUN'
      AND mr.user_id <> :excludeUserId
      AND (:course IS NULL OR co.name = :course)
      AND (:talkLevel IS NULL OR mr.talk_level = :talkLevel)
      AND (:minDistance IS NULL OR rc.distance_max_meters >= :minDistance)
      AND (:maxDistance IS NULL OR rc.distance_min_meters <= :maxDistance)
      AND (:minPace IS NULL OR rc.pace_max_sec >= :minPace)
      AND (:maxPace IS NULL OR rc.pace_min_sec <= :maxPace)
      AND (CAST(:dateFrom AS timestamptz) IS NULL OR mr.scheduled_at >= CAST(:dateFrom AS timestamptz))
      AND (CAST(:dateTo AS timestamptz) IS NULL OR mr.scheduled_at < CAST(:dateTo AS timestamptz))
    """, nativeQuery = true)
    List<MatchBoardRow> searchBoard(@Param("course") String course,
                                    @Param("talkLevel") String talkLevel,
                                    @Param("minDistance") Integer minDistance,
                                    @Param("maxDistance") Integer maxDistance,
                                    @Param("minPace") Integer minPace,
                                    @Param("maxPace") Integer maxPace,
                                    @Param("dateFrom") OffsetDateTime dateFrom,
                                    @Param("dateTo") OffsetDateTime dateTo,
                                    @Param("excludeUserId") Long excludeUserId);

    // 네이티브 쿼리 결과 한 행을 매핑하는 Spring Data JPA 프로젝션 인터페이스.
    // SELECT의 `AS 별칭`이 여기 getter 이름(별칭 앞글자만 소문자로 바꾼 형태)과 일치해야
    // 값이 채워진다 — 예: `AS distanceMinMeters` → getDistanceMinMeters().
    interface MatchBoardRow {
        Long getId();
        String getCourseName();
        Integer getDistanceMinMeters();
        Integer getDistanceMaxMeters();
        Integer getPaceMinSec();
        Integer getPaceMaxSec();
        String getTalkLevel();
        Instant getScheduledAt();
        Long getUserId();
        String getAuthorNickname();
        Double getAuthorRating();
        Integer getAuthorCompletedCount();
    }

    // 모집글 상세(GET /api/matching/requests/{id})용 조회. 목록(searchBoard)과 달리
    // status·meetingPoint까지 포함하고, status 필터 없이 어떤 상태의 글이든 조회 가능
    // (작성자 본인이 취소/완료된 자기 글을 다시 열어볼 수도 있어야 하므로).
    // 작성자 닉네임은 여기서 조인하지 않는다 — Service(MatchRequestBoardService)가 별도로
    // fetchNicknames()를 호출해서 채운다(도메인 간 결합도를 낮추려는 의도).
    @Query(value = """
        SELECT
            mr.id AS id,
            co.name AS courseName,
            rc.distance_min_meters AS distanceMinMeters,
            rc.distance_max_meters AS distanceMaxMeters,
            rc.pace_min_sec AS paceMinSec,
            rc.pace_max_sec AS paceMaxSec,
            rc.meeting_point AS meetingPoint,
            mr.scheduled_at AS scheduledAt,
            mr.talk_level AS talkLevel,
            mr.status AS status,
            mr.user_id AS userId
        FROM match_request mr
        JOIN run_match_condition rc ON rc.match_request_id = mr.id
        JOIN running_course co ON co.id = rc.course_id
        WHERE mr.id = :id
        """, nativeQuery = true)
    java.util.Optional<MatchRequestDetailRow> findDetailById(@Param("id") Long id);

    // 이 게시글에 현재 응답 대기 중인 신청이 몇 건인지 센다 — 상세 조회 화면에서
    // 호스트에게 "N명이 신청했어요" 같은 걸 보여줄 때 쓴다(MatchRequestResponse.pendingApplicantCount).
    // slot='B'(신청자)이면서 released_at이 아직 안 채워진(=아직 끝나지 않은) 참여만 센다.
    @Query(value = """
        SELECT COUNT(*)
        FROM match_participant mp
        WHERE mp.match_request_id = :matchRequestId
          AND mp.slot = 'B'
          AND mp.released_at IS NULL
        """, nativeQuery = true)
    long countPendingApplicants(@Param("matchRequestId") Long matchRequestId);

    // findDetailById() 결과 한 행을 매핑하는 프로젝션 — MatchBoardRow와 마찬가지로
    // SELECT의 `AS 별칭`과 getter 이름이 대응한다.
    interface MatchRequestDetailRow {
        Long getId();
        String getCourseName();
        Integer getDistanceMinMeters();
        Integer getDistanceMaxMeters();
        Integer getPaceMinSec();
        Integer getPaceMaxSec();
        String getMeetingPoint();
        Instant getScheduledAt();
        String getTalkLevel();
        String getStatus();
        Long getUserId();
    }
}