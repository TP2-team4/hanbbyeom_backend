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
import com.team4.hanbbyeom.run.dto.RunConditionCreateRequest;
import com.team4.hanbbyeom.run.service.RunConditionService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

@Service
public class MatchRequestCommandService {

    private static final long MIN_LEAD_HOURS = 3;
    private static final long SEARCH_WINDOW_HOURS = 1;

    private final MatchRequestRepository matchRequestRepository;
    private final RunConditionService runConditionService; // 새로 주입
    private final JdbcTemplate jdbcTemplate; // 취소 시 matching_mutex 락을 잡기 위해 사용
    // 등록 최소 리드타임 판정은 TimeConfig의 Clock 빈으로만 한다 (테스트에서 시계 고정 가능, #94)
    private final Clock clock;

    public MatchRequestCommandService(MatchRequestRepository matchRequestRepository,
                                      RunConditionService runConditionService,
                                      JdbcTemplate jdbcTemplate,
                                      Clock clock) {
        this.matchRequestRepository = matchRequestRepository;
        this.runConditionService = runConditionService;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    // 모집글(match_request)을 SEARCHING 상태로 새로 만들고, 같은 트랜잭션 안에서 러닝 조건까지
    // 등록한다. 유니크 제약(uq_match_request_active_user) 위반은 "이미 활성 게시글/신청이 있음"
    // 예외로 변환해 사용자에게 원인을 알려준다.
    @Transactional
    public Long create(Long userId, MatchRequestCreateRequest request) {
        validateScheduledAt(request.scheduledAt());
        OffsetDateTime searchExpiresAt = request.scheduledAt().minusHours(SEARCH_WINDOW_HOURS);

        MatchRequest matchRequest = new MatchRequest(
                userId,
                request.scheduledAt(),
                TalkLevel.valueOf(request.talkLevel()),
                searchExpiresAt
        );

        Long matchRequestId;
        try {
            matchRequestId = matchRequestRepository.save(matchRequest).getId();
        } catch (DataIntegrityViolationException e) {
            throw new AlreadyHasActiveMatchRequestException("이미 진행 중인 모집글 또는 신청이 있어요.");
        }

        // 코스·거리·페이스·만나는 곳 4개는 B의 RunConditionService가 검증(범위/코스 존재 여부)과
        // 저장까지 전담. C는 더 이상 raw SQL로 직접 run_match_condition에 쓰지 않음.
        // matchRequest가 IDENTITY 전략이라 save() 시점에 이미 INSERT가 실행돼 있고,
        // 같은 트랜잭션(같은 영속성 컨텍스트)이라 아래 호출의 findById()가 바로 찾아냄 — flush 필요 없음.
        runConditionService.create(userId, new RunConditionCreateRequest(
                matchRequestId,
                request.courseId(),
                request.meetingPoint(),
                request.distanceMinMeters(),
                request.distanceMaxMeters(),
                request.paceMinSec(),
                request.paceMaxSec()
        ));

        return matchRequestId;
    }

    // 일정(scheduledAt)·대화 수준만 수정한다 — 코스/거리/페이스/만나는 곳은 RunConditionService
    // (팀원 B) 담당이라 여기서 다루지 않는다. 상태 전이는 없고(SEARCHING 유지), SEARCHING이
    // 아닌 글을 수정하려 하면 MatchRequest.changeConditions()가 IllegalStateException을 던진다.
    @Transactional
    public void update(Long userId, Long matchRequestId, MatchRequestUpdateRequest request) {
        MatchRequest matchRequest = getOwnedMatchRequest(userId, matchRequestId, "수정");
        validateScheduledAt(request.scheduledAt());
        OffsetDateTime searchExpiresAt = request.scheduledAt().minusHours(SEARCH_WINDOW_HOURS);
        matchRequest.changeConditions(request.scheduledAt(), TalkLevel.valueOf(request.talkLevel()), searchExpiresAt);
    }

    // 게시글을 CANCELLED로 전이시켜 모집 탭에서 내린다. 모집 중(SEARCHING)인 게시글만 취소할 수 있다(이슈 #100).
    //
    // 예전에는 상태를 검사하지 않아, 이미 신청이 걸린 PENDING_CONFIRMATION이나 확정된 MATCHED 게시글도 취소됐다.
    // 그러면 게시글만 CANCELLED가 되고 activity_match/match_participant는 정리되지 않아 두 가지가 어긋났다.
    //  - 신청 대기 중 취소: 응답 기한이 지나 expireOverdue()가 돌면 취소한 게시글이 SEARCHING으로 되살아난다
    //  - 확정 후 취소: 활동과 참가자가 그대로 남아 상대가 묶이고, 종료되면 취소한 활동이 완료로 집계된다
    // 이미 신청이 걸렸다면 호스트가 먼저 신청을 거절(MatchDecisionService.reject)해 게시글이 SEARCHING으로 돌아온 뒤에
    // 취소해야 한다. 확정된 매칭을 사용자가 직접 취소하는 기능은 아직 없다(제품 결정이 필요해 별도 이슈).
    //
    // 신청·수락·거절·만료·탈퇴 정리와 같이 matching_mutex 락을 먼저 잡는다. 락이 없으면 apply()가 게시글을
    // PENDING_CONFIRMATION으로 바꾸는 것과 동시에 들어온 취소가 둘 다 SEARCHING으로 읽고 통과해, 상태 검사가 있어도
    // 같은 불일치가 남는다. 락을 잡은 뒤에 게시글을 읽으므로 이미 커밋된 신청의 결과를 정확히 본다.
    // 소유권 검증(404/403)을 상태 검사보다 앞에 둔다 — 본인 게시글이 아닌 사용자가 상태를 알아내지 못하게 하기 위함.
    @Transactional
    public void cancel(Long userId, Long matchRequestId) {
        jdbcTemplate.queryForObject("SELECT id FROM matching_mutex WHERE id = 1 FOR UPDATE", Long.class);

        MatchRequest matchRequest = getOwnedMatchRequest(userId, matchRequestId, "내리");
        if (matchRequest.getStatus() != MatchRequestStatus.SEARCHING) {
            throw new MatchRequestNotSearchingException(notCancellableMessage(matchRequest.getStatus()));
        }
        matchRequest.changeStatus(MatchRequestStatus.CANCELLED);
    }

    // 취소할 수 없는 상태별로 호스트가 다음에 무엇을 해야 하는지 알려준다.
    private String notCancellableMessage(MatchRequestStatus status) {
        return switch (status) {
            case PENDING_CONFIRMATION -> "신청이 진행 중인 모집글이에요. 신청을 먼저 거절한 뒤 취소해주세요.";
            case MATCHED -> "이미 확정된 매칭이 있는 모집글은 취소할 수 없어요.";
            // CANCELLED/EXPIRED/CLOSED. SEARCHING은 취소 가능하므로 여기 오지 않는다.
            default -> "이미 마감되었거나 취소된 모집글이에요.";
        };
    }

    // update()/cancel() 공용 — 게시글을 찾아 존재 여부(404)와 소유권(403)을 함께 검증한다.
    private MatchRequest getOwnedMatchRequest(Long userId, Long matchRequestId, String actionForMessage) {
        MatchRequest matchRequest = matchRequestRepository.findById(matchRequestId)
                .orElseThrow(() -> new MatchRequestNotFoundException(matchRequestId));
        if (!matchRequest.isOwnedBy(userId)) {
            throw new AccessDeniedException("본인 게시글만 " + actionForMessage + "할 수 있어요.");
        }
        return matchRequest;
    }

    // create()/update() 공용 — 활동 시작 시각이 너무 임박하면 (scheduledAt - 1시간으로 계산되는)
    // searchExpiresAt이 created_at보다 앞서버려 DB 제약(chk_match_request_time)을 위반하게 되므로,
    // 저장 전에 미리 걸러서 500 대신 400으로 응답한다.
    // (참고: 등록 최소 리드타임과 신청 가능 기한은 서로 다른 정책이다 — 신청 시 호스트 응답
    // 기한은 MatchApplyService.apply()가 "최대 24시간, 단 활동 시작 1시간 전을 넘지 않도록"
    // 동적으로 계산해서 여기 MIN_LEAD_HOURS와 무관하게 항상 유효한 신청 가능 구간을 보장한다
    // — 한때 이 값을 24시간보다 크게 고정하는 방식으로 고쳤었으나, 당일 등록·매칭이라는
    // 핵심 시나리오를 막아버려 되돌렸다.)
    private void validateScheduledAt(OffsetDateTime scheduledAt) {
        OffsetDateTime minAllowed = OffsetDateTime.now(clock).plusHours(MIN_LEAD_HOURS);
        if (scheduledAt.isBefore(minAllowed)) {
            throw new InvalidMatchRequestException(
                    "활동 시작 시각은 지금부터 최소 " + MIN_LEAD_HOURS + "시간 이후여야 해요."
            );
        }
    }
}