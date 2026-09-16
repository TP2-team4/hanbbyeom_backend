package com.team4.hanbbyeom.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

// 이메일 인증 코드 발송 및 확인 이력을 나타내는 Entity
// 재전송/재요청 시 기존 행을 수정하지 않고 새 행을 추가하는 방식(V3 참고)

@Entity
@Table(name = "email_verifications")
@Getter
// 필드 임의 변경 방지를 위한 전체 Setter 미사용, 상태 변경은 기능별 메서드로 제한
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class EmailVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 인증 대상 이메일 (Service에서 정규화한 소문자 이메일)
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    // 인증 목적: SIGNUP(회원가입), PASSWORD_RESET(비밀번호 재설정)
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20)
    private VerificationPurpose purpose;

    // 인증 코드 원문이 아닌 해시값
    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    // 인증 코드 만료 시각
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    // 인증 완료(사용 완료) 시각, 미사용 상태면 NULL
    @Column(name = "verified_at")
    private Instant verifiedAt;

    // 인증 코드 입력 실패 횟수 (무차별 대입 방지를 위해 일정 횟수 초과 시 이 코드를 거부)
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    // 생성 시각(=발송 시각), 재전송 대기시간 판단 기준
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // 생성자
    // 인증 코드 발송 시점의 인증 요청 생성
    public EmailVerification(
            // id                       → PostgreSQL이 자동 생성
            String email,
            VerificationPurpose purpose,
            String codeHash,
            Instant expiresAt
            // verifiedAt               → 아직 인증 전이므로 NULL
            // attemptCount             → 기본값 0
            // createdAt                → JPA Auditing이 자동 기록
    ) {
        this.email = email;
        this.purpose = purpose;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.attemptCount = 0;
    }

    // 인증 성공 시 사용 완료 시각 기록
    public void markVerified(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    // 인증 코드 불일치 시 실패 횟수 증가
    public void increaseAttemptCount() {
        this.attemptCount++;
    }
}
