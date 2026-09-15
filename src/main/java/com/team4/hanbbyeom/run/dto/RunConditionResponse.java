package com.team4.hanbbyeom.run.dto;

import com.team4.hanbbyeom.run.domain.RunMatchCondition;
// 러닝 조건 상세 응답 DTO
// API 클라이언트(프론트엔드)에게 조회 결과로 전달할 러닝 세부 조건 정보 데이터 객체입니다.
// 코스 엔티티(RunningCourse)와의 연관관계를 통해 코스 ID와 코스 이름(courseName)을 함께 포함하여 응답합니다.
public record RunConditionResponse(
        Long matchRequestId, // 매칭 요청 ID (RunMatchCondition의 PK이자 MatchRequest의 PK)
        Long courseId, // 코스 ID
        String courseName, // 선택한 러닝 코스 이름
        String meetingPoint, // 사용자 입력 모임 장소
        Integer distanceMinMeters, // 최소 거리(미터)
        Integer distanceMaxMeters, // 최대 거리(미터)
        Integer paceMinSec, // 최소 페이스(초)
        Integer paceMaxSec // 최대 페이스(초)
) {
    // RunMatchCondition 엔티티 객체를 RunConditionResponse DTO 객체로 변환하는 정적 팩토리 메서드
    public static RunConditionResponse from(RunMatchCondition condition) {
        return new RunConditionResponse(
                condition.getMatchRequestId(),
                condition.getRunningCourse().getId(), // N:1 연관관계인 RunningCourse에서 코스 ID 추출
                condition.getRunningCourse().getName(), // N:1 연관관계인 RunningCourse에서 코스 이름 추출
                condition.getMeetingPoint(),
                condition.getDistanceMinMeters(),
                condition.getDistanceMaxMeters(),
                condition.getPaceMinSec(),
                condition.getPaceMaxSec()
        );
    }
}