package com.team4.hanbbyeom.user.domain;

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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.Objects;

// OffsetDateTime: 날짜·시간과 UTC 기준 시차를 함께 표현
// Instant: 전 세계에서 동일한 하나의 UTC 시점을 표현
// TIMESTAMPTZ는 원래 시차를 보존하는 것이 아닌 동일한 시점을 저장하므로 Instant 사용
// 화면 표시 시 프론트엔드에서 한국 시간 등 사용자 시간대로 변환

// 사용자 계정과 이메일 인증 및 탈퇴 상태를 나타내는 User Entity

@Entity
@Table(name = "users")
@Getter
// 필드 임의 변경 방지를 위한 전체 Setter 미사용, 상태 변경은 기능별 메서드로 제한

// 매개변수가 없는 생성자를 protected 접근 범위로 자동 생성
@NoArgsConstructor(access = AccessLevel.PROTECTED)

// User가 저장되거나 변경되는 시점을 AuditingEntityListener가 감지하도록 연결
@EntityListeners(AuditingEntityListener.class)

public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 이메일 (탈퇴 시 NULL)
    @Column(name = "email", length = 255)
    private String email;

    // 비밀번호 (탈퇴 시 NULL)
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    // 닉네임 (탈퇴 시 NULL)
    @Column(name = "nickname", length = 50)
    private String nickname;

    // 모집글 작성 화면에 기본으로 적용할 대화 수준 (탈퇴 시 NULL)
    @Enumerated(EnumType.STRING)
    @Column(name = "default_talk_level", length = 20)
    private DefaultTalkLevel defaultTalkLevel;

    // 회원가입 이메일 인증 완료 시각 (탈퇴 시 NULL)
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    // 회원 탈퇴 시각 (활성 회원은 NULL)
    @Column(name = "deleted_at")
    private Instant deletedAt;

    // 계정 생성 시각 (Entity 최초 저장 시 자동으로 기록)
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // 계정 마지막 수정 시각 (Entity 변경 시 자동으로 갱신)
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // 생성자
    // 이메일 인증 완료 후 가입이 완료된 사용자를 생성
    // Service가 가공하고 검증한 값을 생성자에 전달해야 함
    public User(
            // id                       → PostgreSQL이 자동 생성
            String email,
            String passwordHash,
            String nickname,
            DefaultTalkLevel defaultTalkLevel,
            Instant emailVerifiedAt
            // deletedAt                → 활성 회원이므로 NULL
            // createdAt                → JPA Auditing이 자동 기록
            // updatedAt                → JPA Auditing이 자동 기록
    ) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.nickname = nickname;
        // requireNonNull: 값이 null인지 검사하고, null이면 바로 예외를 발생시키는 메서드(아니면 그대로 반환)
        this.defaultTalkLevel = Objects.requireNonNull(defaultTalkLevel);
        this.emailVerifiedAt = emailVerifiedAt;
    }
}
