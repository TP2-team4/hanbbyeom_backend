package com.team4.hanbbyeom.feedback.domain;

import com.team4.hanbbyeom.matching.domain.TalkLevel;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

// 활동 하나가 끝난 뒤 상대방에 대해 남기는 후기 1건을 나타내는 엔티티.
@Entity
@Table(name = "activity_review")
public class ActivityReview {

    // @Id: 이 필드가 기본키(Primary Key)라는 표시
    // @GeneratedValue(strategy = IDENTITY): 값을 DB(PostgreSQL의 BIGSERIAL)에 맡겨서
    // INSERT 시 자동으로 1씩 증가하는 값을 채워 넣음 — 우리가 직접 id를 안 정해줘도 됨
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 어떤 활동(매칭 건)에 대한 후기인지 — activity_match 테이블의 id를 그대로 저장
    // (연관관계 매핑 없이 Long으로만 들고 있는 이유는 ActivityMatch/MatchParticipant 등
    // 기존 엔티티들도 전부 이 패턴을 쓰고 있어서 통일한 것)
    @Column(name = "activity_match_id", nullable = false)
    private Long activityMatchId; // 어떤 활동에 대한 후기인지

    @Column(name = "reviewer_user_id", nullable = false)
    private Long reviewerUserId; // 후기 쓴 사람

    // trust_profile.average_rating/perceived_talk_level 다수결 집계가 이 사람 기준으로 올라감
    @Column(name = "reviewee_user_id", nullable = false)
    private Long revieweeUserId; // 후기 받는 사람

    // 별점 1~5. DB의 chk_activity_review_rating CHECK 제약이 범위를 한 번 더 강제함
    // (엔티티에서 잘못된 값을 넣어도 DB가 최종적으로 막아줌 — 이중 방어)
    @Column(name = "rating", nullable = false)
    private Integer rating;

    // 체감 대화 수준 — matching 도메인의 TalkLevel(SILENT/LIGHT_CHAT)을 그대로 재사용.
    // @Enumerated(EnumType.STRING): enum을 DB에 저장할 때 순서 번호(0,1)가 아니라
    // 이름 그대로("SILENT" 등) 문자열로 저장 — 나중에 enum 순서가 바뀌어도 안전함
    @Enumerated(EnumType.STRING)
    @Column(name = "perceived_talk_level", nullable = false, length = 20)
    private TalkLevel perceivedTalkLevel;

    // 한 줄 후기 — 선택 입력이라 nullable(= @Column에 nullable=false를 안 붙임), 최대 100자
    @Column(name = "comment", length = 100)
    private String comment;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    // JPA가 DB에서 데이터를 읽어와 객체로 만들 때 내부적으로 쓰는 기본 생성자.
    // 외부에서 빈 객체를 함부로 못 만들게 protected로 막아둠(JPA 프록시만 접근 가능).
    protected ActivityReview() {}

    // 실제로 후기를 등록할 때 쓰는 생성자 — 필요한 값을 전부 받아서 한 번에 세팅.
    // id는 DB가 채워주고, createdAt은 "후기 작성한 지금 이 순간"으로 자동 기록.
    public ActivityReview(Long activityMatchId, Long reviewerUserId, Long revieweeUserId,
                          Integer rating, TalkLevel perceivedTalkLevel, String comment) {
        this.activityMatchId = activityMatchId;
        this.reviewerUserId = reviewerUserId;
        this.revieweeUserId = revieweeUserId;
        this.rating = rating;
        this.perceivedTalkLevel = perceivedTalkLevel;
        this.comment = comment;
        this.createdAt = OffsetDateTime.now();
    }

    // 후기는 등록된 뒤 수정/삭제 기능이 없어서(화면 설계서에도 명시됨),
    // 값을 바꾸는 메서드(setter)가 하나도 없음 — 조회용 getter만 존재
    public Long getId() { return id; }
    public Long getActivityMatchId() { return activityMatchId; }
    public Long getReviewerUserId() { return reviewerUserId; }
    public Long getRevieweeUserId() { return revieweeUserId; }
    public Integer getRating() { return rating; }
    public TalkLevel getPerceivedTalkLevel() { return perceivedTalkLevel; }
    public String getComment() { return comment; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}