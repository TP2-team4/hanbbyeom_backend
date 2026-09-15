package com.team4.hanbbyeom.matching.repository;

import com.team4.hanbbyeom.matching.domain.MatchParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MatchParticipantRepository extends JpaRepository<MatchParticipant, Long> {
    List<MatchParticipant> findByActivityMatchId(Long activityMatchId);

    @Query("""
    SELECT p.activityMatchId FROM MatchParticipant p
    WHERE p.matchRequestId = :requestId AND p.releasedAt IS NULL
    """)
    Optional<Long> findActiveActivityMatchIdByMatchRequestId(@Param("requestId") Long requestId);
}
