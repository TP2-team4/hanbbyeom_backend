package com.team4.hanbbyeom.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 주기 실행(@Scheduled)을 켜는 설정. @EnableScheduling이 등록하는 ScheduledAnnotationBeanPostProcessor가 있어야
// @Scheduled 메서드가 실제로 돈다(없으면 애너테이션은 그냥 무시된다).
//
// app.scheduling.enabled=false로 스케줄링 전체를 끌 수 있다(기본값은 켜짐이라 운영 동작은 그대로다). 테스트에서는 끈다
// (src/test/resources/application.properties).
//
// 스위치를 개별 스케줄러 클래스가 아니라 여기에 두는 이유: @Scheduled를 가진 클래스가 몇 개로 늘어나도 테스트에서는 전부 돌지
// 않는다. 새 스케줄러를 추가하는 사람이 스위치를 따로 붙여야 한다는 걸 기억할 필요가 없다. 스케줄러 클래스는 평범한 빈으로 남아
// 테스트에서 메서드를 직접 호출할 수 있다.
//
// 테스트에서 꺼야 하는 이유: 스케줄러 스레드가 컨텍스트가 뜨자마자(fixedDelay는 초기 지연이 없다) 서비스를 실행한다.
//  - 서비스가 Clock 빈을 쓰므로(#94) Clock을 Mockito 목으로 바꾼 테스트에서는 스케줄러 스레드와 테스트 스레드가 같은 목을 동시에
//    건드려 스터빙이 ClassCastException으로 깨지는 경합이 생긴다. ChatMessageIntegrationTest가 이렇게 간헐 실패했고 지금은 테스트가
//    MutableClock을 쓰도록 고쳐져 있지만, 그 규칙은 사람이 기억해야 한다.
//  - 캐시된 각 테스트 컨텍스트마다 스케줄러가 같은 DB를 주기적으로 건드려, 커밋된 픽스처를 쓰는 동시성 테스트와도 간섭할 수 있다.
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
