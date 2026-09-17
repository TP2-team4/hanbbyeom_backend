package com.team4.hanbbyeom.matching.repository;

import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import org.springframework.data.jpa.repository.JpaRepository;

// activity_match(신청~확정 상태의 매칭 건) 전용 리포지토리.
// 커스텀 쿼리가 하나도 없는 이유: MatchApplyService가 findById()/save()라는 JpaRepository
// 기본 메서드만으로 신청 생성(apply)·조회·취소(cancelApplication) 로직을 전부 처리할 수 있어서다.
public interface ActivityMatchRepository extends JpaRepository<ActivityMatch, Long> {
}
