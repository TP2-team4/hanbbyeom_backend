package com.team4.hanbbyeom.global.config;

import com.team4.hanbbyeom.matching.scheduler.MatchExpireScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

// 테스트 애플리케이션 컨텍스트에서 @Scheduled 자동 실행이 비활성화되어 있는지 확인한다.
//
// 테스트에서 스케줄링이 활성화되면 컨텍스트 시작 후 별도의 스케줄러 스레드가 실행될 수 있다.
// 이 스레드가 테스트 스레드와 같은 Clock Mock을 동시에 사용하면
// stubbing 충돌로 ClassCastException이 간헐적으로 발생할 수 있다(#94 이후 CI에서 관측).
//
// 또한 스케줄러가 테스트 DB를 주기적으로 변경하면
// 커밋된 픽스처를 사용하는 동시성 테스트와 간섭할 수 있다.
//
// 특정 스케줄러 클래스의 존재 여부가 아니라
// @Scheduled 메서드를 실제로 실행하는 후처리기가 등록되지 않았는지를 검증한다.
// 따라서 이후 새로운 스케줄러가 추가되어도 테스트 환경에서 자동 실행되지 않는지를 계속 확인할 수 있다.
//
// 테스트용 스케줄링 비활성화 설정이 제거되거나,
// 다른 설정에서 @EnableScheduling이 다시 활성화되면 이 테스트가 실패한다.
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
