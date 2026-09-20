package com.team4.hanbbyeom.matching.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 애플리케이션 테스트 컨텍스트에서 주기 실행 스케줄러가 꺼져 있는지 확인한다.
//
// 켜져 있으면 스케줄러 스레드가 컨텍스트 시작 직후 서비스를 실행하는데, 서비스가 쓰는 Clock 빈을 목으로 바꾼 테스트
// (ChatMessageIntegrationTest)에서는 그 스레드와 테스트 스레드가 같은 목을 동시에 호출해 스터빙이
// ClassCastException으로 간헐적으로 깨진다(#94 이후 CI에서 관측). src/test/resources/application.properties가 사라지거나
// 바뀌면 이 테스트가 실패해, 그 경합이 조용히 되살아나는 것을 막는다.
@SpringBootTest
class SchedulerDisabledInTestContextTest {

    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("애플리케이션 테스트 컨텍스트에는 스케줄러가 없다")
    void 스케줄러가_없다() {
        assertThat(context.getBeansOfType(MatchExpireScheduler.class)).isEmpty();
    }
}
