package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.domain.*;
import com.team4.hanbbyeom.matching.dto.MatchConfirmResponse;
import com.team4.hanbbyeom.matching.exception.*;
import com.team4.hanbbyeom.matching.repository.*;
import com.team4.hanbbyeom.trust.repository.TrustProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

// 호스트의 수락/거절, 그리고 시스템의 자동 만료·회원 탈퇴 시 정리 처리를 담당. MatchApplyService(신청자 액션)와
// 책임을 나눠서, "누가 주체인 액션인지"로 서비스를 분리했다.
@Service
public class MatchDecisionService {

    private static final Logger log = LoggerFactory.getLogger(MatchDecisionService.class);

    private final MatchRequestRepository matchRequestRepository;
    private final ActivityMatchRepository activityMatchRepository;
    private final MatchParticipantRepository matchParticipantRepository;
    // matching_mutex 잠금과 users 탈퇴 여부 조회에만 쓴다 — trust_profile 쓰기는 더 이상 여기서 하지 않는다
    private final JdbcTemplate jdbcTemplate;
    // 완료 활동 수 집계는 trust 도메인(TrustProfileRepository)이 소유한다 (이슈 #94)
    private final TrustProfileRepository trustProfileRepository;
    // 시각 판단(응답 기한 경과, 활동 종료 여부 등)은 TimeConfig의 Clock 빈을 통해서만 한다 —
    // 테스트에서 시계를 고정할 수 있게 하기 위함 (채팅 도메인과 동일한 방식, #94)
    private final Clock clock;
    // 현장 확인 코드 생성용 — 향후 참석 인증 수단으로 쓰일 가능성을 고려해 예측 불가능한
    // SecureRandom을 사용한다(ThreadLocalRandom은 암호학적으로 안전하지 않음).
    private final SecureRandom secureRandom = new SecureRandom();

    public MatchDecisionService(MatchRequestRepository matchRequestRepository,
                                ActivityMatchRepository activityMatchRepository,
                                MatchParticipantRepository matchParticipantRepository,
                                JdbcTemplate jdbcTemplate,
                                TrustProfileRepository trustProfileRepository,
                                Clock clock) {
        this.matchRequestRepository = matchRequestRepository;
        this.activityMatchRepository = activityMatchRepository;
        this.matchParticipantRepository = matchParticipantRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.trustProfileRepository = trustProfileRepository;
        this.clock = clock;
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

        // 탈퇴한 신청자와 매칭을 확정하면 호스트는 나타나지 않을 상대와 약속을 갖게 되고, 확정된
        // 매칭은 expireOverdue()로 정리되지 않아 잘못된 확정 데이터가 남는다. apply()의 호스트 탈퇴
        // 검사와 대칭이다(users는 matching 도메인이 직접 참조하지 않으므로 jdbcTemplate으로 조회).
        // ensureRespondable() 뒤에 두는 이유: 이미 응답했거나 종료된 매칭에는 그 메시지가 나가야 하고,
        // 아래 안내("거절하면 다시 모집")는 아직 응답 가능한 매칭에서만 사실이기 때문이다.
        // reject()에는 같은 검사를 두지 않는다 — 거절은 게시글을 SEARCHING으로 되돌리는 호스트의 탈출구다.
        Boolean applicantWithdrawn = jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM users WHERE id = ?",
                Boolean.class, applicant.getUserId()
        );
        if (Boolean.TRUE.equals(applicantWithdrawn)) {
            throw new ApplicantWithdrawnException("신청자가 탈퇴해 수락할 수 없어요. 거절하면 다시 모집할 수 있어요.");
        }

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

