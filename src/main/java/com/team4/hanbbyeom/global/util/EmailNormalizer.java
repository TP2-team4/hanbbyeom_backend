package com.team4.hanbbyeom.global.util;

import java.util.Locale;

// 이메일 정규화 규칙을 한곳에서 관리하는 유틸리티
// 회원가입·이메일 인증·로그인에서 중복되는 코드 정리
// users 테이블의 CHECK 제약도 같은 형식의 이메일만 허용
public final class EmailNormalizer {

    // 객체를 만들 필요 없이 메서드만 쓰는 유틸리티라 생성자를 막아둠
    private EmailNormalizer() {
    }

    // 이메일 앞뒤 공백 제거 및 소문자 변환 (users 테이블과 동일한 정규화 규칙)
    public static String normalize(String email) {
        return email
                .trim() // 앞뒤 공백 제거
                .toLowerCase(Locale.ROOT); // 소문자로 변환
    }
}
