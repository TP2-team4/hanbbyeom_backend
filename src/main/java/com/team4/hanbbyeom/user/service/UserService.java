package com.team4.hanbbyeom.user.service;

import com.team4.hanbbyeom.auth.repository.EmailVerificationRepository;
import com.team4.hanbbyeom.matching.service.MatchDecisionService;
import com.team4.hanbbyeom.user.domain.User;
import com.team4.hanbbyeom.user.dto.UserNicknameResponse;
import com.team4.hanbbyeom.user.dto.UserNicknameUpdateRequest;
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

// 사용자 본인의 정보와 기본 설정 변경을 담당하는 Service
@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    // 토큰 소유자와 동일한 활성 사용자를 조회해 본인 설정만 변경하기 위해 사용
    private final UserRepository userRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final PasswordEncoder passwordEncoder; // 탈퇴 시 입력한 비밀번호가 본인 것인지 확인하기 위해 사용
    private final MatchDecisionService matchDecisionService; // 탈퇴 시 진행 중인 매칭과 모집 중인 게시글을 정리하기 위해 사용

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

    // 닉네임 변경: 현재 사용자의 닉네임만 수정
    // 닉네임 노출 지점(모집 목록·상세·신청 내역·활동 이력 등)이 users.nickname을 직접 조회하므로 다른 테이블 갱신 불필요
    @Transactional
    public UserNicknameResponse updateNickname(Long userId, UserNicknameUpdateRequest request) {
        // DB에 변경을 저장할 User를 다시 조회 (탈퇴한 사용자는 제외)
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new IllegalStateException("사용자 정보를 찾을 수 없습니다."));

        // 트랜잭션 안에서 조회한 Entity이므로 별도 save() 없이 Dirty Checking으로 UPDATE 실행
        user.changeNickname(request.nickname());

        return UserNicknameResponse.from(user);
    }

    // 회원 탈퇴: 본인 비밀번호 재확인 후 진행 중인 매칭과 모집 중인 게시글 정리
    // 개인정보 삭제 전에 이메일 인증 기록 정리용으로 이메일 먼저 보관
    @Transactional
    public void withdraw(Long userId, String rawPassword) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new IllegalStateException("사용자 정보를 찾을 수 없습니다."));

        // 비밀번호가 틀리면 아무것도 변경하지 않고 거부 (User.withdraw() 호출 전)
        // 되돌릴 수 없는 작업이라 실패도 서버 로그에 기록 (비밀번호 대입 시도의 사후 탐지 목적)
        // 비밀번호·이메일 같은 개인정보는 로그에서 제외하고 userId만 기록
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            log.warn("회원 탈퇴 비밀번호 불일치: userId={}", userId);
            throw new WithdrawPasswordMismatchException();
        }

        // 탈퇴자가 참가 중인 활성 매칭(신청 대기·확정)과 모집 중인 게시글 정리
        // 탈퇴로 불참이 확정됐는데 확정된 매칭을 방치하면 상대가 기다리게 되므로 정리 필요
        // 순서(매칭 정리 → 게시글 취소)는 이 호출 안에서 보장
        matchDecisionService.closeMatchesOnWithdrawal(userId);

        String email = user.getEmail(); // withdraw()가 이 값을 NULL로 지우기 전에 먼저 보관

        user.withdraw();

        emailVerificationRepository.deleteByEmail(email);

        log.info("회원 탈퇴 처리 완료: userId={}", userId);
    }
}