    // 회원 탈퇴(UserService.withdraw())가 호출 — 탈퇴자가 참가 중인 활성 매칭을 정리하고, 아직 신청자가
    // 없는 모집 중 게시글을 취소한다. 서버는 탈퇴 시점에 그 사람이 나오지 않을 것을 확정적으로 알고,
    // 확정(CONFIRMED) 매칭은 시간이 지나도 스스로 정리되지 않아(endOverdueActivities()는 ENDED로 바꿀
    // 뿐이다) 상대가 약속 장소에 나갔다가 바람맞게 된다. 그래서 탈퇴 시점에 정리한다.
    //
    // 순서가 중요하다: 매칭 정리는 양쪽 게시글을 SEARCHING으로 되돌리는데, 탈퇴자 본인 게시글은 그 뒤에
    // 아래 SEARCHING 취소로 잡아야 한다. 순서가 반대면 탈퇴자의 게시글이 SEARCHING으로 남는다.
    // 두 단계를 한 메서드에 두는 것은 이 순서를 호출자(UserService)가 알 필요가 없게 하기 위해서다.
    //
    // 다른 매칭 액션과 마찬가지로 matching_mutex를 잡아, 동시에 들어오는 신청/수락/거절과 순서를 직렬화한다.
    @Transactional
    public void closeMatchesOnWithdrawal(Long userId) {
        jdbcTemplate.queryForObject("SELECT id FROM matching_mutex WHERE id = 1 FOR UPDATE", Long.class);

        matchParticipantRepository.findActiveActivityMatchIdByUserId(userId)
                .ifPresent(this::closeActiveMatchByWithdrawal);

        // 신청자가 없는 모집 중 게시글은 상대방이 없으므로 그대로 취소한다(MatchRequestStatus.CANCELLED = 작성자가 취소함)
        matchRequestRepository
                .findByUserIdAndStatusIn(userId, List.of(MatchRequestStatus.SEARCHING))
                .ifPresent(request -> request.changeStatus(MatchRequestStatus.CANCELLED));
    }

