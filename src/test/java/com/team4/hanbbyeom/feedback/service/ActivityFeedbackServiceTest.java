package com.team4.hanbbyeom.feedback.service;

import com.team4.hanbbyeom.feedback.domain.ActivityReview;
import com.team4.hanbbyeom.feedback.domain.NoShowReason;
import com.team4.hanbbyeom.feedback.domain.NoShowReport;
import com.team4.hanbbyeom.feedback.dto.NoShowReportCreateRequest;
import com.team4.hanbbyeom.feedback.dto.ReviewCreateRequest;
import com.team4.hanbbyeom.feedback.exception.FeedbackAlreadySubmittedException;
import com.team4.hanbbyeom.feedback.repository.ActivityReviewRepository;
import com.team4.hanbbyeom.feedback.repository.NoShowReportRepository;
import com.team4.hanbbyeom.matching.domain.AcceptStatus;
import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.MatchParticipant;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.trust.repository.TrustProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 후기·신고 저장이 DataIntegrityViolationException으로 실패했을 때 "이미 제출했어요"(409)로 바꾸는 범위를 확인한다.
// Spring은 SQLState 클래스 22(값이 너무 김 22001, 잘못된 바이트 22021 등)와 23(제약 위반)을 모두 같은 예외로 번역하므로,
// 전부 409로 바꾸면 NUL 문자 같은 다른 원인이 "이미 제출했어요"로 가려진다. 유니크 위반(23505)만 409여야 한다.
// 실제 중복 제출이 409가 되는지는 ActivityFeedbackApiIntegrationTest·ActivityFeedbackConcurrentSubmitTest가 실제 DB로 확인한다
class ActivityFeedbackServiceTest {

    private static final Long ACTIVITY_ID = 10L;
    private static final Long REVIEWER_ID = 1L;
    private static final Long REVIEWEE_ID = 2L;
    private static final Instant FIXED_NOW = Instant.parse("2026-09-19T12:00:00Z");

    private final ActivityMatchRepository activityMatchRepository = mock(ActivityMatchRepository.class);
    private final MatchParticipantRepository matchParticipantRepository = mock(MatchParticipantRepository.class);
    private final ActivityReviewRepository activityReviewRepository = mock(ActivityReviewRepository.class);
    private final NoShowReportRepository noShowReportRepository = mock(NoShowReportRepository.class);
    private final TrustProfileRepository trustProfileRepository = mock(TrustProfileRepository.class);
    private final ActivityFeedbackService service = new ActivityFeedbackService(
            activityMatchRepository, matchParticipantRepository, activityReviewRepository, noShowReportRepository,
            trustProfileRepository, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

    // 이미 끝난 확정 활동과 그 참가자 두 명을 준비한다(통합 테스트의 createActivityMatch()와 같은 방식)
    @BeforeEach
    void endedActivityWithTwoParticipants() {
        OffsetDateTime base = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).minusHours(2);
        ActivityMatch activityMatch = new ActivityMatch(
                base.plusMinutes(20), base.plusMinutes(30), TalkLevel.SILENT,
                "뚝섬 한강공원", "뚝섬 한강공원 코스", 5000, 12000,
                "뚝섬유원지역 3번 출구", 360, 400,
                base.plusMinutes(10), base
        );
        activityMatch.confirm("123456");
        activityMatch.end();
        when(activityMatchRepository.findByIdForUpdate(ACTIVITY_ID)).thenReturn(Optional.of(activityMatch));
        when(matchParticipantRepository.findByActivityMatchId(ACTIVITY_ID)).thenReturn(List.of(
                new MatchParticipant(ACTIVITY_ID, 100L, REVIEWER_ID, "A", AcceptStatus.ACCEPTED),
                new MatchParticipant(ACTIVITY_ID, 200L, REVIEWEE_ID, "B", AcceptStatus.ACCEPTED)));
    }

    private static DataIntegrityViolationException dbViolation(String sqlState) {
        return new DataIntegrityViolationException("db", new SQLException("db error", sqlState));
    }

    private void submitReview() {
        service.submitReview(ACTIVITY_ID, REVIEWER_ID, new ReviewCreateRequest(5, TalkLevel.SILENT, "좋았어요"));
    }

    private void submitNoShowReport() {
        service.submitNoShowReport(ACTIVITY_ID, REVIEWER_ID, new NoShowReportCreateRequest(NoShowReason.OTHER, "연락이 없었어요"));
    }

    @Test
    void 후기_유니크_위반은_이미_제출했다는_예외로_바꾼다() {
        when(activityReviewRepository.save(any(ActivityReview.class))).thenThrow(dbViolation("23505"));

        assertThatThrownBy(this::submitReview)
                .isInstanceOf(FeedbackAlreadySubmittedException.class)
                .hasMessage("이미 이 활동에 대한 후기 또는 신고를 제출했어요.");

        verifyNoInteractions(trustProfileRepository);
    }

    @Test
    void 후기_저장의_유니크_위반이_아닌_DB_오류는_409로_바꾸지_않고_그대로_던진다() {
        // 22001: 값이 너무 김, 22021: 잘못된 바이트(NUL 등), 23503: 외래 키, 23514: CHECK
        for (String sqlState : new String[]{"22001", "22021", "23503", "23514"}) {
            DataIntegrityViolationException notDuplicate = dbViolation(sqlState);
            when(activityReviewRepository.save(any(ActivityReview.class))).thenThrow(notDuplicate);

            assertThatThrownBy(this::submitReview).as("SQLState %s", sqlState).isSameAs(notDuplicate);
        }

        verifyNoInteractions(trustProfileRepository);
    }

    @Test
    void 신고_유니크_위반은_이미_제출했다는_예외로_바꾼다() {
        when(noShowReportRepository.save(any(NoShowReport.class))).thenThrow(dbViolation("23505"));

        assertThatThrownBy(this::submitNoShowReport)
                .isInstanceOf(FeedbackAlreadySubmittedException.class)
                .hasMessage("이미 이 활동에 대한 후기 또는 신고를 제출했어요.");

        verifyNoInteractions(trustProfileRepository);
    }

    @Test
    void 신고_저장의_유니크_위반이_아닌_DB_오류는_409로_바꾸지_않고_그대로_던진다() {
        for (String sqlState : new String[]{"22001", "22021", "23503", "23514"}) {
            DataIntegrityViolationException notDuplicate = dbViolation(sqlState);
            when(noShowReportRepository.save(any(NoShowReport.class))).thenThrow(notDuplicate);

            assertThatThrownBy(this::submitNoShowReport).as("SQLState %s", sqlState).isSameAs(notDuplicate);
        }

        verifyNoInteractions(trustProfileRepository);
    }

    @Test
    void SQL_원인이_없는_DataIntegrityViolation도_409로_바꾸지_않는다() {
        DataIntegrityViolationException unknown = new DataIntegrityViolationException("원인을 알 수 없음");
        when(activityReviewRepository.save(any(ActivityReview.class))).thenThrow(unknown);

        assertThatThrownBy(this::submitReview).isSameAs(unknown);
    }
}
