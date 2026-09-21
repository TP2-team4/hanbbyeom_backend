package com.team4.hanbbyeom.matching.dto;

import com.team4.hanbbyeom.matching.domain.TalkLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

// 모집글 등록 요청. 컨트롤러의 @Valid가 필수값 누락을 400으로 걸러낸다. 검증이 없으면 누락이 서비스 안에서
// NullPointerException이나 DB NOT NULL 위반으로 터져 500이 나가고, 프론트는 자기 요청 오류를 서버 장애로 오인한다.
// 코스·만나는 곳·거리·페이스 6개는 RunConditionService.create()로 그대로 넘어가는데, 그쪽 요청 DTO는 서비스가 직접 만들어
// 호출해서 어노테이션이 실행되지 않는다. 그래서 여기서 RunConditionCreateRequest와 같은 기준으로 검증한다.
// 거리·페이스의 범위(1~20km, 5'00"~7'30", min <= max)는 RunConditionService가 계속 검증한다.
// meetingPoint의 상한 255는 run_match_condition.meeting_point와 activity_match.location(확정 시 복사)이 모두 VARCHAR(255)라서다.
// 검증이 없으면 256자부터 INSERT가 "value too long"으로 실패해 500이 된다. 상한은 바이트가 아니라 문자 수다(Postgres VARCHAR(n) 기준).
// message는 응답에 그대로 나가는데 필드 이름이 함께 나가지 않아서, 어느 값이 문제인지 알 수 있는 문구로 쓴다.
public record MatchRequestCreateRequest(
        @NotNull(message = "코스를 선택해주세요.")
        Long courseId,             // running_course.id
        @NotBlank(message = "만나는 곳을 입력해주세요.")
        @Size(max = 255, message = "만나는 곳은 255자 이하로 입력해주세요.")
        String meetingPoint,
        @NotNull(message = "최소 거리를 입력해주세요.")
        Integer distanceMinMeters,
        @NotNull(message = "최대 거리를 입력해주세요.")
        Integer distanceMaxMeters,
        @NotNull(message = "최소 페이스를 입력해주세요.")
        Integer paceMinSec,
        @NotNull(message = "최대 페이스를 입력해주세요.")
        Integer paceMaxSec,
        @NotNull(message = "활동 시작 시각을 입력해주세요.")
        OffsetDateTime scheduledAt,
        // JSON에서는 "SILENT" | "LIGHT_CHAT" 문자열 그대로다. 그 외 값은 JSON 변환 단계에서 걸러져 400이 된다
        @NotNull(message = "대화 수준을 선택해주세요.")
        TalkLevel talkLevel
) {}
