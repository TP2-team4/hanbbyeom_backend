package com.team4.hanbbyeom.global.security;

import com.team4.hanbbyeom.global.util.EmailNormalizer;
import com.team4.hanbbyeom.user.domain.User;
import com.team4.hanbbyeom.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;


// UserDetailsService: (Spring Security 제공) 식별자로 사용자를 찾아오는 방법을 정의하는 인터페이스
// Spring은 언제 부를지만 알고, 어디서 어떻게 찾을지는 이 파일에서 우리가 정함(프로젝트마다 다르므로)
// → CustomUserDetailsService(이 파일): UserRepository로 users 테이블에서 찾아오도록 구현

// 이 클래스는 사용자를 찾아오기만 하고 비밀번호는 비교하지 않음
// 로그인 처리 흐름:
//   AuthenticationManager          요청을 처리할 Provider 선택
//     → DaoAuthenticationProvider  실제 인증 담당 (DAO = 저장소에서 데이터를 가져온다는 뜻)
//         ① loadUserByUsername()으로 사용자 조회 - 이 클래스가 담당하는 부분
//         ② 계정 상태 확인 (isEnabled 등)
//         ③ PasswordEncoder.matches()로 비밀번호 비교
//         ④ 성공 시 인증된 Authentication 생성
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository; // DB에 접근해 이메일로 사용자를 조회하기 위해 사용

    // 로그인 시 Spring Security가 호출하는 메서드
    // 파라미터 이름이 username이지만 우리 서비스의 로그인 식별자는 이메일
    @Override
    public CustomUserDetails loadUserByUsername(String email) {
        String normalizedEmail = EmailNormalizer.normalize(email); // 이메일 정규화(공백 제거 후 소문자로)

        // 탈퇴하지 않은 사용자만 조회
        User user = userRepository.findByEmailAndDeletedAtIsNull(normalizedEmail)
                .orElseThrow(() -> new UsernameNotFoundException("이메일 또는 비밀번호가 올바르지 않습니다."));

        // 조회한 User를 Spring Security가 이해할 수 있도록, UserDetails를 구현한 CustomUserDetails로 감싸서 반환
        // DaoAuthenticationProvider가 getPassword()·getAuthorities() 등을 호출하는데
        // User Entity에는 그런 메서드가 없어 그대로는 넘길 수 없음
        return new CustomUserDetails(user);
    }

    // JWT 요청 인증 시 JwtAuthenticationFilter가 호출하는 메서드
    // 검증된 토큰의 subject에서 추출한 사용자 ID로 탈퇴하지 않은 사용자를 조회
    public CustomUserDetails loadUserById(Long userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));

        // 이후 필터가 이 객체를 Authentication의 principal(현재 인증된 사용자가 누구인지)로 사용
        return new CustomUserDetails(user);
    }
}
