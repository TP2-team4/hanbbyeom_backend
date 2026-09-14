package com.team4.hanbbyeom.run.repository;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.run.domain.RunMatchCondition;
import com.team4.hanbbyeom.run.domain.RunningCourse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
public class RunMatchConditionRepositoryTest {

    @Autowired
    private RunMatchConditionRepository runMatchConditionRepository;

    @Autowired
    private RunningCourseRepository runningCourseRepository;

    @Autowired
    private MatchRequestRepository matchRequestRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RunningCourse testCourse;
    private MatchRequest testMatchRequest;

    @BeforeEach
    void setUp() {
        // users 테이블 CHECK 제약(chk_users_account_lifecycle) 때문에 활성 계정 필드를 다 채워서 넣음
        Long testUserId = jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname, email_verified_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                "condition-test-" + System.nanoTime() + "@example.com",
                "dummy-hash",
                "조건테스트유저",
                OffsetDateTime.now()
        );

        // V5에서 이미 시딩된 코스 중 하나를 가져다 씀
        testCourse = runningCourseRepository.findAll().get(0);

        // matchRequest는 반드시 먼저 save()해서 실제 ID를 가진 상태여야 함
        testMatchRequest = matchRequestRepository.save(
                new MatchRequest(
                        testUserId,
                        OffsetDateTime.now().plusHours(5),
                        TalkLevel.SILENT,
                        OffsetDateTime.now().plusHours(4)
                )
        );
    }

    @Test
    public void 저장하고_아이디로_다시_조회할_수_있다() {
        RunMatchCondition condition = RunMatchCondition.builder()
                .matchRequest(testMatchRequest)
                .runningCourse(testCourse)
                .distanceMinMeters(5000)
                .distanceMaxMeters(8000)
                .meetingPoint("뚝섬유원지역 3번 출구")
                .paceMinSec(360)
                .paceMaxSec(400)
                .build();

        RunMatchCondition saved = runMatchConditionRepository.save(condition);
        RunMatchCondition found = runMatchConditionRepository.findById(saved.getMatchRequestId()).orElseThrow();

        assertThat(found.getMatchRequestId()).isEqualTo(testMatchRequest.getId());
        assertThat(found.getMatchRequest().getId()).isEqualTo(testMatchRequest.getId());
        assertThat(found.getRunningCourse().getId()).isEqualTo(testCourse.getId());
        assertThat(found.getMeetingPoint()).isEqualTo("뚝섬유원지역 3번 출구");
        assertThat(found.getDistanceMinMeters()).isEqualTo(5000);
        assertThat(found.getDistanceMaxMeters()).isEqualTo(8000);
        assertThat(found.getPaceMinSec()).isEqualTo(360);
        assertThat(found.getPaceMaxSec()).isEqualTo(400);
    }
}