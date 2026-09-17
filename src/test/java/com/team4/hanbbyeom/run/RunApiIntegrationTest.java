package com.team4.hanbbyeom.run;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.dto.MatchBoardItemResponse;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.matching.service.MatchRequestBoardService;
import com.team4.hanbbyeom.run.dto.RunConditionCreateRequest;
import com.team4.hanbbyeom.run.dto.RunConditionResponse;
import com.team4.hanbbyeom.run.dto.RunConditionUpdateRequest;
import com.team4.hanbbyeom.run.dto.RunCourseResponse;
import com.team4.hanbbyeom.run.exception.RunMatchConditionNotFoundException;
import com.team4.hanbbyeom.run.service.RunConditionService;
import com.team4.hanbbyeom.run.service.RunCourseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// #18: Run API 통합 테스트 및 C파트 연동 확인
// 개별 API 단위가 아니라, 사용자 시나리오처럼 이어지는 흐름과
// Matching 도메인(C파트)이 run_match_condition 데이터를 정상 참조하는지를 검증한다.
@SpringBootTest
@Transactional
public class RunApiIntegrationTest {

    @Autowired
    private RunCourseService runCourseService;
    @Autowired
    private RunConditionService runConditionService;
    @Autowired
    private MatchRequestBoardService matchRequestBoardService;
    @Autowired
    private MatchRequestRepository matchRequestRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long ownerUserId;
    private Long otherUserId;
    private Long matchRequestId;
    private Long courseId;

    @BeforeEach
    void setUp() {
        ownerUserId = createUser("owner");
        otherUserId = createUser("other");

        courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");

        MatchRequest matchRequest = new MatchRequest(
                ownerUserId, OffsetDateTime.now().plusHours(5), TalkLevel.SILENT,
                OffsetDateTime.now().plusHours(4)
        );
        matchRequestId = matchRequestRepository.save(matchRequest).getId();
    }

    private Long createUser(String label) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                label + "-" + System.nanoTime() + "@example.com", "dummy-hash", label, OffsetDateTime.now()
        );
    }

    // 1. 전체 플로우: 코스 조회 -> 조건 등록 -> 조회 -> 수정 -> 삭제
    @Test
    void 전체_플로우가_이어서_정상_동작한다() {
        // 코스 조회
        List<RunCourseResponse> courses = runCourseService.getCourses();
        RunCourseResponse selectedCourse = courses.stream()
                .filter(c -> c.name().equals("뚝섬 한강공원"))
                .findFirst()
                .orElseThrow();

        // 조건 등록
        RunConditionCreateRequest createRequest = new RunConditionCreateRequest(
                matchRequestId, selectedCourse.id(), "뚝섬유원지 3번 출구", 5000, 8000, 360, 400
        );
        Long createdId = runConditionService.create(ownerUserId, createRequest);
        assertThat(createdId).isEqualTo(matchRequestId);

        // 조회
        RunConditionResponse created = runConditionService.getById(ownerUserId, createdId);
        assertThat(created.courseName()).isEqualTo("뚝섬 한강공원");
        assertThat(created.meetingPoint()).isEqualTo("뚝섬유원지 3번 출구");

        // 수정
        RunConditionUpdateRequest updateRequest = new RunConditionUpdateRequest(
                selectedCourse.id(), "여의도 2번 출구", 3000, 6000, 310, 380
        );
        runConditionService.update(ownerUserId, createdId, updateRequest);

        RunConditionResponse updated = runConditionService.getById(ownerUserId, createdId);
        assertThat(updated.meetingPoint()).isEqualTo("여의도 2번 출구");
        assertThat(updated.distanceMinMeters()).isEqualTo(3000);
        assertThat(updated.distanceMaxMeters()).isEqualTo(6000);

        // 삭제
        runConditionService.delete(ownerUserId, createdId);
        assertThatThrownBy(() -> runConditionService.getById(ownerUserId, createdId))
                .isInstanceOf(RunMatchConditionNotFoundException.class);
    }

    // 2. C파트 연동 확인: 모집 게시판 목록 조회(searchBoard)에서 run_match_condition 데이터가 정상 조회되는지
    @Test
    void 모집게시판_목록에_러닝조건_데이터가_정상_연동된다() {
        RunConditionCreateRequest createRequest = new RunConditionCreateRequest(
                matchRequestId, courseId, "뚝섬유원지 3번 출구", 5000, 8000, 360, 400
        );
        runConditionService.create(ownerUserId, createRequest);

        // currentUserId를 소유자가 아닌 다른 유저로 조회해야 목록에 포함됨(본인 글은 항상 제외)
        List<MatchBoardItemResponse> board = matchRequestBoardService.getBoard(
                null, null, null, null, null, null, null, otherUserId
        );

        MatchBoardItemResponse item = board.stream()
                .filter(row -> row.id().equals(matchRequestId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("모집 게시판 목록에서 등록한 조건을 찾지 못했습니다."));

        assertThat(item.courseName()).isEqualTo("뚝섬 한강공원");
        assertThat(item.distanceMinMeters()).isEqualTo(5000);
        assertThat(item.distanceMaxMeters()).isEqualTo(8000);
        assertThat(item.paceMinSec()).isEqualTo(360);
        assertThat(item.paceMaxSec()).isEqualTo(400);
    }

    // 3. 경계값 테스트: 거리/페이스가 허용 범위의 정확한 경계값이면 정상 등록되어야 함
    @Test
    void 거리와_페이스가_정확히_경계값이면_정상_등록된다() {
        RunConditionCreateRequest boundaryRequest = new RunConditionCreateRequest(
                matchRequestId, courseId, "출구", 1000, 20000, 300, 450
        );

        Long createdId = runConditionService.create(ownerUserId, boundaryRequest);

        RunConditionResponse response = runConditionService.getById(ownerUserId, createdId);
        assertThat(response.distanceMinMeters()).isEqualTo(1000);
        assertThat(response.distanceMaxMeters()).isEqualTo(20000);
        assertThat(response.paceMinSec()).isEqualTo(300);
        assertThat(response.paceMaxSec()).isEqualTo(450);
    }
}
