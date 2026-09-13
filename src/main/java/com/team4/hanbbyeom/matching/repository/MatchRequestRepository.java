package com.team4.hanbbyeom.matching.repository;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

public interface MatchRequestRepository extends JpaRepository<MatchRequest, Long> {

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
      AND (CAST(:dateFrom AS timestamptz) IS NULL OR mr.scheduled_at >= CAST(:dateFrom AS timestamptz))
      AND (CAST(:dateTo AS timestamptz) IS NULL OR mr.scheduled_at < CAST(:dateTo AS timestamptz))
    """, nativeQuery = true)
    List<MatchBoardRow> searchBoard(@Param("course") String course,
                                    @Param("talkLevel") String talkLevel,
                                    @Param("minDistance") Integer minDistance,
                                    @Param("maxDistance") Integer maxDistance,
                                    @Param("dateFrom") OffsetDateTime dateFrom,
                                    @Param("dateTo") OffsetDateTime dateTo,
                                    @Param("excludeUserId") Long excludeUserId);

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

    @Query(value = """
        SELECT COUNT(*)
        FROM match_participant mp
        WHERE mp.match_request_id = :matchRequestId
          AND mp.slot = 'B'
          AND mp.released_at IS NULL
        """, nativeQuery = true)
    long countPendingApplicants(@Param("matchRequestId") Long matchRequestId);

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