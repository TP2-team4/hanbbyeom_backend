package com.team4.hanbbyeom.auth.service;

import com.team4.hanbbyeom.auth.domain.EmailVerification;
import com.team4.hanbbyeom.auth.domain.VerificationPurpose;
import com.team4.hanbbyeom.auth.dto.LoginRequest;
import com.team4.hanbbyeom.auth.dto.LoginResponse;
import com.team4.hanbbyeom.auth.dto.SignUpRequest;
import com.team4.hanbbyeom.auth.dto.SignUpResponse;
import com.team4.hanbbyeom.auth.repository.EmailVerificationRepository;
import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import com.team4.hanbbyeom.user.domain.User;
import com.team4.hanbbyeom.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AuthService의 회원가입/로그인 로직을 실제 PostgreSQL로 검증

@SpringBootTest // Spring Boot 애플리케이션 전체 컨텍스트를 띄워서 테스트하는 어노테이션
@Transactional // 테스트 메서드가 끝나면 DB 변경 내용을 롤백
class AuthServiceTest {

    @Autowired // Spring이 필요한 객체를 자동으로 주입해주는 어노테이션
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmailVerificationRepository emailVerificationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider; // 발급된 토큰의 내용을 확인하기 위해 사용

    private static final String PASSWORD = "test1234";
    private static final String NICKNAME = "테스트";

    // 인증 기록의 codeHash는 이 테스트에서 비교 대상이 아니므로 형식만 맞는 더미값 사용
    private static final String DUMMY_CODE_HASH = "00".repeat(32);

    // 테스트마다 겹치지 않는 이메일 생성 (EmailVerificationServiceTest와 동일한 방식)
    private String randomEmail() {
        return "auth-" + UUID.randomUUID() + "@example.com";
    }

    // 회원가입은 이메일 인증 완료를 전제로 하므로, 인증이 끝난 기록을 미리 만들어둠
    // (실제 인증 코드는 해시로만 저장되어 테스트에서 알 수 없으므로 기록을 직접 저장)
    private void 이메일_인증_완료(String email) {
        EmailVerification verification = new EmailVerification(
                email,
                VerificationPurpose.SIGNUP,
                DUMMY_CODE_HASH,
                Instant.now().plusSeconds(300) // 만료 시각
        );
        verification.markVerified(Instant.now()); // 인증 성공 시각 기록

        emailVerificationRepository.save(verification);
    }

    // 로그인 테스트에서 사용할 가입 완료 계정 준비
    private SignUpResponse 가입된_계정(String email) {
        이메일_인증_완료(email);

        return authService.signUp(
                new SignUpRequest(
                        email,
                        PASSWORD,
                        NICKNAME
                )
        );
    }

    @Test
    @DisplayName("회원가입 시 비밀번호가 평문이 아닌 해시로 저장")
    void signUp_비밀번호_해시_저장() {
        String email = randomEmail();

        가입된_계정(email);

        User user = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();

        // 입력한 비밀번호가 그대로 저장되지 않았는지 확인
        assertNotEquals(PASSWORD, user.getPasswordHash());
        // 저장된 해시가 입력 비밀번호로부터 만들어진 것이 맞는지 확인
        assertTrue(passwordEncoder.matches(PASSWORD, user.getPasswordHash()));
    }

    @Test
    @DisplayName("이미 가입된 이메일로 회원가입 거부")
    void signUp_중복_이메일_거부() {
        String email = randomEmail();
        가입된_계정(email);

        assertThrows(
                IllegalStateException.class,
                () -> authService.signUp(
                        new SignUpRequest(
                                email,
                                PASSWORD,
                                NICKNAME
                        )
                )
        );
    }

    @Test
    @DisplayName("이메일 인증 없이 회원가입 거부")
    void signUp_미인증_이메일_거부() {
        // 인증 기록을 만들지 않고 바로 가입 시도
        assertThrows(
                IllegalStateException.class,
                () -> authService.signUp(
                        new SignUpRequest(
                                randomEmail(),
                                PASSWORD,
                                NICKNAME
                        )
                )
        );
    }

    @Test
    @DisplayName("로그인 성공 시 사용자 id를 subject로 담은 Access Token 발급")
    void login_성공() {
        String email = randomEmail();
        SignUpResponse signUp = 가입된_계정(email);

        LoginResponse response = authService.login(
                new LoginRequest(
                        email,
                        PASSWORD
                )
        );

        assertNotNull(response.accessToken());

        // 토큰을 검증하고 내용 확인
        Claims claims = jwtTokenProvider.parseClaims(response.accessToken());
        // subject에 이메일이 아니라 사용자 id가 담겨야 함
        assertEquals(String.valueOf(signUp.id()), claims.getSubject());
    }

    @Test
    @DisplayName("대문자와 공백이 섞인 이메일로도 로그인 성공")
    void login_이메일_정규화() {
        String email = randomEmail();
        가입된_계정(email);

        // 저장은 소문자로 되지만, 조회 시에도 같은 규칙으로 정규화되므로 찾을 수 있어야 함
        LoginResponse response = authService.login(
                new LoginRequest(
                        "  " + email.toUpperCase() + "  ",
                        PASSWORD
                )
        );

        assertNotNull(response.accessToken());
    }

    @Test
    @DisplayName("잘못된 비밀번호로 로그인 거부")
    void login_비밀번호_불일치_거부() {
        String email = randomEmail();
        가입된_계정(email);

        assertThrows(
                BadCredentialsException.class,
                () -> authService.login(
                        new LoginRequest(
                                email,
                                "wrong-password"
                        )
                )
        );
    }

    @Test
    @DisplayName("가입되지 않은 이메일로 로그인 거부 (비밀번호 불일치와 같은 예외)")
    void login_없는_이메일_거부() {
        // 사용자를 못 찾은 경우에도 UsernameNotFoundException이 아니라
        // BadCredentialsException으로 바뀌어 전달됨
        // → 가입 여부가 응답으로 드러나지 않음
        assertThrows(
                BadCredentialsException.class,
                () -> authService.login(
                        new LoginRequest(
                                randomEmail(),
                                PASSWORD
                        )
                )
        );
    }
}
