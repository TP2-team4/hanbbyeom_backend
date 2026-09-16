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
}
