package com.team4.hanbbyeom.feedback.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

// 노쇼(약속 불이행) 신고 1건을 나타내는 엔티티.
// activity_review(후기)와 똑같은 구조인데, "본 사람의 평가"가 아니라
// "안 나타난 사람에 대한 신고"라서 필드 의미가 다름 — 그래서 같은 테이블에 안 넣고 분리함.
@Entity
@Table(name = "no_show_report")
public class NoShowReport {

    // @Id: 이 필드가 기본키(Primary Key)라는 표시
    // @GeneratedValue(strategy = IDENTITY): 값을 DB(PostgreSQL의 BIGSERIAL)에 맡겨서
    // INSERT 시 자동으로 1씩 증가하는 값을 채워 넣음 — 우리가 직접 id를 안 정해줘도 됨
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 어떤 활동(매칭 건)에 대한 신고인지 — activity_match 테이블의 id를 그대로 저장
    // (연관관계 매핑 없이 Long으로만 들고 있는 이유는 ActivityMatch/MatchParticipant 등
    // 기존 엔티티들도 전부 이 패턴을 쓰고 있어서 통일한 것)
    @Column(name = "activity_match_id", nullable = false)
    private Long activityMatchId;

    // 신고한 사람 (실제로 활동 나갔는데 상대가 안 왔다고 알리는 쪽)
    @Column(name = "reporter_user_id", nullable = false)
    private Long reporterUserId;

    // 신고당한 사람 (안 나타났다고 지목된 쪽) — trust_profile.no_show_report_count가
    // 이 사람 기준으로 올라감
    @Column(name = "reported_user_id", nullable = false)
    private Long reportedUserId;

    // 신고 사유 — NoShowReason enum 3가지 중 하나 (필수 선택)
    // @Enumerated(EnumType.STRING): enum을 DB에 저장할 때 순서 번호(0,1,2)가 아니라
    // 이름 그대로("NOT_SHOWED_UP" 등) 문자열로 저장 — 나중에 enum 순서가 바뀌어도 안전함
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private NoShowReason reason;

    // 상세 내용 — 선택 입력이라 nullable(= @Column에 nullable=false를 안 붙임)
    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    // JPA가 DB에서 데이터를 읽어와 객체로 만들 때 내부적으로 쓰는 기본 생성자.
    // 외부에서 빈 객체를 함부로 못 만들게 protected로 막아둠(JPA 프록시만 접근 가능).
    protected NoShowReport() {}

    // 실제로 신고를 접수할 때 쓰는 생성자 — 필요한 값을 전부 받아서 한 번에 세팅.
    // id는 DB가 채워주고, createdAt은 "신고 접수한 지금 이 순간"으로 자동 기록.
    public NoShowReport(Long activityMatchId, Long reporterUserId, Long reportedUserId,
                        NoShowReason reason, String detail) {
        this.activityMatchId = activityMatchId;
        this.reporterUserId = reporterUserId; // 신고한 사람
        this.reportedUserId = reportedUserId; // 신고당한 사람
        this.reason = reason;
        this.detail = detail;
        this.createdAt = OffsetDateTime.now();
    }

    // 신고는 접수된 뒤 절대 수정/취소가 안 되는 기능이라(화면 설계서에도 명시됨),
    // 값을 바꾸는 메서드(setter)가 하나도 없음 — 조회용 getter만 존재
    public Long getId() { return id; }
    public Long getActivityMatchId() { return activityMatchId; }
    public Long getReporterUserId() { return reporterUserId; }
    public Long getReportedUserId() { return reportedUserId; }
    public NoShowReason getReason() { return reason; }
    public String getDetail() { return detail; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}