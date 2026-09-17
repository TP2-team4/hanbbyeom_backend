package com.team4.hanbbyeom.matching.scheduler;

import com.team4.hanbbyeom.matching.service.MatchDecisionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 매칭 관련 주기적 정리 작업 2가지를 담당한다.
// 로직 자체는 MatchDecisionService에 있고, 이 클래스는 "언제 실행할지"만 담당한다.
@Component
public class MatchExpireScheduler {

    private final MatchDecisionService matchDecisionService;

    public MatchExpireScheduler(MatchDecisionService matchDecisionService) {
        this.matchDecisionService = matchDecisionService;
    }

    // 응답 기한(decision_expires_at)이 지난 PROPOSED 매칭을 자동 만료(EXPIRED) 처리.
    // 1분마다 실행. fixedDelay라 "이전 실행이 끝난 시점 기준" 1분 뒤에 다시 도니까,
    // 처리가 오래 걸려도 중첩 실행되지 않는다.
    @Scheduled(fixedDelay = 60_000)
    public void expireOverdueMatches() {
        matchDecisionService.expireOverdue();
    }

    // 예정 종료 시각(scheduled_end_at)이 지난 CONFIRMED 매칭을 자연 종료(ENDED) 처리하고
    // 참가자를 release() — 이게 없으면 확정된 매칭의 두 사람은 영원히 다른 매칭에 못 들어간다
    // (팀원 리뷰로 발견된 문제). expireOverdueMatches()와 별개 작업이라 메서드를 분리했다.
    @Scheduled(fixedDelay = 60_000)
    public void endOverdueActivities() {
        matchDecisionService.endOverdueActivities();
    }
}