package com.team4.hanbbyeom.matching.scheduler;

import com.team4.hanbbyeom.matching.service.MatchDecisionService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

// 스케줄러가 각 주기 작업을 해당 서비스 메서드에 연결하는지 검증한다. "언제 실행할지"만 담당하는 클래스라
// 실제 로직은 MatchDecisionService 테스트가 검증한다.
class MatchExpireSchedulerTest {

    private final MatchDecisionService service = mock(MatchDecisionService.class);
    private final MatchExpireScheduler scheduler = new MatchExpireScheduler(service);

    @Test
    void 모집_기한이_지난_게시글_만료_작업이_서비스에_연결된다() {
        scheduler.expireOverdueRequests();

        verify(service).expireOverdueRequests();
    }

    @Test
    void 기존_두_작업도_각각_연결된다() {
        scheduler.expireOverdueMatches();
        scheduler.endOverdueActivities();

        verify(service).expireOverdue();
        verify(service).endOverdueActivities();
    }
}
