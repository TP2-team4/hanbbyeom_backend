package com.team4.hanbbyeom.matching.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "match_participant")
public class MatchParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "activity_match_id", nullable = false)
    private Long activityMatchId;

    // 호스트(A)는 항상 값이 있지만, 신청자(B)는 본인 게시글 없이 신청할 수 있어 NULL일 수 있음
    @Column(name = "match_request_id")
    private Long matchRequestId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "slot", nullable = false, length = 1)
    private String slot; // "A" = 호스트, "B" = 신청자

    @Enumerated(EnumType.STRING)
    @Column(name = "accept_status", nullable = false, length = 20)
    private AcceptStatus acceptStatus = AcceptStatus.PENDING;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    @Column(name = "released_at")
    private OffsetDateTime releasedAt;

    protected MatchParticipant() {}

    public MatchParticipant(Long activityMatchId, Long matchRequestId, Long userId, String slot,
                            AcceptStatus acceptStatus) {
        this.activityMatchId = activityMatchId;
        this.matchRequestId = matchRequestId;
        this.userId = userId;
        this.slot = slot;
        this.acceptStatus = acceptStatus;
        if (acceptStatus != AcceptStatus.PENDING) {
            this.respondedAt = OffsetDateTime.now();
        }
    }

    public Long getId() { return id; }
    public Long getActivityMatchId() { return activityMatchId; }
    public Long getMatchRequestId() { return matchRequestId; }
    public Long getUserId() { return userId; }
    public String getSlot() { return slot; }
    public AcceptStatus getAcceptStatus() { return acceptStatus; }
    public OffsetDateTime getReleasedAt() { return releasedAt; }

    public void accept() {
        this.acceptStatus = AcceptStatus.ACCEPTED;
        this.respondedAt = OffsetDateTime.now();
    }

    public void reject() {
        this.acceptStatus = AcceptStatus.REJECTED;
        this.respondedAt = OffsetDateTime.now();
    }

    // 이 참여 연결을 "끝난" 상태로 표시 — released_at이 채워져야 uq_participant_active_user/
    // uq_participant_active_request 부분 유니크 인덱스에서 빠져서, 이 유저(또는 이 게시글)가
    // 다음 매칭에 다시 참여할 수 있게 된다. CONFIRMED(확정)는 아직 활동이 안 끝났으므로 호출하면
    // 안 되고, REJECTED/EXPIRED/CANCELLED처럼 매칭이 성사되지 않고 끝났을 때만 호출한다.
    public void release() {
        this.releasedAt = OffsetDateTime.now();
    }
}