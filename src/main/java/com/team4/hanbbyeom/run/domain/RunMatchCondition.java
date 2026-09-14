package com.team4.hanbbyeom.run.domain;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import jakarta.persistence.*;
import lombok.*;


// 사용자가 러닝 매칭 조건으로 등록한 코스·거리·페이스·만나는 곳 정보를 저장하는 Entity
// match_request(모집 게시글) 1건당 정확히 1건만 존재하는 1:1 관계
@Entity
@Table(name = "run_match_condition")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RunMatchCondition {

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
    private RunningCourse runCourse;

    // 사용자가 슬라이더로 선택한 거리 범위(m). 코스 고정값이 아니라 매번 직접 선택하는 조건값
    private Integer distanceMinMeters;
    private Integer distanceMaxMeters;

    // 사용자가 직접 입력한 만남 장소
    private String meetingPoint;

    // 사용자가 슬라이더로 선택한 페이스 범위
    private Integer paceMinSec;
    private Integer paceMaxSec;

    // matchRequestId를 별도 파라미터로 받지 않는 이유:
    // @MapsId 관계이므로 matchRequest 객체 하나만 받아서 그 안의 ID를 그대로 꺼내 쓰면
    // PK와 연관관계가 항상 같은 값을 가리키도록 보장됨 (따로 값을 넘기면 둘이 어긋날 위험이 있음)
    @Builder
    public RunMatchCondition(MatchRequest matchRequest, RunningCourse runCourse, Integer distanceMinMeters, Integer distanceMaxMeters, String meetingPoint, Integer paceMinSec, Integer paceMaxSec) {
        this.matchRequest = matchRequest;
        this.matchRequestId = matchRequest.getId();
        this.runCourse = runCourse;
        this.distanceMinMeters = distanceMinMeters;
        this.distanceMaxMeters = distanceMaxMeters;
        this.meetingPoint = meetingPoint;
        this.paceMinSec = paceMinSec;
        this.paceMaxSec = paceMaxSec;
    }
}