    // PROPOSED는 expireOverdue()가 기한 후에 하던 처리를 탈퇴 시점으로 앞당기는 것이라 기존 expire()를
    // 재사용한다(신청자 화면에서도 거절과 동일하게 보임). CONFIRMED는 예정 종료 시각 전이면 CANCELLED로 닫고,
    // 이미 지났으면 스케줄러가 했을 ENDED로 닫는다. 참가 연결은 어느 경우든 해제한다.
    //
    // 게시글 상태는 활동 시각으로 정한다 — 게시글을 SEARCHING(모집 탭 재노출)으로 되돌리는 것은 활동 시작 전뿐이다.
    // 이미 시작된 활동의 게시글이 다시 노출되면 목록에는 보이지만 apply()의 응답 기한 계산(시작 1시간 전까지)에
    // 걸려 신청할 수 없는 글이 되기 때문이다.
    //   시작 전                  : PROPOSED→EXPIRED / CONFIRMED→CANCELLED, 게시글 SEARCHING(상대가 즉시 다시 모집)
    //   시작 후 · 종료 전        : CONFIRMED→CANCELLED, 게시글 CLOSED
    //   종료 후(스케줄러 처리 전): CONFIRMED→ENDED, 게시글 CLOSED (endOverdueActivities()가 했을 처리)
    private void closeActiveMatchByWithdrawal(Long activityMatchId) {
        ActivityMatch activityMatch = activityMatchRepository.findById(activityMatchId)
                .orElseThrow(() -> new ActivityMatchNotFoundException("존재하지 않는 매칭이에요."));

        var participants = matchParticipantRepository.findByActivityMatchId(activityMatchId);
        Optional<MatchParticipant> host = findBySlot(participants, "A");
        Optional<MatchParticipant> applicant = findBySlot(participants, "B");
        if (host.isEmpty() || applicant.isEmpty()) {
            // 비정상 데이터 때문에 되돌릴 수 없는 탈퇴 자체를 막지는 않는다 — expireOverdue()와 같은 방침
            log.warn("activityMatchId={} 탈퇴 정리 중 참가자 데이터 이상 발견(host 존재={}, applicant 존재={}) - " +
                    "이 건은 건너뜁니다.", activityMatchId, host.isPresent(), applicant.isPresent());
            return;
        }

        ActivityMatchStatus previousStatus = activityMatch.getStatus();
        OffsetDateTime now = OffsetDateTime.now(clock);
        // 활동 시작 전이면 모집 중으로 되돌리고, 이미 시작됐으면 닫는다(위 주석 참고)
        MatchRequestStatus requestStatusIfNotEnded = now.isBefore(activityMatch.getScheduledAt())
                ? MatchRequestStatus.SEARCHING
                : MatchRequestStatus.CLOSED;
        MatchRequestStatus requestStatusAfter; // 양쪽 게시글이 정리 후 가게 될 상태
        switch (previousStatus) {
            case PROPOSED -> {
                activityMatch.expire();
                requestStatusAfter = requestStatusIfNotEnded;
            }
            case CONFIRMED -> {
                if (now.isBefore(activityMatch.getScheduledEndAt())) {
                    // 아직 끝나지 않은 확정 매칭 — 더 이상 성사될 수 없으므로 취소한다
                    activityMatch.cancelByWithdrawal();
                    requestStatusAfter = requestStatusIfNotEnded;
                } else {
                    // 활동 종료 시각은 지났는데 1분 주기 스케줄러(endOverdueActivities())가 아직 처리하기 전인 건.
                    // 이미 끝난 활동을 취소로 바꾸면 안 되므로, 스케줄러가 했을 처리(ENDED + 게시글 CLOSED)를 앞당긴다.
                    // 완료한 활동 수도 스케줄러가 했을 것과 똑같이 집계한다.
                    endAndCountCompletion(activityMatch, host.get(), applicant.get());
                    requestStatusAfter = MatchRequestStatus.CLOSED;
                }
            }
            default -> {
                // 활성 참가는 PROPOSED/CONFIRMED에만 남아야 한다. 그 외는 비정상 데이터라 건드리지 않는다.
                log.warn("activityMatchId={} 탈퇴 정리 대상이 아닌 상태입니다: status={}", activityMatchId, previousStatus);
                return;
            }
        }

        // 매칭이 끝났으므로 두 참여 연결을 모두 해제한다(안 하면 상대가 다른 매칭에 참여할 수 없다)
        host.get().release();
        applicant.get().release();

        transitionBothRequests(host.get(), applicant.get(), requestStatusAfter);

        log.info("회원 탈퇴로 매칭 정리: activityMatchId={}, 이전 상태={}, 정리 후 게시글 상태={}",
                activityMatchId, previousStatus, requestStatusAfter);
    }

