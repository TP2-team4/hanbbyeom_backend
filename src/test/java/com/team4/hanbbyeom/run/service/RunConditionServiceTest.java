package com.team4.hanbbyeom.run.service;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.MatchRequestStatus;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotFoundException;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotSearchingException;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.run.dto.RunConditionCreateRequest;
import com.team4.hanbbyeom.run.dto.RunConditionResponse;
import com.team4.hanbbyeom.run.dto.RunConditionUpdateRequest;
import com.team4.hanbbyeom.run.exception.RunMatchConditionNotFoundException;
import com.team4.hanbbyeom.run.repository.RunMatchConditionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 러닝 조건 서비스 통합 테스트
// 조건 등록 정상 처리 및 다양한 예외(존재하지 않는 코스/요청, 타인 소유 권한 예외, 페이스/거리 검증 실패) 케이스를 검증합니다.
@SpringBootTest
@Transactional
public class RunConditionServiceTest {

    @Autowired
    private RunConditionService runConditionService;
    @Autowired
    private RunMatchConditionRepository runMatchConditionRepository;
    @Autowired
    private MatchRequestRepository matchRequestRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 테스트 실행 시 공유할 데이터 식별자 변수
    private Long ownerUserId;     // 매칭 요청 소유자 유저 ID
    private Long otherUserId;     // 다른 유저 ID (권한 검증용)
    private Long matchRequestId;  // 사전 생성된 매칭 요청 ID
    private Long courseId;        // 시딩된 코스 ID ("뚝섬 한강공원")

