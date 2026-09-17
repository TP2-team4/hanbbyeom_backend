package com.team4.hanbbyeom.user.service;

import com.team4.hanbbyeom.user.domain.User;
import com.team4.hanbbyeom.user.dto.UserPreferencesResponse;
import com.team4.hanbbyeom.user.dto.UserPreferencesUpdateRequest;
import com.team4.hanbbyeom.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 사용자 본인의 정보와 기본 설정 변경을 담당하는 Service
@Service
@RequiredArgsConstructor
public class UserService {

    // 토큰 소유자와 동일한 활성 사용자를 조회해 본인 설정만 변경하기 위해 사용
    private final UserRepository userRepository;

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
}
