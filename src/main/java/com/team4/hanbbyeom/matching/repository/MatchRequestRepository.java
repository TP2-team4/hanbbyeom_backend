package com.team4.hanbbyeom.matching.repository;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchRequestRepository extends JpaRepository<MatchRequest, Long> {
}
