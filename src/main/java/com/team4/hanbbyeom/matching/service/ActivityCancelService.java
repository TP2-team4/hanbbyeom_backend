package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.MatchParticipant;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.MatchRequestStatus;
import com.team4.hanbbyeom.matching.event.ActivityCancelledEvent;
import com.team4.hanbbyeom.matching.exception.ActivityMatchNotFoundException;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotFoundException;
import com.team4.hanbbyeom.matching.exception.MatchRequestNotSearchingException;
import com.team4.hanbbyeom.matching.exception.NotMatchParticipantException;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

// 확정(CONFIRMED)된 활동에서 참가자가 직접 빠지는 "활동 참여 취소"(이슈 #109). 채팅 화면의
// "참여가 어려워졌어요 → 활동 참여 취소" 버튼이 호출한다.
//
// 확정 매칭이 정리되는 경로는 원래 자연 종료(MatchDecisionService.endOverdueActivities())와 회원 탈퇴
// (MatchDecisionService.closeMatchesOnWithdrawal())뿐이라, 사용자가 직접 빠질 방법이 없었다.
// MatchDecisionService는 다른 이슈에서도 함께 수정되는 파일이라 충돌을 피하려고 별도 서비스로 두었다.
@Service
public class ActivityCancelService {

    private static final Logger log = LoggerFactory.getLogger(ActivityCancelService.class);

    private final ActivityMatchRepository activityMatchRepository;
    private final MatchParticipantRepository matchParticipantRepository;
    private final MatchRequestRepository matchRequestRepository;
    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;
    // 취소 가능 시각 판정은 TimeConfig의 Clock 빈으로만 한다 (테스트에서 시계 고정 가능, #94)
    private final Clock clock;

