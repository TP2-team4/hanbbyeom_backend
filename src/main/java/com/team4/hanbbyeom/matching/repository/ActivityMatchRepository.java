package com.team4.hanbbyeom.matching.repository;

import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityMatchRepository extends JpaRepository<ActivityMatch, Long> {
}
