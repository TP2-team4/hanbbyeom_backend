package com.team4.hanbbyeom.user.dto;

import com.team4.hanbbyeom.user.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;

// 닉네임 변경 후 실제로 저장된 닉네임을 반환하는 응답 데이터
@Schema(description = "닉네임 변경 응답")
public record UserNicknameResponse(

        @Schema(description = "변경되어 저장된 닉네임", example = "한뼘러너")
        String nickname
) {

    // 변경된 User Entity에서 외부에 공개할 값만 응답 DTO로 변환
    public static UserNicknameResponse from(User user) {
        return new UserNicknameResponse(user.getNickname());
    }
}
