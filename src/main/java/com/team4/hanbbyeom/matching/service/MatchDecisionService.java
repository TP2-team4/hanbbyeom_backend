package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.domain.*;
import com.team4.hanbbyeom.matching.dto.MatchConfirmResponse;
import com.team4.hanbbyeom.matching.exception.*;
import com.team4.hanbbyeom.matching.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;

// 호스트의 수락/거절, 그리고 시스템의 자동 만료 처리를 담당. MatchApplyService(신청자 액션)와
// 책임을 나눠서, "누가 주체인 액션인지"로 서비스를 분리했다.
@Service
public class MatchDecisionService {

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
                .orElseThrow(() -> new NotMatchParticipantException("존재하지 않는 매칭이에요."));

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
                .orElseThrow(() -> new NotMatchParticipantException("존재하지 않는 매칭이에요."));

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
            activityMatch.expire();

            var participants = matchParticipantRepository.findByActivityMatchId(activityMatch.getId());
            MatchParticipant host = participants.stream()
                    .filter(p -> "A".equals(p.getSlot()))
                    .findFirst()
                    .orElseThrow(() -> new NotMatchParticipantException("존재하지 않는 매칭이에요."));
            MatchParticipant applicant = participants.stream()
                    .filter(p -> "B".equals(p.getSlot()))
                    .findFirst()
                    .orElseThrow(() -> new NotMatchParticipantException("존재하지 않는 매칭이에요."));

            // reject()와 동일하게 매칭이 무산됐으므로 두 참여 연결 모두 해제
            host.release();
            applicant.release();

            transitionBothRequests(host, applicant, MatchRequestStatus.SEARCHING);
        }
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