    // 스케줄러(MatchExpireScheduler)가 주기적으로 호출 — 응답 기한이 지난 PROPOSED 건들을
    // 자동으로 EXPIRED 처리하고, 결과(게시글 복귀)는 거절과 동일하게 처리한다.
    @Transactional
    public void expireOverdue() {
        jdbcTemplate.queryForObject("SELECT id FROM matching_mutex WHERE id = 1 FOR UPDATE", Long.class);

        OffsetDateTime now = OffsetDateTime.now(clock);
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

    // 스케줄러(MatchExpireScheduler)가 주기적으로 호출 — 모집 기한(search_expires_at)이 지난 모집 중(SEARCHING)
    // 게시글을 자동 만료(EXPIRED) 처리한다(이슈 #107). V3에 설계되어 있지만 구현되지 않았던 동작이다:
    // search_expires_at은 "이 시각이 지나면 스케줄러가 EXPIRED로 전환한다"는 마감 기한인데, 계산해서 저장만 하고
    // 어디서도 쓰이지 않아 기한이 지난 글이 계속 SEARCHING으로 남았다. 그러면 모집 탭에 계속 노출되고(신청은
    // apply()의 응답 기한 계산에 걸려 거부된다), 활성 게시글은 사용자당 하나뿐(uq_match_request_active_user)이라
    // 호스트가 직접 취소하기 전에는 새 글을 올릴 수 없었다. EXPIRED는 그 유니크 인덱스 대상이 아니라 호스트가 풀린다.
    //
    // 기준은 scheduled_at이 아니라 search_expires_at(= scheduled_at - 1시간)이다. apply()가 신청을 거부하는 시점
    // (활동 시작 1시간 전, decisionExpiresAt이 now 이후가 아닐 때)과 같아서, "신청할 수 없는 글은 마감된 글"로 일치한다.
    // 경계는 포함(search_expires_at <= now)이다.
    //
    // 대상은 SEARCHING뿐이다. PENDING_CONFIRMATION은 응답 기한 처리(expireOverdue())가, MATCHED는 종료 처리
    // (endOverdueActivities())가 담당한다. 신청 대기가 만료돼 SEARCHING으로 복귀한 글도 이미 기한이 지났다면
    // 다음 실행에서 이 메서드가 정리한다.
    //
    // 다른 상태 전이와 같이 matching_mutex 락을 먼저 잡아 신청·취소·수락과 순서를 직렬화한다. 락을 잡은 뒤에
    // 갱신하므로 이미 커밋된 신청·취소의 결과를 정확히 본다. 한 문장의 일괄 UPDATE라 배포 직후 첫 실행에서 쌓여 있던
    // 지난 글을 한꺼번에 처리해도 부담이 없고, 여러 번 실행해도 결과가 같다(멱등). 만료된 건수를 반환한다.
    @Transactional
    public int expireOverdueRequests() {
        jdbcTemplate.queryForObject("SELECT id FROM matching_mutex WHERE id = 1 FOR UPDATE", Long.class);

        OffsetDateTime now = OffsetDateTime.now(clock);
        int expiredCount = jdbcTemplate.update(
                """
                UPDATE match_request
                SET status = 'EXPIRED', updated_at = ?
                WHERE status = 'SEARCHING' AND search_expires_at <= ?
                """,
                now, now
        );

        if (expiredCount > 0) {
            log.info("모집 기한이 지난 게시글 {}건을 만료(EXPIRED) 처리했습니다.", expiredCount);
        }
        return expiredCount;
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

        OffsetDateTime now = OffsetDateTime.now(clock);
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

            endAndCountCompletion(activityMatch, host.get(), applicant.get());

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

    // 활동을 ENDED로 닫고 두 참가자의 "완료한 활동 수"를 올린다(이슈 #96). ActivityMatch.end()를 호출하는 곳은
    // 스케줄러(endOverdueActivities)와 탈퇴 정리(closeActiveMatchByWithdrawal) 두 곳뿐이라, 완료 집계도 이 메서드
    // 하나에만 둔다 — 한쪽에서만 집계하면 두 경로의 값이 어긋난다.
    //
    // 완료한 활동은 activity_match.status의 ENDED 전이 기준이다. 후기·노쇼 신고 여부와 무관하며, CANCELLED·EXPIRED·
    // REJECTED는 집계하지 않는다(활동이 실제로 있었던 경우만 센다). 중복 증가는 상태 전이가 막는다: 두 호출 지점 모두
    // CONFIRMED 상태만 대상으로 하고 end() 뒤에는 ENDED가 되어 다시 대상이 되지 않으며, 두 경로는 matching_mutex로 직렬화된다.
    private void endAndCountCompletion(ActivityMatch activityMatch, MatchParticipant host, MatchParticipant applicant) {
        activityMatch.end();
        trustProfileRepository.incrementCompletedActivityCount(host.getUserId());
        trustProfileRepository.incrementCompletedActivityCount(applicant.getUserId());
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
        boolean deadlinePassed = !OffsetDateTime.now(clock).isBefore(activityMatch.getDecisionExpiresAt());
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
