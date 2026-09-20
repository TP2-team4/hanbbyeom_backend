package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.MatchRequestStatus;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.dto.MatchRequestCreateRequest;
import com.team4.hanbbyeom.matching.exception.InvalidMatchRequestException;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotFoundException;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotSearchingException;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.run.service.RunConditionService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
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
                "SILENT"
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
}
