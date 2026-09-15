package com.team4.hanbbyeom.auth.dto;

// 로그인 성공 후 클라이언트에 반환하는 응답 데이터

// 클라이언트는 이후 요청에 Authorization: Bearer {accessToken} 헤더를 붙이고,
// 401을 받으면 재발급 시도 없이 로그인 화면으로 이동
public record LoginResponse(
        //  Access Token만 반환 (Refresh Token은 #4로 보류)
        String accessToken
) {
}
