package com.team4.hanbbyeom.matching.scheduler;

import com.team4.hanbbyeom.matching.service.MatchDecisionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// 스케줄러를 끄는 스위치(app.scheduling.enabled)가 의도대로 동작하는지 검증한다.
// 운영(설정이 없을 때)에서는 켜져 있어야 한다: 꺼진 채 배포되면 응답 기한 만료와 활동 종료 처리가 아예 돌지 않는다.
// 최소한의 컨텍스트로 조건 자체만 검증하므로 전체 앱을 띄우지 않는다.
class MatchExpireSchedulerToggleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MatchDecisionService.class, () -> mock(MatchDecisionService.class))
            .withUserConfiguration(MatchExpireScheduler.class);

    @Test
    @DisplayName("설정이 없으면 스케줄러가 켜진다 (운영 기본 동작)")
    void 설정이_없으면_켜진다() {
        runner.run(context -> assertThat(context).hasSingleBean(MatchExpireScheduler.class));
    }

    @Test
    @DisplayName("app.scheduling.enabled=true면 켜진다")
    void true면_켜진다() {
        runner.withPropertyValues("app.scheduling.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(MatchExpireScheduler.class));
    }

    @Test
    @DisplayName("app.scheduling.enabled=false면 꺼진다")
    void false면_꺼진다() {
        runner.withPropertyValues("app.scheduling.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(MatchExpireScheduler.class));
    }
}
