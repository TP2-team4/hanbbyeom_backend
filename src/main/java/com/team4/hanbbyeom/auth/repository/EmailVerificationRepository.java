package com.team4.hanbbyeom.auth.repository;

import com.team4.hanbbyeom.auth.domain.EmailVerification;
import com.team4.hanbbyeom.auth.domain.VerificationPurpose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// 이메일 인증 코드 저장 및 조회를 담당하는 Repository
public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    // 재전송 대기시간 확인과 인증 코드 검증 조회에 공통으로 사용
    // SELECT 대상: EmailVerification
    // WHERE email = :email
    //   AND purpose = :purpose
    // ORDER BY created_at DESC
    // 조건을 만족하는 가장 최근 인증 요청 1건을 Optional로 반환
    Optional<EmailVerification> findTopByEmailAndPurposeOrderByCreatedAtDesc(
            String email,
            VerificationPurpose purpose
    );
}