    // 각 테스트 실행 전 필요한 기본 픽스처(유저, 코스, 매칭 요청) 데이터 생성 및 초기화
    @BeforeEach
    void setUp() {
        // 소유자와 타인 유저 생성
        ownerUserId = createUser("owner");
        otherUserId = createUser("other");

        // V5 시딩 데이터 중 '뚝섬 한강공원' 코스의 ID 조회
        courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");

        // 소유자 명의의 매칭 요청(MatchRequest) 사전 등록 및 ID 확보
        MatchRequest matchRequest = new MatchRequest(
                ownerUserId, OffsetDateTime.now().plusHours(5), TalkLevel.SILENT,
                OffsetDateTime.now().plusHours(4)
        );
        matchRequestId = matchRequestRepository.save(matchRequest).getId();
    }
    // users 테이블 제약 조건에 맞추어 임의의 테스트 유저 데이터를 직접 삽입하는 도우미 메서드
    private Long createUser(String label) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                label + "-" + System.nanoTime() + "@example.com", "dummy-hash", label, OffsetDateTime.now()
        );
    }
    // 정상적인 입력 값을 가진 기본 요청 DTO 생성 도우미 메서드
    private RunConditionCreateRequest validRequest() {
        return new RunConditionCreateRequest(
                matchRequestId, courseId, "뚝섬유원지역 3번 출구", 5000, 8000, 360, 400
        );
    }
    // 정상적인 조건 등록 시, DB에 저장되고 매칭 요청 ID가 반환되는지 검증
    @Test
    void 정상_등록하면_저장되고_아이디가_반환된다() {
        Long savedId = runConditionService.create(ownerUserId, validRequest());

        assertThat(savedId).isEqualTo(matchRequestId);
        assertThat(runMatchConditionRepository.findById(savedId)).isPresent();
    }
    // 존재하지 않는 매칭 요청 ID로 조건 등록 시, MatchRequestNotFoundException 발생 여부 검증
    @Test
    void 존재하지_않는_matchRequestId면_예외가_발생한다() {
        RunConditionCreateRequest request = new RunConditionCreateRequest(
                999_999L, courseId, "출구", 5000, 8000, 360, 400
        );

        assertThatThrownBy(() -> runConditionService.create(ownerUserId, request))
                .isInstanceOf(MatchRequestNotFoundException.class);
    }
    // 매칭 요청 소유자가 아닌 다른 유저가 조건 등록 시도 시, AccessDeniedException 발생 여부 검증
    @Test
    void 본인_소유가_아닌_matchRequest면_예외가_발생한다() {
        assertThatThrownBy(() -> runConditionService.create(otherUserId, validRequest()))
                .isInstanceOf(AccessDeniedException.class);
    }
    // 존재하지 않는 코스 ID로 조건 등록 시, IllegalArgumentException 발생 여부 검증
    @Test
    void 존재하지_않는_코스면_예외가_발생한다() {
        RunConditionCreateRequest request = new RunConditionCreateRequest(
                matchRequestId, 999_999L, "출구", 5000, 8000, 360, 400
        );

        assertThatThrownBy(() -> runConditionService.create(ownerUserId, request))
                .isInstanceOf(IllegalArgumentException.class);
    }
    // 거리 최소값이 허용 범위보다 작거나, 최대값이 허용 범위보다 크면 IllegalArgumentException 발생 여부 검증
    @Test
    void 거리가_허용_범위를_벗어나면_예외가_발생한다() {
        RunConditionCreateRequest request = new RunConditionCreateRequest(
                matchRequestId, courseId, "출구", 500, 8000, 360, 400 // 500m < 최소 1000m
        );

        assertThatThrownBy(() -> runConditionService.create(ownerUserId, request))
                .isInstanceOf(IllegalArgumentException.class);
    }
    // 거리 최소값이 최대값보다 크면 IllegalArgumentException 발생 여부 검증
    @Test
    void 거리_최소값이_최대값보다_크면_예외가_발생한다() {
        RunConditionCreateRequest request = new RunConditionCreateRequest(
                matchRequestId, courseId, "출구", 8000, 5000, 360, 400 // min > max
        );

        assertThatThrownBy(() -> runConditionService.create(ownerUserId, request))
                .isInstanceOf(IllegalArgumentException.class);
    }
    // 페이스 최소값이 허용 범위보다 작거나, 최대값이 허용 범위보다 크면 IllegalArgumentException 발생 여부 검증
    @Test
    void 페이스가_허용_범위를_벗어나면_예외가_발생한다() {
        RunConditionCreateRequest request = new RunConditionCreateRequest(
                matchRequestId, courseId, "출구", 5000, 8000, 200, 400 // 200초 < 최소 300초
        );

        assertThatThrownBy(() -> runConditionService.create(ownerUserId, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 유효한 수정 요청 DTO 생성 도우미 메서드
    private RunConditionUpdateRequest validUpdateRequest() {
        return new RunConditionUpdateRequest(
                courseId, "여의도 한강공원 2번 출구", 3000, 6000, 310, 380
        );
    }

    // 정상적인 조회 시, 등록된 조건 값이 그대로 반환되는지 검증
    @Test
    void 정상_조회하면_등록된_값이_반환된다() {
        runConditionService.create(ownerUserId, validRequest());

        RunConditionResponse response = runConditionService.getById(ownerUserId, matchRequestId);

        assertThat(response.matchRequestId()).isEqualTo(matchRequestId);
        assertThat(response.courseId()).isEqualTo(courseId);
        assertThat(response.meetingPoint()).isEqualTo("뚝섬유원지역 3번 출구");
    }

    // 존재하지 않는 id로 조회 시, RunMatchConditionNotFoundException 발생 여부 검증
    @Test
    void 존재하지_않는_id로_조회하면_예외가_발생한다() {
        assertThatThrownBy(() -> runConditionService.getById(ownerUserId, 999_999L))
                .isInstanceOf(RunMatchConditionNotFoundException.class);
    }

    // 타인 소유의 조건을 조회 시도하면, AccessDeniedException 발생 여부 검증
    @Test
    void 타인_소유_조건을_조회하면_예외가_발생한다() {
        runConditionService.create(ownerUserId, validRequest());

        assertThatThrownBy(() -> runConditionService.getById(otherUserId, matchRequestId))
                .isInstanceOf(AccessDeniedException.class);
    }

    // 정상적인 수정 시, 값이 실제로 반영되는지 검증
    @Test
    void 정상_수정하면_값이_반영된다() {
        runConditionService.create(ownerUserId, validRequest());

        runConditionService.update(ownerUserId, matchRequestId, validUpdateRequest());

        RunConditionResponse response = runConditionService.getById(ownerUserId, matchRequestId);
        assertThat(response.meetingPoint()).isEqualTo("여의도 한강공원 2번 출구");
        assertThat(response.distanceMinMeters()).isEqualTo(3000);
        assertThat(response.distanceMaxMeters()).isEqualTo(6000);
        assertThat(response.paceMinSec()).isEqualTo(310);
        assertThat(response.paceMaxSec()).isEqualTo(380);
    }

    // 존재하지 않는 id를 수정 시도하면, RunMatchConditionNotFoundException 발생 여부 검증
    @Test
    void 존재하지_않는_id를_수정하면_예외가_발생한다() {
        assertThatThrownBy(() -> runConditionService.update(ownerUserId, 999_999L, validUpdateRequest()))
                .isInstanceOf(RunMatchConditionNotFoundException.class);
    }

    // 타인 소유의 조건을 수정 시도하면, AccessDeniedException 발생 여부 검증
    @Test
    void 타인_소유_조건을_수정하면_예외가_발생한다() {
        runConditionService.create(ownerUserId, validRequest());

        assertThatThrownBy(() -> runConditionService.update(otherUserId, matchRequestId, validUpdateRequest()))
                .isInstanceOf(AccessDeniedException.class);
    }

    // 허용 범위를 벗어난 값으로 수정 시도하면, IllegalArgumentException 발생 여부 검증
    @Test
    void 범위를_벗어난_값으로_수정하면_예외가_발생한다() {
        runConditionService.create(ownerUserId, validRequest());
        RunConditionUpdateRequest request = new RunConditionUpdateRequest(
                courseId, "출구", 500, 8000, 360, 400 // 500m < 최소 1000m
        );

        assertThatThrownBy(() -> runConditionService.update(ownerUserId, matchRequestId, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 정상적인 삭제 시, 조건이 삭제되고 매칭 요청 상태가 CANCELLED로 바뀌는지 검증
    @Test
    void 정상_삭제하면_조건이_삭제되고_매칭요청이_CANCELLED로_바뀐다() {
        runConditionService.create(ownerUserId, validRequest());

        runConditionService.delete(ownerUserId, matchRequestId);

        assertThat(runMatchConditionRepository.findById(matchRequestId)).isEmpty();
        MatchRequest matchRequest = matchRequestRepository.findById(matchRequestId).orElseThrow();
        assertThat(matchRequest.getStatus()).isEqualTo(MatchRequestStatus.CANCELLED);
    }

    // 존재하지 않는 id를 삭제 시도하면, RunMatchConditionNotFoundException 발생 여부 검증
    @Test
    void 존재하지_않는_id를_삭제하면_예외가_발생한다() {
        assertThatThrownBy(() -> runConditionService.delete(ownerUserId, 999_999L))
                .isInstanceOf(RunMatchConditionNotFoundException.class);
    }

    // 타인 소유의 조건을 삭제 시도하면, AccessDeniedException 발생 여부 검증
    @Test
    void 타인_소유_조건을_삭제하면_예외가_발생한다() {
        runConditionService.create(ownerUserId, validRequest());

        assertThatThrownBy(() -> runConditionService.delete(otherUserId, matchRequestId))
                .isInstanceOf(AccessDeniedException.class);
    }

    // SEARCHING 상태가 아닌 매칭 요청의 조건을 삭제 시도하면, MatchRequestNotSearchingException 발생 여부 검증
    @Test
    void SEARCHING_상태가_아니면_삭제하면_예외가_발생한다() {
        runConditionService.create(ownerUserId, validRequest());
        MatchRequest matchRequest = matchRequestRepository.findById(matchRequestId).orElseThrow();
        matchRequest.changeStatus(MatchRequestStatus.MATCHED);

        assertThatThrownBy(() -> runConditionService.delete(ownerUserId, matchRequestId))
                .isInstanceOf(MatchRequestNotSearchingException.class);
    }
    // 이미 신청이 들어와(SEARCHING이 아님) 있는 게시글의 조건을 수정하려 하면 거부되는지 검증
    @Test
    void SEARCHING_상태가_아니면_수정_시도시_예외가_발생한다() {
        runConditionService.create(ownerUserId, validRequest());

        MatchRequest matchRequest = matchRequestRepository.findById(matchRequestId).orElseThrow();
        matchRequest.changeStatus(MatchRequestStatus.PENDING_CONFIRMATION);

        assertThatThrownBy(() -> runConditionService.update(ownerUserId, matchRequestId, validUpdateRequest()))
                .isInstanceOf(IllegalStateException.class);
    }
}