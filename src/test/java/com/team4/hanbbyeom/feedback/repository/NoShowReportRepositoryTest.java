package com.team4.hanbbyeom.feedback.repository;

import com.team4.hanbbyeom.feedback.domain.NoShowReason;
import com.team4.hanbbyeom.feedback.domain.NoShowReport;
import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
public class NoShowReportRepositoryTest {

    @Autowired
    private NoShowReportRepository noShowReportRepository;

    @Autowired
    private ActivityMatchRepository activityMatchRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long reporterUserId;
    private Long reportedUserId;
    private Long activityMatchId;

    @BeforeEach
    void setUp() {
        reporterUserId = insertTestUser("reporter");
        reportedUserId = insertTestUser("reported");

        ActivityMatch savedMatch = activityMatchRepository.save(
                new ActivityMatch(
                        OffsetDateTime.now().plusMinutes(20),
                        OffsetDateTime.now().plusMinutes(30),
                        TalkLevel.SILENT,
                        "뚝섬 한강공원",
                        "뚝섬 한강공원 코스",
                        5000,
                        12000,
                        "뚝섬유원지역 3번 출구",
                        360,
                        400,
                        OffsetDateTime.now().plusMinutes(10),
                        OffsetDateTime.now()
                )
        );
        activityMatchId = savedMatch.getId();
    }

    private Long insertTestUser(String label) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname, email_verified_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                "report-test-" + label + "-" + System.nanoTime() + "@example.com",
                "dummy-hash",
                label + "테스트유저",
                OffsetDateTime.now()
        );
    }

    @Test
    public void 저장하고_중복_여부를_확인할_수_있다() {
        assertThat(noShowReportRepository.existsByActivityMatchIdAndReporterUserId(
                activityMatchId, reporterUserId)).isFalse();

        noShowReportRepository.save(new NoShowReport(
                activityMatchId, reporterUserId, reportedUserId,
                NoShowReason.NOT_SHOWED_UP, null
        ));

        assertThat(noShowReportRepository.existsByActivityMatchIdAndReporterUserId(
                activityMatchId, reporterUserId)).isTrue();
    }

    @Test
    public void 배치로_신고한_활동_id만_골라낼_수_있다() {
        noShowReportRepository.save(new NoShowReport(
                activityMatchId, reporterUserId, reportedUserId,
                NoShowReason.LEFT_WITHOUT_NOTICE, "상세 내용"
        ));

        List<Long> reported = noShowReportRepository.findActivityMatchIdByReporterUserIdAndActivityMatchIdIn(
                reporterUserId, List.of(activityMatchId, 999_999L)
        );

        assertThat(reported).containsExactly(activityMatchId);
    }
}