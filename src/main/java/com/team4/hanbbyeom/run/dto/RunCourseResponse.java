package com.team4.hanbbyeom.run.dto;

import com.team4.hanbbyeom.run.domain.RunningCourse;

/**
 * 러닝 코스 조회 응답 DTO
 * - API 클라이언트(프론트엔드)에게 전달할 러닝 코스의 정보를 담는 객체
 * - Record 타입을 사용하여 불변성을 보장하고 기본 메서드(Getter, equals, hashCode 등)를 자동 생성
 */
public record RunCourseResponse(
    Long id, // 러닝 코스의 고유 식별자
    String name, // 러닝 코스의 이름
    String routeDescription // 러닝 코스의 경로 설명
) {
    // RunningCourse 엔티티 객체를 DTO 객체로 변환하는 정적 팩토리 메서드
    public static RunCourseResponse from(RunningCourse runningCourse) {
        return new RunCourseResponse(
            runningCourse.getId(),
            runningCourse.getName(),
            runningCourse.getRouteDescription()
        );
    }
}