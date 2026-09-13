package com.team4.hanbbyeom.auth.domain;

// 이메일 인증 목적 구분 (DB의 chk_email_verifications_purpose 제약조건과 대응)
public enum VerificationPurpose {
    SIGNUP,
    PASSWORD_RESET
}
