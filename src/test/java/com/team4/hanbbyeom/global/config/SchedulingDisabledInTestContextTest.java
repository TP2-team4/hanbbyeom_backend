package com.team4.hanbbyeom.global.config;

import com.team4.hanbbyeom.matching.scheduler.MatchExpireScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 애플리케이션 테스트 컨텍스트에서 주기 실행(@Scheduled)이 꺼져 있는지 확인한다.
//
// 켜져 있으면 스케줄러 스레드가 컨텍스트 시작 직후 서비스를 실행하는데, 서비스가 쓰는 Clock 빈을 목으로 바꾼 테스트에서는 그 스레드와
// 테스트 스레드가 같은 목을 동시에 호출해 스터빙이 ClassCastException으로 간헐적으로 깨진다(#94 이후 CI에서 관측). 또 캐시된 각
// 컨텍스트의 스케줄러가 커밋된 픽스처를 쓰는 테스트와 같은 DB에서 간섭할 수 있다.
// 특정 스케줄러 클래스가 아니라 @Scheduled를 처리하는 후처리기가 없는지를 확인하므로, 나중에 스케줄러가 추가돼도 이 테스트는 그대로
// 유효하다. src/test/resources/application.properties가 사라지거나 바뀌면, 또는 누가 @EnableScheduling을 다른 곳에 다시 붙이면 실패한다.
@SpringBootTest
class SchedulingDisabledInTestContextTest {

    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("애플리케이션 테스트 컨텍스트에는 @Scheduled를 처리하는 후처리기가 없다")
    void 스케줄링이_꺼져_있다() {
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(context.getBeansOfType(SchedulingConfig.class)).isEmpty();
    }

    @Test
    @DisplayName("스케줄러 클래스는 평범한 빈으로 남아 있어 테스트에서 메서드를 직접 호출할 수 있다")
    void 스케줄러_빈은_남아_있다() {
        assertThat(context.getBeansOfType(MatchExpireScheduler.class)).hasSize(1);
    }
}
