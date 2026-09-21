package com.team4.hanbbyeom.auth.service;

import com.team4.hanbbyeom.auth.dto.PasswordResetRequest;
import com.team4.hanbbyeom.auth.exception.PasswordResetAuthenticationException;
import com.team4.hanbbyeom.auth.exception.VerificationCodeMismatchException;
import com.team4.hanbbyeom.global.util.EmailNormalizer;
import com.team4.hanbbyeom.user.domain.User;
import com.team4.hanbbyeom.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 이메일 인증을 완료한 사용자의 비밀번호 재설정 처리 Service
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final String SAME_PASSWORD_MESSAGE = "기존에 사용하던 비밀번호로는 변경할 수 없습니다.";

    private final UserRepository userRepository; // 비밀번호를 변경할 가입 사용자 조회
    private final PasswordEncoder passwordEncoder; // 현재 비밀번호 확인과 새 비밀번호 암호화
    private final EmailVerificationService emailVerificationService; // 재설정 인증 검증과 사용 완료 기록 정리

    // 사용자 조회부터 인증 기록 삭제까지 하나의 트랜잭션으로 처리
    // 코드 불일치 예외만 실패 횟수 저장을 위해 롤백에서 제외
    @Transactional(noRollbackFor = VerificationCodeMismatchException.class)
    public void resetPassword(PasswordResetRequest request) {
        String email = EmailNormalizer.normalize(request.email());

        // 가입되지 않은 이메일이거나 인증에 실패해도 같은 오류를 반환해 사용자 가입 여부 노출 방지
        User user = userRepository
                .findByEmailAndDeletedAtIsNull(email)
                .orElseThrow(PasswordResetAuthenticationException::new);

        // 가장 최근의 비밀번호 재설정 인증 기록을 조회, 사용자가 입력한 인증 코드가 유효한지 다시 확인
        emailVerificationService.validatePasswordResetAuthorization(
                email,
                request.code()
        );

        // 현재 비밀번호와 같은 값의 재설정 차단
        // 인증 기록 삭제 전에 검사해 다른 비밀번호로 다시 요청 가능
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException(SAME_PASSWORD_MESSAGE);
        }

        // 새 비밀번호의 BCrypt 암호화와 관리 상태 User Entity 반영
        String newPasswordHash = passwordEncoder.encode(request.newPassword());
        user.changePasswordHash(newPasswordHash);

        // 이전 인증 완료 기록의 재사용 방지를 위한 PASSWORD_RESET 기록 전체 삭제
        emailVerificationService.consumePasswordResetVerifications(email);
    }
}
