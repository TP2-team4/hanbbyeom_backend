package com.team4.hanbbyeom.run.controller;

import com.team4.hanbbyeom.run.dto.RunCourseResponse;
import com.team4.hanbbyeom.run.service.RunCourseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 러닝 코스 관련 HTTP 요청을 처리하는 컨트롤러 클래스
// 프론트엔드(클라이언트)로부터 들어오는 API 요청을 받고, 서비스 계층에 처리를 위임한 뒤 결과를 반환함


// Swagger UI 문서 상에서 이 특정 엔드포인트(API)의 간략한 기능 요약 제목 표시
@Tag(name = "Run - Course", description = "러닝 코스 조회 API")

@RestController
@RequestMapping("/api/run/courses")
// final 키워드가 붙은 필드(runCourseService)를 인자로 받는 생성자를 Lombok이 자동 생성 -> Spring의 의존성 주입(DI)에 사용
@RequiredArgsConstructor
public class RunCourseController {

    private final RunCourseService runCourseService;

    // 러닝 코스 전체 목록 조회 API
    // HTTP Method: GET
    // URL: /api/run/courses
    // Response: 200 OK + 러닝 코스 목록(JSON 배열)

    @Operation(summary = "러닝 코스 목록 조회")
    @GetMapping
    public List<RunCourseResponse> getCourses() {
        // 서비스 계층의 코스 목록 조회 메서드를 호출하여 DTO 리스트 결과를 컨트롤러 응답 값으로 즉시 반환
        return runCourseService.getCourses();
    }
}
