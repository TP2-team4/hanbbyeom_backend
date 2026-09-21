package com.team4.hanbbyeom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
// @ConfigurationPropertiesScan:
// 프로젝트 전체에서 @ConfigurationProperties가 붙은 클래스를 찾아서 자동으로 Bean 등록
// → JwtProperties 등 각 설정 클래스에는 @ConfigurationProperties만 붙이면 되고,
//	 @Component는 따로 붙일 필요 없음 (붙이면 오히려 생성자 자동 주입과 충돌해서 오류 발생)
@ConfigurationPropertiesScan
public class HanbbyeomApplication {

	public static void main(String[] args) {
		SpringApplication.run(HanbbyeomApplication.class, args);
	}

}