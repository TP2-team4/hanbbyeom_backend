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

    // createdAt을 파라미터로 받는 이유: 여기서 OffsetDateTime.now()를 다시 호출하면, 호출자가
    // decisionExpiresAt을 계산할 때 쓴 now와 실제로 저장되는 createdAt이 서로 다른(created_at
    // 쪽이 항상 더 늦은) 시각이 된다. decisionExpiresAt이 now+24시간처럼 큰 여유를 갖던 예전엔
    // 문제가 안 됐지만, activity 시작 임박 시 decisionExpiresAt이 now에 바짝 붙을 수 있게 되면서
    // (MatchApplyService.apply() 참고) 그 미세한 시간차만으로 chk_activity_match_time
    // (created_at < decision_expires_at) 위반이 가능해졌다(팀원 리뷰로 발견한 회귀). 호출자가
    // 검증에 쓴 시각을 그대로 넘겨받아 createdAt으로 쓰면, 그 가드가 정확히 이 제약도 보장한다.
    public ActivityMatch(OffsetDateTime scheduledAt, OffsetDateTime scheduledEndAt, TalkLevel talkLevel,
                         String location, String courseName, Integer distanceMinMeters, Integer distanceMaxMeters,
                         String routeDescription,
                         Integer agreedPaceMinSec, Integer agreedPaceMaxSec, OffsetDateTime decisionExpiresAt,
                         OffsetDateTime createdAt) {
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
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public ActivityMatchStatus getStatus() { return status; }
    public String getMeetingCode() { return meetingCode; }
    public OffsetDateTime getConfirmedAt() { return confirmedAt; }
    public OffsetDateTime getClosedAt() { return closedAt; }
    public Long getClosedByUserId() { return closedByUserId; }
    public OffsetDateTime getDecisionExpiresAt() { return decisionExpiresAt; }
    public OffsetDateTime getScheduledAt() { return scheduledAt; }
    public OffsetDateTime getScheduledEndAt() { return scheduledEndAt; }
    public String getLocation() { return location; }
    public String getCourseName() { return courseName; }
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

    // 참가자 탈퇴로 더 이상 성사될 수 없는 확정(CONFIRMED) 매칭을 닫는다.
    // 노쇼가 아니라 사전 취소이므로 no_show_report_count는 건드리지 않고, 시스템 처리이므로
    // closedByUserId는 남기지 않는다(expire()와 동일). confirmedAt/meetingCode는 그대로 둔다 —
    // 채팅이 confirmedAt으로 "한 번이라도 확정된 매칭인지"를 판단해 과거 대화 조회를 유지하기 때문이다.
    // 이 호출 이후에 참가자 release()까지 반드시 같이 해줘야 한다(end()와 동일한 이유).
    //
    // ⚠️ 계약: CANCELLED로 만드는 경로는 둘이고, closedByUserId로 구분한다. 활동 이력 API(GET /api/matching/matches)가
    // closed_by_user_id로 취소 주체(cancelledBy: ME/COUNTERPART/SYSTEM)를 나누기 때문이다.
    //   - 회원 탈퇴로 인한 시스템 처리(이 메서드): closedByUserId=NULL
    //   - 참가자가 직접 취소(cancelByParticipant()): closedByUserId=취소한 사용자 id — 새 취소 경로를 만들면 이 값을 남겨야 한다
    public void cancelByWithdrawal() {
        this.status = ActivityMatchStatus.CANCELLED;
        this.closedAt = OffsetDateTime.now();
    }

    // 확정(CONFIRMED)된 활동을 참가자가 직접 취소했을 때 호출한다(이슈 #109). 결과 상태는 cancelByWithdrawal()과 같지만
    // 시스템이 아니라 사용자의 행동이므로 closedByUserId에 취소한 사용자를 남긴다. confirmedAt/meetingCode는 그대로 둔다 —
    // 채팅이 confirmedAt으로 "한 번이라도 확정된 매칭인지"를 판단해 과거 대화 조회를 유지하기 때문이다.
    // 이 호출 이후에 참가자 release()까지 반드시 같이 해줘야 한다(end()와 동일한 이유).
    public void cancelByParticipant(Long cancelledByUserId) {
        this.status = ActivityMatchStatus.CANCELLED;
        this.closedAt = OffsetDateTime.now();
        this.closedByUserId = cancelledByUserId;
    }

    // 확정(CONFIRMED)된 활동이 예정 종료 시각(scheduled_end_at)을 지나 자연 종료됐을 때 호출.
    // expire()와 마찬가지로 시스템이 자동으로 처리하는 것이라 closedByUserId는 남기지 않는다.
    // 이 호출 이후에 참가자 release()까지 반드시 같이 해줘야 한다 — 안 그러면 확정된 매칭의
    // 두 참가자는 uq_participant_active_user 제약에 걸려 영원히 다른 매칭에 못 들어간다.
    public void end() {
        this.status = ActivityMatchStatus.ENDED;
        this.closedAt = OffsetDateTime.now();
    }
}
