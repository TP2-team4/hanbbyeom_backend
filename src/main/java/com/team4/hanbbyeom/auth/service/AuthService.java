package com.team4.hanbbyeom.auth.service;

import com.team4.hanbbyeom.auth.domain.VerificationPurpose;
import com.team4.hanbbyeom.auth.dto.LoginRequest;
import com.team4.hanbbyeom.auth.dto.LoginResponse;
import com.team4.hanbbyeom.auth.dto.SignUpRequest;
import com.team4.hanbbyeom.auth.dto.SignUpResponse;
import com.team4.hanbbyeom.global.security.CustomUserDetails;
import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import com.team4.hanbbyeom.global.util.EmailNormalizer;
import com.team4.hanbbyeom.user.domain.User;
import com.team4.hanbbyeom.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

// 회원가입, 로그인 등 인증 관련 비즈니스 로직을 담당하는 Service
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository; // 사용자 계정을 DB에 저장하고 조회하기 위해 사용
    private final PasswordEncoder passwordEncoder; // 비밀번호를 BCrypt로 암호화/검증하기 위해 사용
    private final EmailVerificationService emailVerificationService; // 이메일 인증 완료 여부 확인을 위해 사용
    private final AuthenticationManager authenticationManager; // 로그인 시 이메일·비밀번호 인증을 요청하기 위해 사용
    private final JwtTokenProvider jwtTokenProvider; // 인증 성공 후 Access Token을 발급하기 위해 사용

    // 회원가입: 이메일 중복 확인, 이메일 인증 완료 확인, 비밀번호 암호화 후 사용자 저장
    @Transactional
    // SignUpRequest: 이메일, 비밀번호, 닉네임을 하나로 묶어놓은 객체
    public SignUpResponse signUp(SignUpRequest request) {
        String email = EmailNormalizer.normalize(request.email()); // 이메일 정규화(공백 제거 후 소문자로)

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

    // 로그인: 이메일·비밀번호 인증 후 Access Token 발급
    // 비밀번호 비교 코드가 여기 없는 이유: AuthenticationManager가 대신 처리
    // (CustomUserDetailsService로 사용자 조회 → PasswordEncoder로 비밀번호 비교)
    // 이메일 정규화도 조회 단계인 CustomUserDetailsService에서 수행
    public LoginResponse login(LoginRequest request) {

        // Authentication: 인증 정보를 담는 객체 (인증 전후 의미가 다름. 이 사람 확인해줘/이 사람 확인됐어)
        // authenticate: 인터페이스의 유일한 메서드. 성공하면 결과 반환, 실패하면 예외 던짐

        // 인자 2개짜리 생성자 = 아직 검증되지 않은 인증 요청서
        Authentication authenticationRequest = new UsernamePasswordAuthenticationToken(
                request.email(), // principal: 확인할 대상
                request.password() // credentials: 확인에 사용할 비밀번호
        );

        // 인증 수행. 실패하면 여기서 AuthenticationException(주로 BadCredentialsException)이 발생
        // 성공하면 검증이 끝난 인증 결과가 반환됨. 결과에서 credentials은 지워짐
        Authentication authentication =
                authenticationManager.authenticate(authenticationRequest);

        // getPrincipal()의 반환 타입이 Object인 이유
        // 인증 전에는 이메일(String), 인증 후에는 UserDetails가 담겨 타입을 하나로 정할 수 없음
        // CustomUserDetailsService가 CustomUserDetails를 반환했으므로 그 타입으로 변환
        CustomUserDetails principal =
                (CustomUserDetails) authentication.getPrincipal();

        // 토큰의 subject(sub)에 사용자 id를 담아 Access Token 발급
        String accessToken = jwtTokenProvider
                .createAccessToken(principal.getUserId());

        return new LoginResponse(accessToken);
    }
}
