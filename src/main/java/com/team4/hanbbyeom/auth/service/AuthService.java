package com.team4.hanbbyeom.auth.service;

import com.team4.hanbbyeom.auth.domain.VerificationPurpose;
import com.team4.hanbbyeom.auth.dto.SignUpRequest;
import com.team4.hanbbyeom.auth.dto.SignUpResponse;
import com.team4.hanbbyeom.user.domain.User;
import com.team4.hanbbyeom.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;

// 회원가입, 로그인 등 인증 관련 비즈니스 로직을 담당하는 Service
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository; // 사용자 계정을 DB에 저장하고 조회하기 위해 사용
    private final PasswordEncoder passwordEncoder; // 비밀번호를 BCrypt로 암호화/검증하기 위해 사용
    private final EmailVerificationService emailVerificationService; // 이메일 인증 완료 여부 확인을 위해 사용

    // 회원가입: 이메일 중복 확인, 이메일 인증 완료 확인, 비밀번호 암호화 후 사용자 저장
    @Transactional
    // SignUpRequest: 이메일, 비밀번호, 닉네임을 하나로 묶어놓은 객체
    public SignUpResponse signUp(SignUpRequest request) {
        String email = normalize(request.email()); // 이메일 정규화

        // 탈퇴하지 않은 동일 이메일 사용자가 이미 있는지 확인 (중복차단)
        if (userRepository.existsByEmailAndDeletedAtIsNull(email)) {
            throw new IllegalStateException("이미 가입된 이메일입니다.");
        }

        // 이 이메일이 회원가입(SIGNUP) 목적으로 인증 완료된 실제 시각 조회 (미인증 시 예외)
        Instant emailVerifiedAt = emailVerificationService.getVerifiedAt(email, VerificationPurpose.SIGNUP);

        // 비밀번호 원문은 저장하지 않고 BCrypt 해시로 변환
        String passwordHash = passwordEncoder.encode(request.password());

        User user = new User(
                email,
                passwordHash,
                request.nickname(),
                emailVerifiedAt // 실제 인증 성공 시각을 그대로 기록
        );

        // save(): (JPA 제공) DB에 INSERT하고, 모든 필드가 채워진 User를 반환받음
        User savedUser = userRepository.save(user);

        // User에서 비밀번호만 빼고 반환함
        return SignUpResponse // passwordHash가 빠진 응답 전용 DTO
                .from(savedUser); // (SignUpResponse에서 만듦) savedUser를 SignUpResponse로 변환
    }

    // 이메일 앞뒤 공백 제거 및 소문자 변환 (users 테이블과 동일한 정규화 규칙)
    private String normalize(String email) {
        return email
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
