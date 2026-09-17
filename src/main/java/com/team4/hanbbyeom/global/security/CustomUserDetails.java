package com.team4.hanbbyeom.global.security;

import com.team4.hanbbyeom.user.domain.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

// UserDetails: (Spring Security 제공) 인증에 사용할 사용자 정보의 규격(인터페이스)
// Spring Security 내부 코드는 우리 User Entity를 모름
// 이 인터페이스가 요구하는 메서드만 제공하면 인증은 Spring Security가 알아서 처리
// → CustomUserDetails(이 파일): 우리 User를 그 규격에 맞춰 보여주는 번역기 역할

public class CustomUserDetails implements UserDetails {

    // User Entity를 필드에 통째로 갖고 있음
    private final User user;

    // 생성자
    public CustomUserDetails(User user) {
        this.user = user;
    }

    // UserDetails 규격에는 없지만 우리가 추가한 메서드
    // Spring Security에도 UserDetails 기본 구현체가 있지만 username, password, authorities만 담을 수 있음
    // username이 아니라 userId를 써야 하므로 커스텀!

    // Access Token의 subject(sub)를 다루는 지점이 두 곳인데 방향이 반대!
    // parseClaims().getSubject() : (토큰을 검증할 때) sub에 들어 있던 값을 읽음
    // getUserId()                : (토큰을 만들 때) sub에 넣을 사용자 id를 꺼냄
    public Long getUserId() {
        return user.getId();
    }

    // 감싸고 있는 User Entity를 그대로 꺼내는 메서드
    // 인증 과정(JwtAuthenticationFilter)에서 이미 DB 조회를 마친 User이므로,
    // Controller가 같은 사용자를 다시 조회할 필요 없이 이 객체를 그대로 사용하면 됨
    // → 내 정보 조회(GET /api/users/me)처럼 본인 정보를 응답할 때 사용
    public User getUser() {
        return user;
    }

    // 로그인 식별자
    // Spring Security에서 username은 이름이 아니라 로그인에 사용하는 식별자를 뜻함
    // → 우리 서비스는 이메일로 로그인하므로 이메일을 반환
    @Override
    public String getUsername() {
        return user.getEmail();
    }

    // DB에 저장된 BCrypt 해시를 반환 (평문 비밀번호 X)
    // 입력한 비밀번호와의 비교는 DaoAuthenticationProvider가 PasswordEncoder로 대신 수행
    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    // 사용자가 가진 권한(Role) 목록
    // 우리 서비스에는 Role 구분이 없으므로 빈 목록을 반환
    // → 빈 목록이어도 인증 자체는 성립하며, SecurityConfig의 authenticated() 규칙을 통과함
    //   (hasRole(...) 같은 권한별 제한을 쓸 때만 값이 필요)
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    // isEnabled()				    계정 활성화 여부
    // isAccountNonLocked()		    계정 잠김 여부
    // isAccountNonExpired()	    계정 만료 여부
    // isCredentialsNonExpired()	비밀번호 만료 여부
    // 하나라도 false면 비밀번호 맞아도 로그인 거부
    // UserDetails에 default 메서드로 true(정상)가 구현되어 있어 재정의하지 않음
}
