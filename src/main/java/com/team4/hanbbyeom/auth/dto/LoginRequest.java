package com.team4.hanbbyeom.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 로그인 요청 시 클라이언트가 보내는 요청 데이터: 이메일, 비밀번호
public record LoginRequest(

        @NotBlank
        @Email // 이메일 형식 검증 (존재여부 검증X)
        @Size(max = 255)
        String email,

        // 회원가입과 달리 비밀번호 길이 제한(@Size)을 두지 않음
        // 비밀번호 정책이 바뀌면 기존 사용자가 로그인하지 못하게 되고,
        // 형식만으로 응답이 걸러지면 비밀번호 규칙을 외부에 알려주는 것이 되기 때문
        @NotBlank
        String password
) {
}
