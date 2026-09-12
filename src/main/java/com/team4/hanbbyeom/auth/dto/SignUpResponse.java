package com.team4.hanbbyeom.auth.dto;

import com.team4.hanbbyeom.user.domain.User;

// 회원가입 완료 후 클라이언트에 반환하는 응답 데이터 (passwordHash 제외)
public record SignUpResponse(
        Long id,
        String email,
        String nickname
) {

    // from() 정의: User Entity를 받아서 SignUpResponse DTO로 변환하는 정적 메서드
    public static SignUpResponse from(User user) {
        return new SignUpResponse(
                user.getId(), // DB에 저장되면서 자동 생성된 User Entity의 id 값
                user.getEmail(),
                user.getNickname()
        );
    }
}
