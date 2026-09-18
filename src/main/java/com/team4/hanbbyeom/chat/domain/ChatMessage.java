package com.team4.hanbbyeom.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

// 확정된 매칭 참가자들이 주고받은 메시지를 저장하는 Entity
// 별도 채팅방 Entity 없이 activityMatchId 하나가 채팅방을 구분하는 역할을 함
@Entity
@Table(name = "chat_message")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 메시지가 속한 매칭 ID
    // Matching 도메인 Entity를 직접 참조하지 않고 ID만 저장해 도메인 간 결합을 줄임
    @Column(name = "activity_match_id", nullable = false)
    private Long activityMatchId;

    // 메시지를 보낸 사용자 ID
    // 클라이언트 입력이 아닌 JWT 인증을 마친 사용자의 ID를 저장
    @Column(name = "sender_id", nullable = false)
    private Long senderId;

    // 일반 입력과 프리셋 버튼 문구를 구분하지 않고 동일한 메시지 내용으로 저장
    @Column(name = "content", nullable = false, length = 100)
    private String content;

    // 메시지 정렬과 화면의 전송 시각 표시에 사용하는 서버 생성 시각
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    // JPA가 DB 조회 결과를 Entity로 만들 때 사용하는 기본 생성자
    protected ChatMessage() {
    }

    public ChatMessage(Long activityMatchId, Long senderId, String content) {
        this.activityMatchId = activityMatchId;
        this.senderId = senderId;
        this.content = content;
    }

    // 애플리케이션에서 저장할 때도 생성 시각이 항상 채워지도록 보장
    // DB의 CURRENT_TIMESTAMP 기본값은 JPA를 거치지 않는 직접 INSERT에 대한 안전장치
    @PrePersist
    void recordCreatedAt() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getActivityMatchId() {
        return activityMatchId;
    }

    public Long getSenderId() {
        return senderId;
    }

    public String getContent() {
        return content;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
