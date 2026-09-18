package com.team4.hanbbyeom.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

// 현재 시각을 사용하는 Service가 운영과 테스트에서 서로 다른 시계를 주입받을 수 있도록 설정
@Configuration
public class TimeConfig {

    // 시각의 저장·비교는 UTC 기준으로 통일, 각 Service가 자신의 기준 타임존으로 변환해서 처리
    // → UTC 기준이므로 LocalDate.now(clock) 말고 OffsetDateTime.now(clock)로 비교해서 사용할 것
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
