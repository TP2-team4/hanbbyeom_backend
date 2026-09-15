package com.team4.hanbbyeom.run.domain;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;


// 사용자가 러닝 매칭 조건으로 등록한 코스·거리·페이스·만나는 곳 정보를 저장하는 Entity
// match_request(모집 게시글) 1건당 정확히 1건만 존재하는 1:1 관계
@Entity
@Table(name = "run_match_condition")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RunMatchCondition implements Persistable<Long> {

    // PK이지만 자동 채번(@GeneratedValue) 아님
    // 새 값을 직접 만드는 게 아니라, 아래 matchRequest의 ID를 그대로 공유해서 씀 (@MapsId)
    @Id
    private Long matchRequestId;

    // match_request와의 1:1 관계
    // @MapsId: 이 연관관계가 가리키는 MatchRequest의 ID를 그대로 내 PK(matchRequestId)로 사용
    // → 별도의 FK 컬럼을 추가로 만들지 않고, PK 컬럼 하나가 PK이자 FK 역할을 동시에 함
    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "match_request_id")
    private MatchRequest matchRequest;

    // 선택한 러닝 코스 (N:1) — 여러 사람이 같은 코스를 선택할 수 있음
    @ManyToOne(fetch =FetchType.LAZY)
    @JoinColumn(name = "course_id")
    private RunningCourse runningCourse;

    // 사용자가 슬라이더로 선택한 거리 범위(m). 코스 고정값이 아니라 매번 직접 선택하는 조건값
    private Integer distanceMinMeters;
    private Integer distanceMaxMeters;

    // 사용자가 직접 입력한 만남 장소
    private String meetingPoint;

    // 사용자가 슬라이더로 선택한 페이스 범위
    private Integer paceMinSec;
    private Integer paceMaxSec;

    // DB 컬럼이 아니라 "이 객체가 새로 만들어진 건지" 표시만 하는 임시 플래그
    @Transient
    private boolean isNew = true;

    // matchRequestId를 별도 파라미터로 받지 않는 이유:
    // @MapsId 관계이므로 matchRequest 객체 하나만 받아서 그 안의 ID를 그대로 꺼내 쓰면
    // PK와 연관관계가 항상 같은 값을 가리키도록 보장됨 (따로 값을 넘기면 둘이 어긋날 위험이 있음)
    @Builder
    public RunMatchCondition(MatchRequest matchRequest, RunningCourse runningCourse, Integer distanceMinMeters, Integer distanceMaxMeters, String meetingPoint, Integer paceMinSec, Integer paceMaxSec) {
        this.matchRequest = matchRequest;
        this.matchRequestId = matchRequest.getId();
        this.runningCourse = runningCourse;
        this.distanceMinMeters = distanceMinMeters;
        this.distanceMaxMeters = distanceMaxMeters;
        this.meetingPoint = meetingPoint;
        this.paceMinSec = paceMinSec;
        this.paceMaxSec = paceMaxSec;
    }

    @Override
    public Long getId() {
        return matchRequestId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    // DB에서 값을 읽어올 때(=이미 존재하는 row)는 자동으로 isNew를 false로 바꿔줌
    @PostLoad
    @PrePersist
    void markNotNew() {
        this.isNew = false;
    }

    // 조건 수정: 코스, 만나는 곳, 거리 범위, 페이스 범위를 한 번에 변경
    // matchRequest는 파라미터에 없음 - PK와 직렬 된 값이라 절대 바뀌지 않음
    public void changeCondition(RunningCourse runningCourse, String meetingPoint, Integer distanceMinMeters, Integer distanceMaxMeters, Integer paceMinSec, Integer paceMaxSec) {
        this.runningCourse = runningCourse;
        this.meetingPoint = meetingPoint;
        this.distanceMinMeters = distanceMinMeters;
        this.distanceMaxMeters = distanceMaxMeters;
        this.paceMinSec = paceMinSec;
        this.paceMaxSec = paceMaxSec;
    }
}
