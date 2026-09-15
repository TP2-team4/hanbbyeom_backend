package com.team4.hanbbyeom.run.service;

import com.team4.hanbbyeom.run.dto.RunCourseResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 러닝 코스 서비스 단위 통합 테스트

// 서비스 계층의 코스 목록 조회 로직이 DB 데이터(시딩 데이터)를 올바르게 가져와서
// DTO(RunCourseResponse) 형태로 정상 반환하는지 검증합니다.

@SpringBootTest // Spring Boot의 모든 컨텍스트 및 빈(Bean)을 로드하여 실제 환경과 유사하게 테스트 수행
@Transactional // 테스트 시작 전 트랜잭션을 걸고, 테스트 완료 후 데이터베이스 작업을 자동으로 롤백하여 DB 청결 유지
public class RunCourseServiceTest {
    // 테스트 대상이 되는 러닝 코스 서비스 객체 주입
    @Autowired
    private RunCourseService runCourseService;

    // 서비스의 코스 목록 조회 기능을 검증하는 테스트 메서드
    @Test
    void 코스_목록을_조회하면_5개가_반환된다() {
        // When: 서비스의 getCourses() 메서드를 호출하여 코스 목록을 조회
        List<RunCourseResponse> courses = runCourseService.getCourses();
        // Then: 반환된 결과 검증
        // DB 초기 데이터(Flyway V5 마이그레이션 등)로 시딩된 코스 개수가 정확히 5개인지 검증
        assertThat(courses).hasSize(5);
        // 조회된 5개 DTO 객체에서 name 필드만 추출하여, 예상한 5개 코스명이 모두 포함되어 있는지 확인
        assertThat(courses)
                .extracting(RunCourseResponse::name)
                .contains("뚝섬 한강공원", "여의도 한강공원", "잠실 한강공원", "반포 한강공원", "안양천");
    }
}