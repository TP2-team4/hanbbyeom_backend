package com.team4.hanbbyeom.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

// Spring에게 설정 클래스라고 알려주는 어노테이션
@Configuration

// Spring Data JPA의 Auditing 기능을 활성화해서 Entity의 생성 시각, 수정 시각 같은 값을 자동으로 넣을 수 있게 함
@EnableJpaAuditing
public class JpaConfig {
}
