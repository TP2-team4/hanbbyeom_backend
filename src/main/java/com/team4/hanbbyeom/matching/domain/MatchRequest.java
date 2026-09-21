package com.team4.hanbbyeom.matching.domain;

import com.team4.hanbbyeom.matching.exception.MatchRequestNotSearchingException;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "match_request")
public class MatchRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "activity_type", nullable = false, length = 10)
    private String activityType = "RUN";

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "talk_level", nullable = false, length = 20)
    private TalkLevel talkLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private MatchRequestStatus status = MatchRequestStatus.SEARCHING;

    @Column(name = "search_expires_at", nullable = false)
    private OffsetDateTime searchExpiresAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected MatchRequest() {}

    public MatchRequest(Long userId, OffsetDateTime scheduledAt, TalkLevel talkLevel,
                        OffsetDateTime searchExpiresAt) {
        this.userId = userId;
        this.scheduledAt = scheduledAt;
        this.talkLevel = talkLevel;
        this.searchExpiresAt = searchExpiresAt;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getActivityType() { return activityType; }
    public OffsetDateTime getScheduledAt() { return scheduledAt; }
    public TalkLevel getTalkLevel() { return talkLevel; }
    public MatchRequestStatus getStatus() { return status; }
    public OffsetDateTime getSearchExpiresAt() { return searchExpiresAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void changeStatus(MatchRequestStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = OffsetDateTime.now();
    }

    // Day2 신규 — "모집글 수정": 아직 신청자가 없는(SEARCHING) 상태에서만 허용
    public void changeConditions(OffsetDateTime scheduledAt, TalkLevel talkLevel, OffsetDateTime searchExpiresAt) {
        if (this.status != MatchRequestStatus.SEARCHING) {
            throw new MatchRequestNotSearchingException("모집 중인 게시글만 수정할 수 있어요.");
        }
        this.scheduledAt = scheduledAt;
        this.talkLevel = talkLevel;
        this.searchExpiresAt = searchExpiresAt;
        this.updatedAt = OffsetDateTime.now();
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }
}