package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.domain.*;
import com.team4.hanbbyeom.matching.dto.MatchConfirmResponse;
import com.team4.hanbbyeom.matching.exception.*;
import com.team4.hanbbyeom.matching.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

// 호스트의 수락/거절, 그리고 시스템의 자동 만료 처리를 담당. MatchApplyService(신청자 액션)와
// 책임을 나눠서, "누가 주체인 액션인지"로 서비스를 분리했다.
@Service
public class MatchDecisionService {

    private static final Logger log = LoggerFactory.getLogger(MatchDecisionService.class);

    private final MatchRequestRepository matchRequestRepository;
    private final ActivityMatchRepository activityMatchRepository;
    private final MatchParticipantRepository matchParticipantRepository;
    private final JdbcTemplate jdbcTemplate;
    // 현장 확인 코드 생성용 — 향후 참석 인증 수단으로 쓰일 가능성을 고려해 예측 불가능한
    // SecureRandom을 사용한다(ThreadLocalRandom은 암호학적으로 안전하지 않음).
    private final SecureRandom secureRandom = new SecureRandom();

    public MatchDecisionService(MatchRequestRepository matchRequestRepository,
                                ActivityMatchRepository activityMatchRepository,
                                MatchParticipantRepository matchParticipantRepository,
                                JdbcTemplate jdbcTemplate) {
        this.matchRequestRepository = matchRequestRepository;
        this.activityMatchRepository = activityMatchRepository;
        this.matchParticipantRepository = matchParticipantRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    // 호스트가 신청을 수락 - CONFIRMED 전이 + meeting_code 발급 + 양쪽 게시글 MATCHED(신청자는 본인 게시글이 있을 때만)
    @Transactional
    public MatchConfirmResponse accept(Long hostUserId, Long activityMatchId) {
        // 1) matching_mutex 락 - apply()/cancelApplication()과 동일하게 동시 처리 경함 방지
        jdbcTemplate.queryForObject("SELECT id FROM matching_mutex WHERE id = 1 FOR UPDATE", Long.class);

        // 2) 매칭 건 조회
        ActivityMatch activityMatch = activityMatchRepository.findById(activityMatchId)
                .orElseThrow(() -> new ActivityMatchNotFoundException("존재하지 않는 매칭이에요."));

        // 3) 참가자 2명 조회 - 호스트(slot A)가 요청자 본인인지 검증, 신청자(slot B) 존재 검증
        var participants = matchParticipantRepository.findByActivityMatchId(activityMatchId);
        MatchParticipant host = participants.stream()
                .filter(p -> "A".equals(p.getSlot()) && p.getUserId().equals(hostUserId))
                .findFirst()
                .orElseThrow(() -> new NotMatchParticipantException("이 매칭의 호스트만 수락할 수 있어요."));
        MatchParticipant applicant = participants.stream()
                .filter(p -> "B".equals(p.getSlot()))
                .findFirst()
                .orElseThrow(() -> new NotMatchParticipantException("아직 신청자가 없어요."));

        // 4) 이미 응답했거나(CONFIRMED/REJECTED) 자동 만료(EXPIRED)된 건은 다시 수락할 수 없음.
        //    상태가 아직 PROPOSED라도 decisionExpiresAt이 이미 지났으면(스케줄러가 아직 안 돈
        //    사이) 수락을 막는다 — 안 그러면 기한 지난 매칭이 수락돼 CONFIRMED가 될 수 있음.
        ensureRespondable(activityMatch);

        // 5) 호스트 참여 상태 ACCEPTED로, activity_match를 CONFIRMED로 전이하며 현장 확인 코드 발급
        host.accept();
        String meetingCode = generateMeetingCode();
        activityMatch.confirm(meetingCode);

        // 6) 양쪽 게시글을 MATCHED로 (신청자 게시글은 본인 게시글이 있을 때만)
        transitionBothRequests(host, applicant, MatchRequestStatus.MATCHED);

        return new MatchConfirmResponse(activityMatch.getId(), meetingCode, activityMatch.getConfirmedAt());
    }

    // 호스트가 신청을 거절 — REJECTED 전이 + 양쪽 게시글 SEARCHING 복귀(신청자는 있을 때만).
    @Transactional
    public void reject(Long hostUserId, Long activityMatchId) {
        jdbcTemplate.queryForObject("SELECT id FROM matching_mutex WHERE id = 1 FOR UPDATE", Long.class);

        ActivityMatch activityMatch = activityMatchRepository.findById(activityMatchId)
                .orElseThrow(() -> new ActivityMatchNotFoundException("존재하지 않는 매칭이에요."));

        var participants = matchParticipantRepository.findByActivityMatchId(activityMatchId);
        MatchParticipant host = participants.stream()
                .filter(p -> "A".equals(p.getSlot()) && p.getUserId().equals(hostUserId))
                .findFirst()
                .orElseThrow(() -> new NotMatchParticipantException("이 매칭의 호스트만 거절할 수 있어요."));
        MatchParticipant applicant = participants.stream()
                .filter(p -> "B".equals(p.getSlot()))
                .findFirst()
                .orElseThrow(() -> new NotMatchParticipantException("아직 신청자가 없어요."));

        ensureRespondable(activityMatch);

        host.reject();
        activityMatch.reject(hostUserId);

        // 매칭이 무산됐으므로 두 참여 연결 모두 해제 — 안 하면 이 두 사람이 다시는
        // 매칭에 참여할 수 없게 된다(uq_participant_active_user 부분 유니크 인덱스 때문).
        host.release();
        applicant.release();

        transitionBothRequests(host, applicant, MatchRequestStatus.SEARCHING);
    }

    // 스케줄러(MatchExpireScheduler)가 주기적으로 호출 — 응답 기한이 지난 PROPOSED 건들을
    // 자동으로 EXPIRED 처리하고, 결과(게시글 복귀)는 거절과 동일하게 처리한다.
    @Transactional
    public void expireOverdue() {
        jdbcTemplate.queryForObject("SELECT id FROM matching_mutex WHERE id = 1 FOR UPDATE", Long.class);

        OffsetDateTime now = OffsetDateTime.now();
        var overdueMatches = activityMatchRepository.findByStatusAndDecisionExpiresAtBefore(
                ActivityMatchStatus.PROPOSED, now);

        for (ActivityMatch activityMatch : overdueMatches) {
            // 락을 잡은 이후에도 방금 호스트가 응답했을 수 있으니 방어적으로 재확인
            if (activityMatch.getStatus() != ActivityMatchStatus.PROPOSED) {
                continue;
            }

            // 참가자 존재 여부를 먼저 확인한다(상태 전이 전에!) — 원래는 activityMatch.expire()를
            // 먼저 호출한 뒤 참가자를 못 찾으면 예외를 던졌는데, 그러면 이 배치 하나가 통째로
            // 롤백되어(@Transactional) 같은 배치의 다른 정상 건들까지 매분 계속 처리 실패하는
            // 문제가 있었다(PR #57 리뷰 피드백). 참가자 데이터가 비정상인 건은 로그만 남기고
            // 건너뛰어(continue) 나머지 건은 정상 처리되도록 한다. 상태 전이도 참가자 확인 이후로
            // 미뤄서, 건너뛴 건이 PROPOSED로 남아 다음 스케줄러 실행 때 다시 시도되게 한다.
            var participants = matchParticipantRepository.findByActivityMatchId(activityMatch.getId());
            Optional<MatchParticipant> host = findBySlot(participants, "A");
            Optional<MatchParticipant> applicant = findBySlot(participants, "B");
            if (host.isEmpty() || applicant.isEmpty()) {
                log.warn("activityMatchId={} 자동 만료 처리 중 참가자 데이터 이상 발견(host 존재={}, " +
                                "applicant 존재={}) - 이 건은 건너뛰고 다음 배치에서 재시도합니다.",
                        activityMatch.getId(), host.isPresent(), applicant.isPresent());
                continue;
            }

            activityMatch.expire();

            // reject()와 동일하게 매칭이 무산됐으므로 두 참여 연결 모두 해제
            host.get().release();
            applicant.get().release();

            transitionBothRequests(host.get(), applicant.get(), MatchRequestStatus.SEARCHING);
        }
    }

    // 스케줄러(MatchExpireScheduler)가 주기적으로 호출 — 확정(CONFIRMED)된 활동 중
    // 예정 종료 시각(scheduled_end_at)이 지난 건들을 ENDED로 자연 종료 처리한다.
    // ⚠️ 이게 없으면 CONFIRMED로 끝난 매칭의 두 참가자는 released_at이 영원히 안 채워져서
    // uq_participant_active_user 제약에 걸려 이후 어떤 매칭에도 다시 참여할 수 없게 된다
    // (팀원 리뷰로 발견 — accept()가 의도적으로 release()를 안 부르는 대신, 활동이 끝나면
    // 반드시 이 메서드가 대신 풀어줘야 한다는 전제였는데 그 후속 처리가 빠져 있었음).
    @Transactional
    public void endOverdueActivities() {
        jdbcTemplate.queryForObject("SELECT id FROM matching_mutex WHERE id = 1 FOR UPDATE", Long.class);

        OffsetDateTime now = OffsetDateTime.now();
        var overdueMatches = activityMatchRepository.findByStatusAndScheduledEndAtBefore(
                ActivityMatchStatus.CONFIRMED, now);

        for (ActivityMatch activityMatch : overdueMatches) {
            // 락을 잡은 이후에도 방금 다른 트랜잭션이 처리했을 수 있으니 방어적으로 재확인
            if (activityMatch.getStatus() != ActivityMatchStatus.CONFIRMED) {
                continue;
            }

            // expireOverdue()와 동일한 이유로, 상태 전이 전에 참가자 존재부터 확인하고
            // 비정상인 건은 로그만 남기고 건너뛴다(continue) — 예외를 던지면 이 배치 전체가
            // 롤백돼 같은 배치의 다른 정상 건들까지 매분 계속 처리 실패하게 된다.
            var participants = matchParticipantRepository.findByActivityMatchId(activityMatch.getId());
            Optional<MatchParticipant> host = findBySlot(participants, "A");
            Optional<MatchParticipant> applicant = findBySlot(participants, "B");
            if (host.isEmpty() || applicant.isEmpty()) {
                log.warn("activityMatchId={} 자동 종료 처리 중 참가자 데이터 이상 발견(host 존재={}, " +
                                "applicant 존재={}) - 이 건은 건너뛰고 다음 배치에서 재시도합니다.",
                        activityMatch.getId(), host.isPresent(), applicant.isPresent());
                continue;
            }

            activityMatch.end();

            // 게시글을 CLOSED로 전이한다 — reject()/expireOverdue()처럼 SEARCHING으로 되돌릴
            // 이유는 없지만(활동이 정상적으로 끝난 것), MATCHED에 그대로 두면 안 된다. MATCHED는
            // uq_match_request_active_user(부분 유니크 인덱스)와 /requests/me 양쪽에서 여전히
            // "활성" 게시글로 취급돼서, 활동이 끝난 뒤에도 그 게시글이 /requests/me에 계속
            // 노출되고 사용자가 새 게시글을 등록하지도 못하게 된다(팀원 리뷰로 발견). release()로
            // 참가자 슬롯만 풀어주는 걸로는 이 문제를 못 막는다.
            host.get().release();
            applicant.get().release();
            transitionBothRequests(host.get(), applicant.get(), MatchRequestStatus.CLOSED);
        }
    }

    // expireOverdue()/endOverdueActivities()가 공통으로 쓰는 slot(A=호스트/B=신청자) 조회.
    private Optional<MatchParticipant> findBySlot(List<MatchParticipant> participants, String slot) {
        return participants.stream()
                .filter(p -> slot.equals(p.getSlot()))
                .findFirst();
    }

    // accept()/reject() 공통 — 응답 가능한 상태인지 검증한다. status가 PROPOSED가 아니거나
    // (이미 CONFIRMED/REJECTED/EXPIRED), 상태는 아직 PROPOSED라도 decisionExpiresAt이 이미
    // 지났으면(1분 주기 스케줄러가 아직 못 돈 사이) 응답을 막는다. 리뷰 피드백 반영:
    // 상태만 보고 시각을 안 보면 기한이 지난 매칭도 수락/거절될 수 있었음.
    private void ensureRespondable(ActivityMatch activityMatch) {
        boolean alreadyDecided = activityMatch.getStatus() != ActivityMatchStatus.PROPOSED;
        boolean deadlinePassed = !OffsetDateTime.now().isBefore(activityMatch.getDecisionExpiresAt());
        if (alreadyDecided || deadlinePassed) {
            throw new MatchRequestNotSearchingException("이미 응답했거나 종료된 매칭이에요.");
        }
    }

    // accept()/reject()/expireOverdue() 3곳에서 공통으로 쓰는 게시글 상태 전이 로직.
    // 호스트 게시글은 항상 전이하고, 신청자 게시글은 matchRequestId가 있을 때만 전이한다.
    // (신청자는 본인 게시글 없이도 신청할 수 있어 null일 수 있음 - MatchApplyService와 동일한 패턴)
    private void transitionBothRequests(MatchParticipant host, MatchParticipant applicant,
                                        MatchRequestStatus newStatus) {
        MatchRequest hostRequest = matchRequestRepository.findById(host.getMatchRequestId())
                .orElseThrow(() -> new MatchRequestNotFoundException(host.getMatchRequestId()));
        hostRequest.changeStatus(newStatus);

        Long applicantMatchRequestId = applicant.getMatchRequestId();
        if (applicantMatchRequestId != null) {
            MatchRequest applicantRequest = matchRequestRepository.findById(applicantMatchRequestId)
                    .orElseThrow(() -> new MatchRequestNotFoundException(applicantMatchRequestId));
            applicantRequest.changeStatus(newStatus);
        }
    }

    // 현장 확인용 6자리 코드 생성 ("000000"~"999999", 0으로 시작해도 유효)
    private String generateMeetingCode() {
        int code = secureRandom.nextInt(1_000_000);
        return String.format("%06d", code);
    }
}
