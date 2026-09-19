package com.team4.hanbbyeom.user.service;

import com.team4.hanbbyeom.auth.repository.EmailVerificationRepository;
import com.team4.hanbbyeom.matching.domain.MatchRequestStatus;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.user.domain.User;
import com.team4.hanbbyeom.user.dto.UserPreferencesResponse;
import com.team4.hanbbyeom.user.dto.UserPreferencesUpdateRequest;
import com.team4.hanbbyeom.user.exception.WithdrawPasswordMismatchException;
import com.team4.hanbbyeom.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 사용자 본인의 정보와 기본 설정 변경을 담당하는 Service
@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    // 토큰 소유자와 동일한 활성 사용자를 조회해 본인 설정만 변경하기 위해 사용
    private final UserRepository userRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final PasswordEncoder passwordEncoder; // 탈퇴 시 입력한 비밀번호가 본인 것인지 확인하기 위해 사용
    private final MatchRequestRepository matchRequestRepository; // 탈퇴 시 모집 중인 게시글을 취소하기 위해 사용

    // 기본 대화 수준 변경: 현재 사용자 설정만 수정하고 기존 모집글·매칭의 대화 수준은 변경하지 않음
    @Transactional
    public UserPreferencesResponse updatePreferences(Long userId, UserPreferencesUpdateRequest request) {
        // DB에 변경을 저장할 User를 다시 조회 (탈퇴한 사용자는 제외)
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new IllegalStateException("사용자 정보를 찾을 수 없습니다."));

        // Entity의 상태 변경 메서드 사용
        // 트랜잭션 안에서 조회한 Entity이므로 별도 save() 없이 Dirty Checking으로 UPDATE 실행
        user.changeDefaultTalkLevel(request.defaultTalkLevel());

        // from: User Entity를 UserPreferencesResponse DTO로 변환
        return UserPreferencesResponse.from(user);
    }

    // 회원 탈퇴 — 본인 비밀번호를 다시 확인한 뒤, 모집 중인 게시글을 취소하고,
    // 개인정보를 지우기 전에 이메일 인증 기록 정리용으로 이메일을 먼저 담아둔다.
    @Transactional
    public void withdraw(Long userId, String rawPassword) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new IllegalStateException("사용자 정보를 찾을 수 없습니다."));

        // 비밀번호가 틀리면 아무것도 변경하지 않고 거부 (User.withdraw() 호출 전)
        // 되돌릴 수 없는 작업이라 실패도 서버 로그에 남긴다(비밀번호 대입 시도를 뒤늦게라도 탐지하기 위함).
        // 비밀번호·이메일 같은 개인정보는 로그에 넣지 않고 userId만 기록한다.
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            log.warn("회원 탈퇴 비밀번호 불일치: userId={}", userId);
            throw new WithdrawPasswordMismatchException();
        }

        // 아직 신청자가 없는 모집 중(SEARCHING) 게시글은 상대방이 없으므로 그대로 취소한다.
        // 작성자가 사라진 글이 모집 탭 데이터로 남는 걸 막기 위함이며, 신청이 진행 중이거나
        // 확정된 매칭(PENDING_CONFIRMATION/MATCHED)은 상대방이 있어 제품 결정이 필요하므로 건드리지 않는다.
        matchRequestRepository
                .findByUserIdAndStatusIn(userId, List.of(MatchRequestStatus.SEARCHING))
                .ifPresent(request -> request.changeStatus(MatchRequestStatus.CANCELLED));

        String email = user.getEmail(); // withdraw()가 이 값을 NULL로 지우기 전에 먼저 보관

        user.withdraw();

        emailVerificationRepository.deleteByEmail(email);

        log.info("회원 탈퇴 처리 완료: userId={}", userId);
    }
}
