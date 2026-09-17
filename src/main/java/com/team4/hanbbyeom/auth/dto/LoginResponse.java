package com.team4.hanbbyeom.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// 로그인 성공 후 클라이언트에 반환하는 응답 데이터

// 클라이언트는 이후 요청에 Authorization: Bearer {accessToken} 헤더를 붙이고,
// 401을 받으면 재발급 시도 없이 로그인 화면으로 이동
@Schema(description = "로그인 성공 응답. 발급된 토큰을 이후 요청의 Authorization 헤더에 담아 보냅니다.")
public record LoginResponse(

        //  Access Token만 반환 (Refresh Token은 #4로 보류)
        @Schema(description = "보호 API 호출에 사용할 Access Token. "
                + "Authorization: Bearer {accessToken} 형식으로 전송하며, "
                + "만료되면 401이 반환되므로 다시 로그인해야 합니다.",
                example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.hrEYQ8xkFQ0hJmZ3RDxAR8kZ1sZrJqTqMYQlTQ0XQhA")
        String accessToken
) {
}
