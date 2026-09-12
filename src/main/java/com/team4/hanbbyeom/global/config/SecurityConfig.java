package com.team4.hanbbyeom.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration // Spring에게 설정 클래스라고 알려주는 어노테이션
public class SecurityConfig {

    // PasswordEncoder Bean 등록 (비밀번호 암호화 및 검증에 사용)
    // PasswordEncoder: (Spring Security 제공) 비밀번호 암호화/검증 인터페이스
    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCryptPasswordEncoder: (Spring Security 제공) PasswordEncoder를 BCrypt 방식으로 구현한 클래스
        // 비밀번호를 BCrypt 방식으로 해시하고, 로그인할 때 입력 비밀번호가 저장된 해시와 맞는지 비교해주는 객체
        return new BCryptPasswordEncoder();
    }
}
