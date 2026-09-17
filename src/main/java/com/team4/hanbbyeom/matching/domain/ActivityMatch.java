package com.team4.hanbbyeom.matching.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "activity_match")
public class ActivityMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "activity_type", nullable = false, length = 10)
    private String activityType = "RUN";

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    @Column(name = "scheduled_end_at", nullable = false)
    private OffsetDateTime scheduledEndAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "talk_level", nullable = false, length = 20)
    private TalkLevel talkLevel;

    @Column(name = "location", nullable = false)
    private String location;

    @Column(name = "course_name", nullable = false, length = 100)
    private String courseName;

    @Column(name = "distance_min_meters", nullable = false)
    private Integer distanceMinMeters;

    @Column(name = "distance_max_meters", nullable = false)
    private Integer distanceMaxMeters;

    @Column(name = "route_description", nullable = false, length = 1000)
    private String routeDescription;

    @Column(name = "agreed_pace_min_sec", nullable = false)
    private Integer agreedPaceMinSec;

    @Column(name = "agreed_pace_max_sec", nullable = false)
    private Integer agreedPaceMaxSec;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ActivityMatchStatus status = ActivityMatchStatus.PROPOSED;

    @Column(name = "decision_expires_at", nullable = false)
    private OffsetDateTime decisionExpiresAt;

    @Column(name = "meeting_code", length = 6)
    private String meetingCode;

    @Column(name = "confirmed_at")
    private OffsetDateTime confirmedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "closed_by_user_id")
    private Long closedByUserId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ActivityMatch() {}

    public ActivityMatch(OffsetDateTime scheduledAt, OffsetDateTime scheduledEndAt, TalkLevel talkLevel,
                         String location, String courseName, Integer distanceMinMeters, Integer distanceMaxMeters,
                         String routeDescription,
                         Integer agreedPaceMinSec, Integer agreedPaceMaxSec, OffsetDateTime decisionExpiresAt) {
        this.scheduledAt = scheduledAt;
        this.scheduledEndAt = scheduledEndAt;
        this.talkLevel = talkLevel;
        this.location = location;
        this.courseName = courseName;
        this.distanceMinMeters = distanceMinMeters;
        this.distanceMaxMeters = distanceMaxMeters;
        this.routeDescription = routeDescription;
        this.agreedPaceMinSec = agreedPaceMinSec;
        this.agreedPaceMaxSec = agreedPaceMaxSec;
        this.decisionExpiresAt = decisionExpiresAt;
        this.createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public ActivityMatchStatus getStatus() { return status; }
    public String getMeetingCode() { return meetingCode; }
    public OffsetDateTime getConfirmedAt() { return confirmedAt; }
    public OffsetDateTime getClosedAt() { return closedAt; }
    public Long getClosedByUserId() { return closedByUserId; }
    public OffsetDateTime getDecisionExpiresAt() { return decisionExpiresAt; }
    // 필요한 getter는 계속 추가하세요.

    public void confirm(String meetingCode) {
        this.status = ActivityMatchStatus.CONFIRMED;
        this.confirmedAt = OffsetDateTime.now();
        this.meetingCode = meetingCode;
    }

    public void reject(Long closedByUserId) {
        this.status = ActivityMatchStatus.REJECTED;
        this.closedAt = OffsetDateTime.now();
        this.closedByUserId = closedByUserId;
    }

    // 응답 기한(decision_expires_at)을 넘겨 시스템이 자동으로 만료시킬 때 호출.
    public void expire() {
        this.status = ActivityMatchStatus.EXPIRED;
        this.closedAt = OffsetDateTime.now();
    }
}