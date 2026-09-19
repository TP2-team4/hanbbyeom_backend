package com.team4.hanbbyeom.auth.repository;

import com.team4.hanbbyeom.auth.domain.EmailVerification;
import com.team4.hanbbyeom.auth.domain.VerificationPurpose;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

// 이메일 인증 코드 저장 및 조회를 담당하는 Repository
public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {

    // 재전송 대기시간 확인과 인증 코드 검증 조회에 공통으로 사용
    // 동시각 기록에서도 같은 행이 선택되도록 id를 보조 정렬 기준으로 사용
    // SELECT 대상: EmailVerification
    // WHERE email = :email
    //   AND purpose = :purpose
    // ORDER BY created_at DESC, id DESC
    // 조건을 만족하는 가장 최근 인증 요청 1건을 Optional로 반환
    Optional<EmailVerification> findTopByEmailAndPurposeOrderByCreatedAtDescIdDesc(
            String email,
            VerificationPurpose purpose
    );

    // 같은 인증 기록이 여러 비밀번호 재설정 요청에서 동시에 사용되지 않도록
    // 가장 최근 인증 기록 1건을 조회하면서 DB 잠금을 걸어둠 (비관적 쓰기 잠금!)
    // JPQL에는 LIMIT 문법이 없어 최신 1건 제한은 Limit 파라미터로 전달
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT v FROM EmailVerification v
        WHERE v.email = :email
          AND v.purpose = :purpose
        ORDER BY v.createdAt DESC, v.id DESC
        """)
    Optional<EmailVerification> findLatestForUpdate(
            @Param("email") String email,
            @Param("purpose") VerificationPurpose purpose,
            Limit limit
    );

    // 비밀번호 재설정이 완료된 뒤 같은 인증 기록을 다시 사용할 수 없도록
    // 해당 이메일의 비밀번호 재설정 인증 기록을 모두 삭제
    void deleteAllByEmailAndPurpose(String email, VerificationPurpose purpose);

    // 회원 탈퇴 시 남아 있는 이메일 인증 기록을 정리하는 용도.
    // DELETE FROM email_verifications WHERE email = :email
    void deleteByEmail(String email);
}
