package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.MatchRequestStatus;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.dto.MatchRequestCreateRequest;
import com.team4.hanbbyeom.matching.dto.MatchRequestUpdateRequest;
import com.team4.hanbbyeom.matching.exception.AlreadyHasActiveMatchRequestException;
import com.team4.hanbbyeom.matching.exception.InvalidMatchRequestException;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotFoundException;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotSearchingException;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.run.service.RunConditionService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MatchRequestCommandServiceTest {

    // 서비스가 TimeConfig의 Clock 빈으로 "지금"을 읽으므로(#94), 테스트에서는 고정 시계를 넣어
    // 실행 시점과 무관하게 항상 같은 결과가 나오게 한다.
    private static final Instant FIXED_NOW = Instant.parse("2026-09-19T10:00:00Z");
    private final Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private final MatchRequestRepository matchRequestRepository = mock(MatchRequestRepository.class);
    private final RunConditionService runConditionService = mock(RunConditionService.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MatchRequestCommandService service =
            new MatchRequestCommandService(matchRequestRepository, runConditionService, jdbcTemplate, fixedClock);

    @Test
    void 시작_시각이_너무_임박하면_예외가_발생한다() {
        MatchRequestCreateRequest request = new MatchRequestCreateRequest(
                1L,
                "뚝섬유원지역 3번 출구",
                7000,
                9000,
                360,
                400,
                OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).plusMinutes(30), // 최소 리드타임(3시간)보다 훨씬 임박함
                TalkLevel.SILENT
        );

        assertThatThrownBy(() -> service.create(1L, request))
                .isInstanceOf(InvalidMatchRequestException.class);
    }

    // ---- 모집글 취소 상태 검증 (이슈 #100) ---------------------------------------------------------
    // 모집 중(SEARCHING)인 게시글만 취소할 수 있다. 이미 신청이 걸렸거나 확정된 게시글을 취소하면 게시글만 CANCELLED가 되고
    // activity_match/참가자는 정리되지 않아 어긋나므로 409로 거부하고 상태는 그대로 둔다.

    private static final Long OWNER_ID = 1L;
    private static final Long POST_ID = 10L;

    private MatchRequest postWithStatus(MatchRequestStatus status) {
        MatchRequest post = new MatchRequest(
                OWNER_ID, OffsetDateTime.now().plusHours(48), TalkLevel.LIGHT_CHAT, OffsetDateTime.now().plusHours(9));
        post.changeStatus(status);
        when(matchRequestRepository.findById(POST_ID)).thenReturn(Optional.of(post));
        return post;
    }

    @Test
    void 모집_중인_게시글은_취소된다() {
        MatchRequest post = postWithStatus(MatchRequestStatus.SEARCHING);

        service.cancel(OWNER_ID, POST_ID);

        assertThat(post.getStatus()).isEqualTo(MatchRequestStatus.CANCELLED);
    }

    @Test
    void 신청이_진행_중인_게시글은_취소할_수_없고_먼저_거절하라고_안내한다() {
        MatchRequest post = postWithStatus(MatchRequestStatus.PENDING_CONFIRMATION);

        assertThatThrownBy(() -> service.cancel(OWNER_ID, POST_ID))
                .isInstanceOf(MatchRequestNotSearchingException.class)
                .hasMessage("신청이 진행 중인 모집글이에요. 신청을 먼저 거절한 뒤 취소해주세요.");
        assertThat(post.getStatus()).isEqualTo(MatchRequestStatus.PENDING_CONFIRMATION);
    }

    @Test
    void 확정된_게시글은_취소할_수_없다() {
        MatchRequest post = postWithStatus(MatchRequestStatus.MATCHED);

        assertThatThrownBy(() -> service.cancel(OWNER_ID, POST_ID))
                .isInstanceOf(MatchRequestNotSearchingException.class)
                .hasMessage("이미 확정된 매칭이 있는 모집글은 취소할 수 없어요.");
        assertThat(post.getStatus()).isEqualTo(MatchRequestStatus.MATCHED);
    }

    @Test
    void 이미_취소되었거나_마감된_게시글은_다시_취소할_수_없다() {
        for (MatchRequestStatus status : new MatchRequestStatus[]{
                MatchRequestStatus.CANCELLED, MatchRequestStatus.EXPIRED, MatchRequestStatus.CLOSED}) {
            MatchRequest post = postWithStatus(status);

            assertThatThrownBy(() -> service.cancel(OWNER_ID, POST_ID))
                    .isInstanceOf(MatchRequestNotSearchingException.class)
                    .hasMessage("이미 마감되었거나 취소된 모집글이에요.");
            assertThat(post.getStatus()).isEqualTo(status);
        }
    }

    // 소유권(404/403)이 상태 검사보다 먼저여야 한다 — 본인 게시글이 아닌 사용자가 409 메시지로 상태를 알아내지 못하게.
    @Test
    void 본인_게시글이_아니면_상태와_무관하게_403이다() {
        postWithStatus(MatchRequestStatus.PENDING_CONFIRMATION);

        assertThatThrownBy(() -> service.cancel(999L, POST_ID))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void 존재하지_않는_게시글은_404다() {
        when(matchRequestRepository.findById(POST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(OWNER_ID, POST_ID))
                .isInstanceOf(MatchRequestNotFoundException.class);
    }

    // 취소도 신청·수락·거절과 같은 matching_mutex 락을 잡아야 하고, 게시글을 읽기 전에 잡아야 한다.
    // 락 없이 먼저 읽으면 apply()가 게시글을 PENDING_CONFIRMATION으로 바꾸는 것과 동시에 SEARCHING으로 읽고 통과한다.
    @Test
    void 취소는_게시글을_읽기_전에_matching_mutex_락을_먼저_잡는다() {
        postWithStatus(MatchRequestStatus.SEARCHING);

        service.cancel(OWNER_ID, POST_ID);

        InOrder order = inOrder(jdbcTemplate, matchRequestRepository);
        order.verify(jdbcTemplate).queryForObject(contains("matching_mutex"), eq(Long.class));
        order.verify(matchRequestRepository).findById(POST_ID);
    }

    // ---- 모집글 수정 락 ----------------------------------------------------------------------------
    // 수정도 신청·수락·거절·취소와 같은 matching_mutex 락을 잡아야 한다. 락 없이 읽으면 apply()가 게시글을
    // PENDING_CONFIRMATION으로 바꿔 커밋한 뒤에도 낡은 SEARCHING으로 통과해, 커밋 시점에 status까지 되돌린다.

    private static final OffsetDateTime UPDATE_SCHEDULED_AT = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).plusDays(2);

    @Test
    void 수정은_게시글을_읽기_전에_matching_mutex_락을_먼저_잡는다() {
        postWithStatus(MatchRequestStatus.SEARCHING);

        service.update(OWNER_ID, POST_ID, new MatchRequestUpdateRequest(UPDATE_SCHEDULED_AT, TalkLevel.SILENT));

        InOrder order = inOrder(jdbcTemplate, matchRequestRepository);
        order.verify(jdbcTemplate).queryForObject(contains("matching_mutex"), eq(Long.class));
        order.verify(matchRequestRepository).findById(POST_ID);
    }

    @Test
    void 모집_중인_게시글은_수정된다() {
        MatchRequest post = postWithStatus(MatchRequestStatus.SEARCHING);

        service.update(OWNER_ID, POST_ID, new MatchRequestUpdateRequest(UPDATE_SCHEDULED_AT, TalkLevel.SILENT));

        assertThat(post.getScheduledAt()).isEqualTo(UPDATE_SCHEDULED_AT);
        assertThat(post.getStatus()).isEqualTo(MatchRequestStatus.SEARCHING);
    }

    @Test
    void 신청이_걸린_게시글은_수정할_수_없고_일정과_상태가_바뀌지_않는다() {
        MatchRequest post = postWithStatus(MatchRequestStatus.PENDING_CONFIRMATION);
        OffsetDateTime before = post.getScheduledAt();

        assertThatThrownBy(() -> service.update(OWNER_ID, POST_ID, new MatchRequestUpdateRequest(UPDATE_SCHEDULED_AT, TalkLevel.SILENT)))
                .isInstanceOf(MatchRequestNotSearchingException.class);

        assertThat(post.getScheduledAt()).isEqualTo(before);
        assertThat(post.getStatus()).isEqualTo(MatchRequestStatus.PENDING_CONFIRMATION);
    }

    @Test
    void 수정도_본인_게시글이_아니면_403이다() {
        postWithStatus(MatchRequestStatus.SEARCHING);

        assertThatThrownBy(() -> service.update(999L, POST_ID, new MatchRequestUpdateRequest(UPDATE_SCHEDULED_AT, TalkLevel.SILENT)))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---- 일정 최대 허용 시점 (정책 상수 MAX_LEAD_DAYS, 등록·수정에 동일 적용) ---------------------------
    // 고정 시계라 경계(정확히 최대 시점 / 1초 초과)를 결정적으로 확인할 수 있다. 검증은 DB에 닿기 전에 끝나야 한다:
    // DB가 저장하지 못하는 범위(연도 999999999)가 들어오면 예전에는 등록이 "이미 진행 중인 모집글" 409로, 수정이 500으로 나갔다.

    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
    private static final OffsetDateTime EXTREME_FUTURE = OffsetDateTime.parse("+999999999-12-31T23:59:59Z");
    private static final String TOO_FAR_MESSAGE =
            "활동 시작 시각은 지금부터 최대 " + MatchRequestCommandService.MAX_LEAD_DAYS + "일 이내여야 해요.";

    private MatchRequestCreateRequest createRequestAt(OffsetDateTime scheduledAt) {
        return new MatchRequestCreateRequest(1L, "뚝섬유원지역 3번 출구", 7000, 9000, 360, 400, scheduledAt, TalkLevel.SILENT);
    }

    @Test
    void 최대_허용_시점_정확히는_등록된다() {
        when(matchRequestRepository.save(any(MatchRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(1L, createRequestAt(NOW.plusDays(MatchRequestCommandService.MAX_LEAD_DAYS)));

        verify(matchRequestRepository).save(any(MatchRequest.class));
    }

    @Test
    void 최대_허용_시점을_1초라도_넘으면_예외이고_아무것도_저장하지_않는다() {
        assertThatThrownBy(() -> service.create(1L, createRequestAt(
                NOW.plusDays(MatchRequestCommandService.MAX_LEAD_DAYS).plusSeconds(1))))
                .isInstanceOf(InvalidMatchRequestException.class)
                .hasMessage(TOO_FAR_MESSAGE);

        verifyNoInteractions(matchRequestRepository, runConditionService);
    }

    @Test
    void 극단적인_미래_일정도_예외이고_아무것도_저장하지_않는다() {
        assertThatThrownBy(() -> service.create(1L, createRequestAt(EXTREME_FUTURE)))
                .isInstanceOf(InvalidMatchRequestException.class)
                .hasMessage(TOO_FAR_MESSAGE);

        verifyNoInteractions(matchRequestRepository, runConditionService);
    }

    @Test
    void 수정도_최대_허용_시점_정확히는_통과한다() {
        MatchRequest post = postWithStatus(MatchRequestStatus.SEARCHING);
        OffsetDateTime edge = NOW.plusDays(MatchRequestCommandService.MAX_LEAD_DAYS);

        service.update(OWNER_ID, POST_ID, new MatchRequestUpdateRequest(edge, TalkLevel.SILENT));

        assertThat(post.getScheduledAt()).isEqualTo(edge);
    }

    @Test
    void 수정도_최대_허용_시점을_넘으면_예외이고_기존_일정이_유지된다() {
        for (OffsetDateTime tooFar : new OffsetDateTime[]{
                NOW.plusDays(MatchRequestCommandService.MAX_LEAD_DAYS).plusSeconds(1), EXTREME_FUTURE}) {
            MatchRequest post = postWithStatus(MatchRequestStatus.SEARCHING);
            OffsetDateTime before = post.getScheduledAt();

            assertThatThrownBy(() -> service.update(OWNER_ID, POST_ID, new MatchRequestUpdateRequest(tooFar, TalkLevel.SILENT)))
                    .isInstanceOf(InvalidMatchRequestException.class)
                    .hasMessage(TOO_FAR_MESSAGE);

            assertThat(post.getScheduledAt()).isEqualTo(before);
            assertThat(post.getStatus()).isEqualTo(MatchRequestStatus.SEARCHING);
        }
    }

    // ---- 등록의 DB 예외 변환 범위 -------------------------------------------------------------------
    // Spring은 SQLState 클래스 22(데이터 예외)와 23(무결성 제약 위반)을 모두 DataIntegrityViolationException으로 번역한다.
    // 그 예외를 전부 "이미 진행 중인 글이 있음"(409)으로 바꾸면 충돌이 아닌 오류까지 409로 잘못 안내한다.
    // 활성 글 유니크 인덱스 위반(23505)일 때만 409로 바꾸고, 나머지는 그대로 던져 500으로 드러낸다.

    private static DataIntegrityViolationException dbViolation(String sqlState) {
        return new DataIntegrityViolationException("db", new SQLException("db error", sqlState));
    }

    @Test
    void 활성_글_유니크_위반은_이미_진행_중인_글_예외로_바꾼다() {
        when(matchRequestRepository.save(any(MatchRequest.class))).thenThrow(dbViolation("23505"));

        assertThatThrownBy(() -> service.create(1L, createRequestAt(NOW.plusDays(2))))
                .isInstanceOf(AlreadyHasActiveMatchRequestException.class)
                .hasMessage("이미 진행 중인 모집글 또는 신청이 있어요.");

        verifyNoInteractions(runConditionService);
    }

    @Test
    void 유니크_위반이_아닌_DB_오류는_409로_바꾸지_않고_그대로_던진다() {
        // 22008: 날짜 범위 초과, 22001: 값이 너무 김, 22021: 잘못된 바이트(NUL 등), 23502: NOT NULL 위반
        for (String sqlState : new String[]{"22008", "22001", "22021", "23502"}) {
            DataIntegrityViolationException notConflict = dbViolation(sqlState);
            when(matchRequestRepository.save(any(MatchRequest.class))).thenThrow(notConflict);

            assertThatThrownBy(() -> service.create(1L, createRequestAt(NOW.plusDays(2))))
                    .as("SQLState %s", sqlState)
                    .isSameAs(notConflict);
        }
    }

    @Test
    void SQL_원인이_없는_DataIntegrityViolation도_409로_바꾸지_않는다() {
        DataIntegrityViolationException unknown = new DataIntegrityViolationException("원인을 알 수 없음");
        when(matchRequestRepository.save(any(MatchRequest.class))).thenThrow(unknown);

        assertThatThrownBy(() -> service.create(1L, createRequestAt(NOW.plusDays(2))))
                .isSameAs(unknown);
    }
}
