package com.team4.hanbbyeom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// @ConfigurationPropertiesScan:
// 프로젝트 전체에서 @ConfigurationProperties가 붙은 클래스를 찾아서 자동으로 Bean 등록
// → JwtProperties 등 각 설정 클래스에는 @ConfigurationProperties만 붙이면 되고,
//	 @Component는 따로 붙일 필요 없음 (붙이면 오히려 생성자 자동 주입과 충돌해서 오류 발생)
@ConfigurationPropertiesScan
// @EnableScheduling: @Scheduled 애노테이션이 붙은 메서드를 Spring이 실제로 주기 실행하게
// 활성화한다. 이거 없으면 MatchExpireScheduler의 @Scheduled는 그냥 무시되고 절대 안 돈다.
@EnableScheduling
public class HanbbyeomApplication {

	public static void main(String[] args) {
		SpringApplication.run(HanbbyeomApplication.class, args);
	}

}