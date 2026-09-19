package com.team4.hanbbyeom.matching.repository;

import com.team4.hanbbyeom.matching.domain.MatchParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

// match_participant(한 activity_match에 딸린 호스트/신청자 2명) 전용 리포지토리.
public interface MatchParticipantRepository extends JpaRepository<MatchParticipant, Long> {

    // 하나의 activity_match에 연결된 참가자 2명(slot A=호스트, slot B=신청자)을 한 번에 가져온다.
    // MatchApplyService.cancelApplication()에서 "이 매칭의 호스트/신청자가 각각 누구인지" 찾을 때 쓴다.
    List<MatchParticipant> findByActivityMatchId(Long activityMatchId);

    // 채팅 접근 권한 확인용 — 매칭이 끝나 releasedAt이 채워진 참가자도 과거 메시지는
    // 계속 조회할 수 있어야 하므로 활성 여부 조건 없이 매칭과 사용자 ID만 확인한다.
    boolean existsByActivityMatchIdAndUserId(Long activityMatchId, Long userId);

    // 컨트롤러/프론트가 알고 있는 건 "게시글 id(matchRequestId)"뿐이고, 실제로 상태를 바꿔야 할
    // activity_match의 id는 모르는 상황(신청 취소 API)을 위한 역조회 쿼리.
    // releasedAt IS NULL 조건이 핵심: 한 게시글에 대해 "현재 유효한(아직 안 끝난)" 참여 연결은
    // 항상 하나뿐이라는 DB 제약(uq_participant_active_request)을 그대로 활용해, 그 하나의 행에서
    // activityMatchId만 꺼내온다. 매칭이 이미 끝나서 released_at이 채워졌다면 조회되지 않는다.
    @Query("""
    SELECT p.activityMatchId FROM MatchParticipant p
    WHERE p.matchRequestId = :requestId AND p.releasedAt IS NULL
    """)
    Optional<Long> findActiveActivityMatchIdByMatchRequestId(@Param("requestId") Long requestId);

    // 회원 탈퇴 시 탈퇴자가 지금 참가 중인 activity_match를 찾는 쿼리. 위 쿼리와 같은 releasedAt IS NULL
    // 패턴을 사용자 기준으로 적용한다. 한 사용자는 동시에 하나의 활성 참여만 가질 수 있다는 DB 제약
    // (uq_participant_active_user)이 있어 결과는 0건 또는 1건이다. 활성 참여는 PROPOSED(신청 대기)
    // 또는 CONFIRMED(확정) 상태의 매칭에만 남는다(그 외 상태로 끝나면 release()로 해제됨).
    @Query("""
    SELECT p.activityMatchId FROM MatchParticipant p
    WHERE p.userId = :userId AND p.releasedAt IS NULL
    """)
    Optional<Long> findActiveActivityMatchIdByUserId(@Param("userId") Long userId);

    // 매칭 상세 응답에 표시할 상대 사용자 닉네임 조회
    // matching이 user 도메인을 직접 참조하지 않도록 네이티브 쿼리로 users를 조인
    // 탈퇴해도 users 행은 남고 nickname만 NULL이 되므로 그대로 null 반환
    @Query(value = """
            SELECT u.nickname
            FROM match_participant p
            JOIN users u ON u.id = p.user_id
            WHERE p.activity_match_id = :activityMatchId
              AND p.user_id = :userId
            """, nativeQuery = true)
    String findNicknameByActivityMatchIdAndUserId(@Param("activityMatchId") Long activityMatchId,
                                                  @Param("userId") Long userId);
}
