package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.domain.AcceptStatus;
import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.ActivityMatchStatus;
import com.team4.hanbbyeom.matching.domain.MatchParticipant;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.MatchRequestStatus;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.event.ActivityCancelledEvent;
import com.team4.hanbbyeom.matching.exception.ActivityMatchNotFoundException;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotSearchingException;
import com.team4.hanbbyeom.matching.exception.NotMatchParticipantException;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 확정된 활동 참여 취소(ActivityCancelService)의 락 순서·검증 순서·상태 전이·이벤트 발행을 검증한다(이슈 #109).
// 도메인 객체는 실제 객체를 쓰고 저장소·시계·이벤트 발행기만 대체한다.
class ActivityCancelServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-19T10:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

    private static final long MATCH_ID = 7L;
    private static final long HOST_ID = 1L;
    private static final long APPLICANT_ID = 2L;
    private static final long OUTSIDER_ID = 3L;
    private static final long HOST_POST_ID = 11L;
    private static final long APPLICANT_POST_ID = 22L;

    private final ActivityMatchRepository activityMatchRepository = mock(ActivityMatchRepository.class);
    private final MatchParticipantRepository matchParticipantRepository = mock(MatchParticipantRepository.class);
    private final MatchRequestRepository matchRequestRepository = mock(MatchRequestRepository.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ActivityCancelService service = new ActivityCancelService(
            activityMatchRepository, matchParticipantRepository, matchRequestRepository, jdbcTemplate,
            eventPublisher, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

    private ActivityMatch match;
    private MatchParticipant host;
    private MatchParticipant applicant;
    private MatchRequest hostPost;
    private MatchRequest applicantPost;

    @BeforeEach
    void setUp() {
        // 활동은 내일 시작하는 확정 매칭. 두 게시글은 확정으로 MATCHED이고 신청자도 본인 게시글이 있다
        match = confirmedMatch(NOW.plusDays(1));
        host = new MatchParticipant(MATCH_ID, HOST_POST_ID, HOST_ID, "A", AcceptStatus.ACCEPTED);
        applicant = new MatchParticipant(MATCH_ID, APPLICANT_POST_ID, APPLICANT_ID, "B", AcceptStatus.ACCEPTED);
        hostPost = matchedPost(HOST_ID);
        applicantPost = matchedPost(APPLICANT_ID);

        when(activityMatchRepository.findById(MATCH_ID)).thenReturn(Optional.of(match));
        when(matchParticipantRepository.findByActivityMatchId(MATCH_ID)).thenReturn(List.of(host, applicant));
        when(matchRequestRepository.findById(HOST_POST_ID)).thenReturn(Optional.of(hostPost));
        when(matchRequestRepository.findById(APPLICANT_POST_ID)).thenReturn(Optional.of(applicantPost));
    }

    private ActivityMatch confirmedMatch(OffsetDateTime scheduledAt) {
        ActivityMatch activityMatch = new ActivityMatch(
                scheduledAt, scheduledAt.plusHours(2), TalkLevel.LIGHT_CHAT, "만나는 곳", "뚝섬 한강공원",
                5000, 8000, "코스 설명", 360, 400, scheduledAt.minusHours(2), scheduledAt.minusDays(1));
        activityMatch.confirm("123456");
        return activityMatch;
    }

    private MatchRequest matchedPost(Long userId) {
        MatchRequest post = new MatchRequest(userId, NOW.plusDays(1), TalkLevel.LIGHT_CHAT, NOW.plusHours(23));
        post.changeStatus(MatchRequestStatus.MATCHED);
        return post;
    }

    // 매칭이 어떤 이유로든 바뀌지 않았고 아무도 알림을 받지 않았는지 — 거부된 요청은 아무 흔적도 남기면 안 된다
    private void assertNothingChanged(ActivityMatchStatus expectedStatus) {
        assertThat(match.getStatus()).isEqualTo(expectedStatus);
        assertThat(match.getClosedByUserId()).isNull();
        assertThat(host.getReleasedAt()).isNull();
        assertThat(applicant.getReleasedAt()).isNull();
        assertThat(hostPost.getStatus()).isEqualTo(MatchRequestStatus.MATCHED);
        assertThat(applicantPost.getStatus()).isEqualTo(MatchRequestStatus.MATCHED);
        verifyNoInteractions(eventPublisher);
    }

    // 다른 상태 전이(신청·수락·거절·취소·탈퇴 정리)와 같은 matching_mutex 락을 먼저 잡아야 한다. 락 없이 읽으면 동시에
    // 취소하는 두 참가자가 둘 다 CONFIRMED로 읽고 통과해 취소 메시지가 두 번 남는 등 순서가 섞인다.
    @Test
    void 매칭을_읽기_전에_matching_mutex_락을_먼저_잡는다() {
        service.cancel(HOST_ID, MATCH_ID);

        InOrder order = inOrder(jdbcTemplate, activityMatchRepository);
        order.verify(jdbcTemplate).queryForObject(contains("matching_mutex"), eq(Long.class));
        order.verify(activityMatchRepository).findById(MATCH_ID);
    }

    @Test
    void 호스트가_취소하면_매칭이_취소되고_참가자가_해제되며_호스트_게시글은_취소되고_신청자_게시글은_다시_모집한다() {
        service.cancel(HOST_ID, MATCH_ID);

        assertThat(match.getStatus()).isEqualTo(ActivityMatchStatus.CANCELLED);
        assertThat(match.getClosedByUserId()).isEqualTo(HOST_ID); // 활동 이력의 cancelledBy(ME/COUNTERPART) 구분에 필요
        assertThat(match.getClosedAt()).isNotNull();
        assertThat(match.getConfirmedAt()).isNotNull(); // 채팅 기록 조회가 confirmedAt에 의존하므로 유지
        assertThat(host.getReleasedAt()).isNotNull();
        assertThat(applicant.getReleasedAt()).isNotNull();
        assertThat(hostPost.getStatus()).isEqualTo(MatchRequestStatus.CANCELLED);
        assertThat(applicantPost.getStatus()).isEqualTo(MatchRequestStatus.SEARCHING);
        verify(eventPublisher).publishEvent(new ActivityCancelledEvent(MATCH_ID, HOST_ID));
    }

    @Test
    void 신청자가_취소하면_신청자_게시글은_취소되고_호스트_게시글은_다시_모집한다() {
        service.cancel(APPLICANT_ID, MATCH_ID);

        assertThat(match.getStatus()).isEqualTo(ActivityMatchStatus.CANCELLED);
        assertThat(match.getClosedByUserId()).isEqualTo(APPLICANT_ID);
        assertThat(host.getReleasedAt()).isNotNull();
        assertThat(applicant.getReleasedAt()).isNotNull();
        assertThat(applicantPost.getStatus()).isEqualTo(MatchRequestStatus.CANCELLED);
        assertThat(hostPost.getStatus()).isEqualTo(MatchRequestStatus.SEARCHING);
        verify(eventPublisher).publishEvent(new ActivityCancelledEvent(MATCH_ID, APPLICANT_ID));
    }

    // 신청자는 본인 게시글 없이도 신청할 수 있어 match_participant.match_request_id가 NULL일 수 있다
    @Test
    void 본인_게시글_없이_신청한_신청자가_취소해도_호스트_게시글만_다시_모집한다() {
        MatchParticipant applicantWithoutPost = new MatchParticipant(
                MATCH_ID, null, APPLICANT_ID, "B", AcceptStatus.ACCEPTED);
        when(matchParticipantRepository.findByActivityMatchId(MATCH_ID))
                .thenReturn(List.of(host, applicantWithoutPost));

        service.cancel(APPLICANT_ID, MATCH_ID);

        assertThat(match.getClosedByUserId()).isEqualTo(APPLICANT_ID);
        assertThat(hostPost.getStatus()).isEqualTo(MatchRequestStatus.SEARCHING);
        assertThat(host.getReleasedAt()).isNotNull();
        assertThat(applicantWithoutPost.getReleasedAt()).isNotNull();
    }

    @Test
    void 호스트가_취소해도_신청자에게_본인_게시글이_없으면_호스트_게시글만_취소한다() {
        MatchParticipant applicantWithoutPost = new MatchParticipant(
                MATCH_ID, null, APPLICANT_ID, "B", AcceptStatus.ACCEPTED);
        when(matchParticipantRepository.findByActivityMatchId(MATCH_ID))
                .thenReturn(List.of(host, applicantWithoutPost));

        service.cancel(HOST_ID, MATCH_ID);

        assertThat(hostPost.getStatus()).isEqualTo(MatchRequestStatus.CANCELLED);
        assertThat(applicantWithoutPost.getReleasedAt()).isNotNull();
    }

    @Test
    void 존재하지_않는_매칭이면_404_예외이고_아무것도_바꾸지_않는다() {
        when(activityMatchRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(HOST_ID, 999L))
                .isInstanceOf(ActivityMatchNotFoundException.class);

        verifyNoInteractions(matchParticipantRepository, matchRequestRepository, eventPublisher);
    }

    @Test
    void 참가자가_아니면_거절되고_아무것도_바꾸지_않는다() {
        assertThatThrownBy(() -> service.cancel(OUTSIDER_ID, MATCH_ID))
                .isInstanceOf(NotMatchParticipantException.class);

        assertNothingChanged(ActivityMatchStatus.CONFIRMED);
    }

    // 참가자 검증이 상태 검증보다 먼저다 — 제3자에게 매칭의 상태(취소·종료 여부)가 드러나면 안 된다
    @Test
    void 참가자가_아니면_이미_종료된_매칭이어도_상태가_아니라_참가자_오류가_먼저다() {
        ReflectionTestUtils.setField(match, "status", ActivityMatchStatus.ENDED);

        assertThatThrownBy(() -> service.cancel(OUTSIDER_ID, MATCH_ID))
                .isInstanceOf(NotMatchParticipantException.class);
    }

    @Test
    void 확정_전인_매칭은_신청_취소나_거절을_안내하며_거절된다() {
        ReflectionTestUtils.setField(match, "status", ActivityMatchStatus.PROPOSED);

        assertThatThrownBy(() -> service.cancel(HOST_ID, MATCH_ID))
                .isInstanceOf(MatchRequestNotSearchingException.class)
                .hasMessageContaining("확정되지 않은");

        assertNothingChanged(ActivityMatchStatus.PROPOSED);
    }

    // 이미 취소된 활동을 다시 취소하는 요청(더블 클릭, 상대가 먼저 취소)은 409로 거절되고 채팅 메시지가 또 남지 않아야 한다
    @ParameterizedTest
    @EnumSource(value = ActivityMatchStatus.class, names = {"REJECTED", "EXPIRED", "CANCELLED", "ENDED"})
    void 이미_끝난_매칭은_거절된다(ActivityMatchStatus status) {
        ReflectionTestUtils.setField(match, "status", status);

        assertThatThrownBy(() -> service.cancel(HOST_ID, MATCH_ID))
                .isInstanceOf(MatchRequestNotSearchingException.class)
                .hasMessageContaining("이미 취소되었거나 종료된");

        assertNothingChanged(status);
    }

    // 시작 후 취소를 허용하면 나오지 않은 사람이 취소로 바꿔 노쇼 신고를 피할 수 있다(CANCELLED는 후기·신고 대상이 아니다)
    @Test
    void 활동이_이미_시작됐으면_거절된다() {
        match = confirmedMatch(NOW.minusMinutes(30)); // 30분 전에 시작, 아직 종료 전
        when(activityMatchRepository.findById(MATCH_ID)).thenReturn(Optional.of(match));

        assertThatThrownBy(() -> service.cancel(HOST_ID, MATCH_ID))
                .isInstanceOf(MatchRequestNotSearchingException.class)
                .hasMessageContaining("이미 시작된");

        assertNothingChanged(ActivityMatchStatus.CONFIRMED);
    }

    // 경계: 시작 시각과 정확히 같은 순간부터 취소할 수 없다(시작 전 = now < scheduledAt)
    @Test
    void 시작_시각과_정확히_같은_순간은_거절되고_1초_전은_허용된다() {
        match = confirmedMatch(NOW);
        when(activityMatchRepository.findById(MATCH_ID)).thenReturn(Optional.of(match));
        assertThatThrownBy(() -> service.cancel(HOST_ID, MATCH_ID))
                .isInstanceOf(MatchRequestNotSearchingException.class)
                .hasMessageContaining("이미 시작된");

        match = confirmedMatch(NOW.plusSeconds(1));
        when(activityMatchRepository.findById(MATCH_ID)).thenReturn(Optional.of(match));
        service.cancel(HOST_ID, MATCH_ID);
        assertThat(match.getStatus()).isEqualTo(ActivityMatchStatus.CANCELLED);
    }

    // 예정 종료 시각이 지났지만 1분 주기 스케줄러가 아직 ENDED로 바꾸지 못한 건은 시작 후이므로 함께 걸러진다
    @Test
    void 종료_시각이_지났지만_아직_확정으로_남은_매칭도_거절된다() {
        match = confirmedMatch(NOW.minusHours(3)); // 3시간 전 시작, 2시간짜리라 종료 시각도 지남
        when(activityMatchRepository.findById(MATCH_ID)).thenReturn(Optional.of(match));

        assertThatThrownBy(() -> service.cancel(APPLICANT_ID, MATCH_ID))
                .isInstanceOf(MatchRequestNotSearchingException.class)
                .hasMessageContaining("이미 시작된");

        assertNothingChanged(ActivityMatchStatus.CONFIRMED);
    }
}