    public ActivityCancelService(ActivityMatchRepository activityMatchRepository,
                                 MatchParticipantRepository matchParticipantRepository,
                                 MatchRequestRepository matchRequestRepository,
                                 JdbcTemplate jdbcTemplate,
                                 ApplicationEventPublisher eventPublisher,
                                 Clock clock) {
        this.activityMatchRepository = activityMatchRepository;
        this.matchParticipantRepository = matchParticipantRepository;
        this.matchRequestRepository = matchRequestRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    // 호스트·신청자 누구든 자기 참여를 취소할 수 있다. 결과:
    //   - activity_match: CONFIRMED → CANCELLED, closed_by_user_id에 취소한 사용자를 남긴다. 활동 이력 API가 이 값으로
    //     취소 주체(cancelledBy: ME/COUNTERPART)를 구분한다.
    //   - 두 참가자 모두 release() — 안 하면 uq_participant_active_user에 걸려 두 사람 다 다른 매칭에 참여할 수 없다.
    //   - 게시글: 취소한 사람의 것은 CANCELLED(작성자가 취소함), 상대의 것은 SEARCHING(다시 모집). 탈퇴 정리의
    //     "활동 시작 전" 규칙과 같다(MatchDecisionService.closeActiveMatchByWithdrawal()). 신청자는 본인 게시글
    //     없이 신청할 수 있어(match_request_id가 NULL) 있을 때만 바꾼다.
    //   - 채팅: 취소 메시지를 같은 트랜잭션에서 남긴다(ActivityCancelledChatListener).
    // 노쇼가 아니라 사전 취소이므로 no_show_report_count는 건드리지 않는다(탈퇴 정리와 같다).
    //
    // 다른 상태 전이와 같이 matching_mutex를 먼저 잡고, 404 → 참가자 검증(403) → 상태·시각 검증(409) 순서로 확인한다.
    @Transactional
    public void cancel(Long userId, Long activityMatchId) {
        // 락을 잡은 뒤에 읽어야, 이미 커밋된 다른 취소·탈퇴 정리·종료 처리의 결과를 정확히 본다(동시에 두 참가자가
        // 취소하면 뒤의 요청은 앞의 결과를 보고 409가 된다).
        jdbcTemplate.queryForObject("SELECT id FROM matching_mutex WHERE id = 1 FOR UPDATE", Long.class);

        ActivityMatch activityMatch = activityMatchRepository.findById(activityMatchId)
                .orElseThrow(() -> new ActivityMatchNotFoundException("존재하지 않는 매칭이에요."));

        List<MatchParticipant> participants = matchParticipantRepository.findByActivityMatchId(activityMatchId);
        MatchParticipant canceller = participants.stream()
                .filter(p -> p.getUserId().equals(userId))
                .findFirst()
                .orElseThrow(() -> new NotMatchParticipantException("이 매칭의 참가자만 활동을 취소할 수 있어요."));

        ensureCancellable(activityMatch);

        MatchParticipant host = findBySlot(participants, "A")
                .orElseThrow(() -> new NotMatchParticipantException("존재하지 않는 매칭이에요."));
        MatchParticipant applicant = findBySlot(participants, "B")
                .orElseThrow(() -> new NotMatchParticipantException("아직 신청자가 없어요."));
        MatchParticipant counterpart = "A".equals(canceller.getSlot()) ? applicant : host;

        activityMatch.cancelByParticipant(userId);
        host.release();
        applicant.release();

        // 취소한 사람의 게시글은 닫고, 상대의 게시글은 다시 모집 중으로 돌려 상대가 바로 새 상대를 찾게 한다
        changeRequestStatus(canceller, MatchRequestStatus.CANCELLED);
        changeRequestStatus(counterpart, MatchRequestStatus.SEARCHING);

        eventPublisher.publishEvent(new ActivityCancelledEvent(activityMatchId, userId));

        log.info("활동 참여 취소: activityMatchId={}, 취소한 사용자 slot={}", activityMatchId, canceller.getSlot());
    }

    // 취소할 수 있는 건 "확정됐고 아직 시작 전인" 활동뿐이다.
    //   - 확정 전(PROPOSED): 신청자는 신청 취소, 호스트는 거절을 쓴다.
    //   - 시작 후: 후기·노쇼 신고는 CONFIRMED/ENDED만 받는다(ActivityFeedbackService). 시작 후 취소를 허용하면
    //     나오지 않은 사람이 취소로 바꿔 노쇼 신고를 피할 수 있으므로, 시작 이후 불참은 노쇼 신고로 다룬다.
    // 예정 종료 시각이 지났는데 스케줄러가 아직 ENDED로 바꾸지 못한 건도 시작 후이므로 함께 걸러진다.
    private void ensureCancellable(ActivityMatch activityMatch) {
        switch (activityMatch.getStatus()) {
            case CONFIRMED -> { }
            case PROPOSED -> throw new MatchRequestNotSearchingException(
                    "아직 확정되지 않은 매칭이에요. 신청 취소나 거절을 이용해주세요.");
            default -> throw new MatchRequestNotSearchingException("이미 취소되었거나 종료된 활동이에요.");
        }
        if (!OffsetDateTime.now(clock).isBefore(activityMatch.getScheduledAt())) {
            throw new MatchRequestNotSearchingException("이미 시작된 활동은 취소할 수 없어요.");
        }
    }

    private Optional<MatchParticipant> findBySlot(List<MatchParticipant> participants, String slot) {
        return participants.stream()
                .filter(p -> slot.equals(p.getSlot()))
                .findFirst();
    }

    // 신청자는 본인 게시글 없이 신청할 수 있어 matchRequestId가 null일 수 있다
    private void changeRequestStatus(MatchParticipant participant, MatchRequestStatus newStatus) {
        Long matchRequestId = participant.getMatchRequestId();
        if (matchRequestId == null) {
            return;
        }
        MatchRequest matchRequest = matchRequestRepository.findById(matchRequestId)
                .orElseThrow(() -> new MatchRequestNotFoundException(matchRequestId));
        matchRequest.changeStatus(newStatus);
    }
}
