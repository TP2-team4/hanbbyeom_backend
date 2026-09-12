package com.team4.hanbbyeom.user.repository;

import com.team4.hanbbyeom.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// 사용자 계정 저장 및 조회를 담당하는 Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // 로그인 시 탈퇴하지 않은 사용자를 이메일로 찾는 용도
    // SELECT 대상: User
    // WHERE email = :email
    //   AND deleted_at IS NULL
    // 조건을 만족하는 활성 사용자 1명을 Optional로 반환
    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    // 회원가입 시 탈퇴하지 않은 동일 이메일 사용자의 중복 가입 방지 용도
    // SELECT 존재 여부
    // WHERE email = :email
    //   AND deleted_at IS NULL
    // 조건을 만족하는 활성 사용자가 존재하면 true, 없으면 false 반환
    boolean existsByEmailAndDeletedAtIsNull(String email);
}
