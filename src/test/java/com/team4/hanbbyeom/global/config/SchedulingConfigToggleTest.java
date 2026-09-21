package com.team4.hanbbyeom.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 스케줄링 스위치(app.scheduling.enabled)가 @Scheduled를 가진 빈이 몇 개든 한 번에 켜고 끄는지 검증한다.
// 운영(설정이 없을 때)에서는 켜져 있어야 한다: 꺼진 채 배포되면 응답 기한 만료·활동 종료 처리가 아예 돌지 않는다.
// 최소한의 컨텍스트로 조건 자체만 검증하므로 전체 앱을 띄우지 않는다.
class SchedulingConfigToggleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class);

    // @Scheduled를 가진 서로 다른 두 클래스. 스케줄러가 몇 개로 늘어나도 스위치 하나로 함께 켜지고 꺼지는지 보기 위해 둘을 쓴다.
    public static class FirstTicker {
        final AtomicInteger ticks = new AtomicInteger();

        @Scheduled(fixedRate = 20)
        public void tick() {
            ticks.incrementAndGet();
        }
    }

    public static class SecondTicker {
        final AtomicInteger ticks = new AtomicInteger();

        @Scheduled(fixedRate = 20)
        public void tick() {
            ticks.incrementAndGet();
        }
    }

    @Test
    @DisplayName("설정이 없으면 스케줄링이 켜진다 (운영 기본 동작)")
    void 설정이_없으면_켜진다() {
        runner.run(context -> assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    @DisplayName("app.scheduling.enabled=true면 켜진다")
    void true면_켜진다() {
        runner.withPropertyValues("app.scheduling.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    @DisplayName("app.scheduling.enabled=false면 꺼진다")
    void false면_꺼진다() {
        runner.withPropertyValues("app.scheduling.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                    assertThat(context).doesNotHaveBean(SchedulingConfig.class);
                });
    }

    @Test
    @DisplayName("켜져 있으면 @Scheduled 메서드가 실제로 실행된다 (이 테스트의 '꺼짐' 검증이 우연히 통과하지 않게 하는 대조군)")
    void 켜져_있으면_Scheduled_메서드가_실행된다() {
        runner.withBean(FirstTicker.class).withBean(SecondTicker.class)
                .run(context -> {
                    assertThat(waitUntilPositive(context.getBean(FirstTicker.class).ticks)).isTrue();
                    assertThat(waitUntilPositive(context.getBean(SecondTicker.class).ticks)).isTrue();
                });
    }

    @Test
    @DisplayName("꺼져 있으면 @Scheduled를 가진 빈이 몇 개든 어느 것도 실행되지 않는다")
    void 꺼져_있으면_어떤_Scheduled_메서드도_실행되지_않는다() {
        runner.withPropertyValues("app.scheduling.enabled=false")
                .withBean(FirstTicker.class).withBean(SecondTicker.class)
                .run(context -> {
                    Thread.sleep(300); // 켜져 있다면 20ms 주기라 이 사이에 여러 번 실행된다
                    assertThat(context.getBean(FirstTicker.class).ticks.get()).isZero();
                    assertThat(context.getBean(SecondTicker.class).ticks.get()).isZero();
                });
    }

    private boolean waitUntilPositive(AtomicInteger counter) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (counter.get() > 0) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
