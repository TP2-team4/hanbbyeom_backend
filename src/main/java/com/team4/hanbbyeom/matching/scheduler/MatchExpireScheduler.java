package com.team4.hanbbyeom.matching.scheduler;

import com.team4.hanbbyeom.matching.service.MatchDecisionService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 응답 기한(decision_expires_at)이 지난 PROPOSED 매칭을 주기적으로 자동 만료 처리한다.
// 로직 자체는 MatchDecisionService.expireOverdue()에 있고, 이 클래스는 "언제 실행할지"만 담당한다.
@Component
public class MatchExpireScheduler {

    private final MatchDecisionService matchDecisionService;

    public MatchExpireScheduler(MatchDecisionService matchDecisionService) {
        this.matchDecisionService = matchDecisionService;
    }

    // 1분마다 실행. fixedDelay라 "이전 실행이 끝난 시점 기준" 1분 뒤에 다시 도니까,
    // expireOverdue() 처리가 오래 걸려도 중첩 실행되지 않는다.
    @Scheduled(fixedDelay = 60_000)
    public void expireOverdueMatches() {
        matchDecisionService.expireOverdue();
    }
}