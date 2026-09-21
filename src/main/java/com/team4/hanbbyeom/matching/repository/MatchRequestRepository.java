package com.team4.hanbbyeom.matching.repository;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.MatchRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface MatchRequestRepository extends JpaRepository<MatchRequest, Long> {

    // 내 활성 모집글/신청 조회(GET /api/matching/requests/me)용 — uq_match_request_active_user
    // 부분 유니크 인덱스와 동일한 상태 집합(SEARCHING/PENDING_CONFIRMATION/MATCHED)이라
    // 이 유저에게 활성 행이 있다면 정확히 0건 또는 1건만 나온다.
    Optional<MatchRequest> findByUserIdAndStatusIn(Long userId, List<MatchRequestStatus> statuses);

    // 모집 탭 목록(GET /api/matching/board) 조회용 쿼리.
    // match_request 하나당 코스명/거리/페이스(run_match_condition, running_course)와 작성자
    // 닉네임·신뢰도(users, trust_profile)까지 한 번에 조인해서 화면에 필요한 걸 통째로 가져온다
    // — N+1 없이 목록 화면 하나를 한 번의 쿼리로 채우려는 목적. trust_profile은 아직 활동 이력이
    // 없는 신규 유저면 행 자체가 없을 수 있어 LEFT JOIN. 그 경우 rating은 null("평가 없음")
    // 횟수 두 개는COALESCE로 0 — 프로필 조회 API(TrustProfileLookupService.lookup())와 같은 규칙 (이슈 #94).
    // 최근 후기 1건은 LEFT JOIN LATERAL로 가져온다 — 작성자(reviewee_user_id)가 받은 후기 중
    // created_at DESC, id DESC(동시각 타이브레이커)로 첫 행. V15 복합 인덱스
    // (reviewee_user_id, created_at DESC, id DESC)가 이 정렬 순서와 정확히 일치해서 정렬 없이
    // 인덱스 첫 행만 읽고 끝난다. 후기가 없으면 두 컬럼 모두 NULL (이슈 #70).
    // 탈퇴한 작성자(users.deleted_at IS NOT NULL)의 글은 제외한다 — 탈퇴 시 닉네임이 NULL로 지워져도
    // SEARCHING 상태의 모집글은 그대로 남기 때문에, 이 조건이 없으면 닉네임 없는 글이 목록에 노출된다.
    // WHERE절의 파라미터들은 전부 "값이 없으면(:xxx IS NULL) 그 조건은 무시"하는 선택적 필터이고,
    // 거리/페이스는 정확히 일치가 아니라 "게시글의 범위와 필터 범위가 겹치는지"로 판단한다
    // (예: 필터 minDistance=9000인데 게시글이 5000~8000이면 겹치지 않으므로 제외).
    // 정렬·페이징(이슈 #103): 최신 글이 위로 오도록 created_at DESC, id DESC(동시각 타이브레이커)로
    // 고정하고, 커서(마지막으로 받은 글의 id)보다 "뒤"인 행만 LIMIT만큼 돌려준다. 커서 비교는
    // 정렬 키 (created_at, id) 행 비교로 해서 정렬 순서와 정확히 같은 기준으로 자른다 — id만 비교하면
    // created_at이 같은 순서라는 보장이 없어서 중복/누락이 생길 수 있다. 오프셋(page)이 아니라
    // 커서를 쓰는 이유: 모집 탭은 새 글이 계속 위로 들어오는 피드라 오프셋이면 스크롤 중 새 글이
    // 끼어들 때 같은 글이 두 번 보이거나 한 글이 건너뛰어진다. 커서는 "이 글 다음부터"라 영향이 없다.
    // 정렬용 인덱스는 따로 두지 않는다 — uq_match_request_active_user 때문에 SEARCHING 글은 사용자당
    // 최대 1건이라 정렬 대상이 작다(사용자 수 이하). 실데이터 EXPLAIN에서 정렬 비용이 보이면
    // (status, activity_type, created_at DESC, id DESC) 부분 인덱스를 그때 추가한다.
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
           COALESCE(tp.completed_activity_count, 0) AS authorCompletedCount,
           COALESCE(tp.no_show_report_count, 0) AS authorNoShowCount,
           latest_review.comment AS latestReviewComment,
           latest_review.created_at AS latestReviewCreatedAt
    FROM match_request mr
    JOIN run_match_condition rc ON rc.match_request_id = mr.id
    JOIN running_course co ON co.id = rc.course_id
    JOIN users u ON u.id = mr.user_id
    LEFT JOIN trust_profile tp ON tp.user_id = mr.user_id
    LEFT JOIN LATERAL (
        SELECT ar.comment, ar.created_at
        FROM activity_review ar
        WHERE ar.reviewee_user_id = mr.user_id
        ORDER BY ar.created_at DESC, ar.id DESC
        LIMIT 1
    ) latest_review ON true
    WHERE mr.status = 'SEARCHING'
      AND mr.activity_type = 'RUN'
      AND u.deleted_at IS NULL
      AND mr.user_id <> :excludeUserId
      AND (:course IS NULL OR co.name = :course)
      AND (:talkLevel IS NULL OR mr.talk_level = :talkLevel)
      AND (:minDistance IS NULL OR rc.distance_max_meters >= :minDistance)
      AND (:maxDistance IS NULL OR rc.distance_min_meters <= :maxDistance)
      AND (:minPace IS NULL OR rc.pace_max_sec >= :minPace)
      AND (:maxPace IS NULL OR rc.pace_min_sec <= :maxPace)
      AND (CAST(:dateFrom AS timestamptz) IS NULL OR mr.scheduled_at >= CAST(:dateFrom AS timestamptz))
      AND (CAST(:dateTo AS timestamptz) IS NULL OR mr.scheduled_at < CAST(:dateTo AS timestamptz))
      AND (CAST(:cursorId AS bigint) IS NULL
           OR (mr.created_at, mr.id) < (SELECT c.created_at, c.id FROM match_request c WHERE c.id = CAST(:cursorId AS bigint)))
    ORDER BY mr.created_at DESC, mr.id DESC
    LIMIT :limit
    """, nativeQuery = true)
    List<MatchBoardRow> searchBoard(@Param("course") String course,
                                    @Param("talkLevel") String talkLevel,
                                    @Param("minDistance") Integer minDistance,
                                    @Param("maxDistance") Integer maxDistance,
                                    @Param("minPace") Integer minPace,
                                    @Param("maxPace") Integer maxPace,
                                    @Param("dateFrom") OffsetDateTime dateFrom,
                                    @Param("dateTo") OffsetDateTime dateTo,
                                    @Param("excludeUserId") Long excludeUserId,
                                    @Param("cursorId") Long cursorId,
                                    @Param("limit") int limit);

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
        Integer getAuthorNoShowCount();
        String getLatestReviewComment();
        Instant getLatestReviewCreatedAt();
    }

    // 모집글 상세(GET /api/matching/requests/{id})용 조회. 목록(searchBoard)과 달리
    // status·meetingPoint까지 포함하고, status 필터 없이 어떤 상태의 글이든 조회 가능
    // (작성자 본인이 취소/완료된 자기 글을 다시 열어볼 수도 있어야 하므로).
    // 작성자 닉네임은 여기서 조인하지 않는다 — Service(MatchRequestBoardService)가 별도로
    // fetchNicknames()를 호출해서 채운다(도메인 간 결합도를 낮추려는 의도). 다만 평점/완료횟수/
    // 노쇼횟수/최근후기는 searchBoard와 똑같이 여기서 조인해서 내려준다 — 이전엔 이 값들을
    // 아예 안 가져와서 Service가 null로 하드코딩했었다 (이슈 #70 버그 수정).
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
            mr.user_id AS userId,
            tp.average_rating AS authorRating,
            COALESCE(tp.completed_activity_count, 0) AS authorCompletedCount,
            COALESCE(tp.no_show_report_count, 0) AS authorNoShowCount,
            latest_review.comment AS latestReviewComment,
            latest_review.created_at AS latestReviewCreatedAt
        FROM match_request mr
        JOIN run_match_condition rc ON rc.match_request_id = mr.id
        JOIN running_course co ON co.id = rc.course_id
        LEFT JOIN trust_profile tp ON tp.user_id = mr.user_id
        LEFT JOIN LATERAL (
            SELECT ar.comment, ar.created_at
            FROM activity_review ar
            WHERE ar.reviewee_user_id = mr.user_id
            ORDER BY ar.created_at DESC, ar.id DESC
            LIMIT 1
        ) latest_review ON true
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
        Double getAuthorRating();
        Integer getAuthorCompletedCount();
        Integer getAuthorNoShowCount();
        String getLatestReviewComment();
        Instant getLatestReviewCreatedAt();
    }

    // GET /api/matching/requests (내 모집글 전체 목록)용 조회. findDetailById()와 조인 구조는
    // 동일하되, 특정 id 하나가 아니라 이 유저가 작성한 모든 상태의 게시글을 최신순으로 가져온다.
    // 상태 필터링/재매핑은 프론트가 담당하므로 여기서는 status를 그대로 노출한다.
    @Query(value = """
        SELECT
            mr.id AS id,
            co.name AS courseName,
            rc.distance_min_meters AS distanceMinMeters,
            rc.distance_max_meters AS distanceMaxMeters,
            mr.scheduled_at AS scheduledAt,
            mr.talk_level AS talkLevel,
            mr.status AS status
        FROM match_request mr
        JOIN run_match_condition rc ON rc.match_request_id = mr.id
        JOIN running_course co ON co.id = rc.course_id
        WHERE mr.user_id = :userId
        ORDER BY mr.created_at DESC, mr.id DESC
        """, nativeQuery = true)
    List<MyPostRow> findMyPosts(@Param("userId") Long userId);

    // 위 쿼리 결과 한 행을 매핑하는 프로젝션 — findDetailById()의 MatchRequestDetailRow와
    // 같은 패턴(SELECT의 AS 별칭과 getter 이름이 대응).
    interface MyPostRow {
        Long getId();
        String getCourseName();
        Integer getDistanceMinMeters();
        Integer getDistanceMaxMeters();
        Instant getScheduledAt();
        String getTalkLevel();
        String getStatus();
    }
}