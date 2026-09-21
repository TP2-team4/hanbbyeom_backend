package com.team4.hanbbyeom.run.dto;

import com.team4.hanbbyeom.global.validation.NoNulCharacter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 러닝 조건 등록 요청 DTO
// - API 클라이언트(프론트엔드)로부터 전달받은 러닝 조건 등록 요청 데이터를 담는 객체
// - (@NotNull, @NotBlank)을 통해 필수 입력값의 누락 여부를 1차적으로 검증합니다.
public record RunConditionCreateRequest(

    // 연결할 매칭 요청서 ID (필수)
    // MatchRequest와의 1:1 식별 관계(@MapsId) 형성을 위해 사용됩니다.
    @NotNull
    Long matchRequestId,

    // 선택한 러닝 코스 ID (필수)
    // RunningCourse 마스터 테이블과의 N:1 연관관계 매핑에 사용됩니다.
    @NotNull
    Long courseId,

    // 사용자가 직접 입력한 상세 집결 장소 (필수)
    // null, 빈 문자열(""), 공백(" ") 모두 허용하지 않습니다.
    // 255자 이하(문자 수 기준): meeting_point 컬럼이 VARCHAR(255)라 넘으면 INSERT/UPDATE가 실패해 500이 된다.
    // NUL 문자(0x00)는 PostgreSQL이 저장하지 못해 DB 단계에서 500이 나므로 함께 거절한다.
    @NotBlank
    @Size(max = 255, message = "만나는 곳은 255자 이하로 입력해주세요.")
    @NoNulCharacter(message = "만나는 곳에 사용할 수 없는 문자가 포함되어 있어요.")
    String meetingPoint,

    // 희망 러닝 최소거리 (미터 단위, 필수)
    // 예: 1000 (1km)
    @NotNull
    Integer distanceMinMeters,

    // 희망 러닝 최대거리 (미터 단위, 필수)
    // 예: 20000 (20km)
    @NotNull
    Integer distanceMaxMeters,

    // 희망 러닝 최소 페이스 (초 단위, 필수)
    // 예: 300 (5'00"km)
    @NotNull
    Integer paceMinSec,

    // 희망 러닝 최대 페이스 (초 단위, 필수)
    // 예: 450 (7'30"km)
    @NotNull
    Integer paceMaxSec
) {
}