package com.team4.hanbbyeom.matching.repository;

import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.ActivityMatchStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

// activity_match(신청~확정 상태의 매칭 건) 전용 리포지토리.
// 커스텀 쿼리가 하나도 없는 이유: MatchApplyService가 findById()/save()라는 JpaRepository
// 기본 메서드만으로 신청 생성(apply)·조회·취소(cancelApplication) 로직을 전부 처리할 수 있어서다.
public interface ActivityMatchRepository extends JpaRepository<ActivityMatch, Long> {

    // 스케줄러(MatchExpireScheduler)가 자동 만료 대상을 찾을 때 쓴다.
    // status=PROPOSED(아직 호스트 응답 대기 중)이면서 decisionExpiresAt이 now보다 과거인 건들.
    List<ActivityMatch> findByStatusAndDecisionExpiresAtBefore(ActivityMatchStatus status, OffsetDateTime now);

    // 스케줄러가 자연 종료 대상을 찾을 때 쓴다.
    // status=CONFIRMED(확정됨)이면서 scheduledEndAt이 now보다 과거인 건들 — 활동 시간이
    // 이미 지났는데 아직 ENDED로 전이 안 된 매칭들이다.
    List<ActivityMatch> findByStatusAndScheduledEndAtBefore(ActivityMatchStatus status, OffsetDateTime now);
}