package com.team4.hanbbyeom.matching.repository;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface MatchRequestRepository extends JpaRepository<MatchRequest, Long> {

    @Query(value = """
        SELECT
            mr.id AS id,
            rc.course_name AS courseName,
            rc.distance_meters AS distanceMeters,
            rc.pace_min_sec AS paceMinSec,
            rc.pace_max_sec AS paceMaxSec,
            mr.talk_level AS talkLevel,
            mr.scheduled_at AS scheduledAt,
            mr.user_id AS userId
        FROM match_request mr
        JOIN run_match_condition rc ON rc.id = mr.run_match_condition_id
        WHERE mr.status = 'SEARCHING'
          AND mr.activity_type = 'RUN'
          AND (:region IS NULL OR rc.region = :region)
          AND (:talkLevel IS NULL OR mr.talk_level = :talkLevel)
          AND (:minDistance IS NULL OR rc.distance_meters >= :minDistance)
          AND (:maxDistance IS NULL OR rc.distance_meters <= :maxDistance)
          AND (CAST(:dateFrom AS timestamptz) IS NULL OR mr.scheduled_at >= :dateFrom)
          AND (CAST(:dateTo AS timestamptz) IS NULL OR mr.scheduled_at < :dateTo)
          AND mr.user_id <> :excludeUserId
        ORDER BY mr.created_at DESC
        """, nativeQuery = true)
    List<MatchBoardRow> searchBoard(
            @Param("region") String region,
            @Param("talkLevel") String talkLevel,
            @Param("minDistance") Integer minDistance,
            @Param("maxDistance") Integer maxDistance,
            @Param("dateFrom") OffsetDateTime dateFrom,
            @Param("dateTo") OffsetDateTime dateTo,
            @Param("excludeUserId") Long excludeUserId
    );

    interface MatchBoardRow {
        Long getId();
        String getCourseName();
        Integer getDistanceMeters();
        Integer getPaceMinSec();
        Integer getPaceMaxSec();
        String getTalkLevel();
        OffsetDateTime getScheduledAt();
        Long getUserId();
    }

    @Query(value = """
        SELECT
            mr.id AS id,
            rc.course_name AS courseName,
            rc.distance_meters AS distanceMeters,
            rc.pace_min_sec AS paceMinSec,
            rc.pace_max_sec AS paceMaxSec,
            rc.meeting_point AS meetingPoint,
            mr.scheduled_at AS scheduledAt,
            mr.talk_level AS talkLevel,
            mr.status AS status,
            mr.user_id AS userId
        FROM match_request mr
        JOIN run_match_condition rc ON rc.id = mr.run_match_condition_id
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
        Integer getDistanceMeters();
        Integer getPaceMinSec();
        Integer getPaceMaxSec();
        String getMeetingPoint();
        OffsetDateTime getScheduledAt();
        String getTalkLevel();
        String getStatus();
        Long getUserId();
    }
}