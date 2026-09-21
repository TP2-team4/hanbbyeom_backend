package com.team4.hanbbyeom.feedback.service;

import com.team4.hanbbyeom.feedback.domain.ActivityReview;
import com.team4.hanbbyeom.feedback.domain.NoShowReport;
import com.team4.hanbbyeom.feedback.dto.FeedbackStatusResponse;
import com.team4.hanbbyeom.feedback.dto.NoShowReportCreateRequest;
import com.team4.hanbbyeom.feedback.dto.ReviewCreateRequest;
import com.team4.hanbbyeom.feedback.exception.FeedbackAlreadySubmittedException;
import com.team4.hanbbyeom.feedback.exception.FeedbackNotAllowedException;
import com.team4.hanbbyeom.feedback.repository.ActivityReviewRepository;
import com.team4.hanbbyeom.feedback.repository.NoShowReportRepository;
import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.ActivityMatchStatus;
import com.team4.hanbbyeom.matching.domain.MatchParticipant;
import com.team4.hanbbyeom.matching.exception.ActivityMatchNotFoundException;
import com.team4.hanbbyeom.matching.exception.NotMatchParticipantException;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.trust.repository.TrustProfileRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

// 활동 후기 작성 / 노쇼 신고 접수 / 제출 가능 여부 조회를 담당하는 서비스.
// 세 기능 모두 "이 사람이 이 매칭의 진짜 참가자가 맞는지 + 활동이 실제로 끝났는지"를
// 똑같이 검증해야 해서, 그 공통 로직을 validateAndGetCounterpart()로 뽑아뒀다.
@Service
public class ActivityFeedbackService {

    private final ActivityMatchRepository activityMatchRepository;
    private final MatchParticipantRepository matchParticipantRepository;
    private final ActivityReviewRepository activityReviewRepository;
    private final NoShowReportRepository noShowReportRepository;
    // trust_profile 쓰기는 trust 도메인(TrustProfileRepository)이 소유한다 — 후기/노쇼 반영 SQL은
    // 거기 있고, 여기서는 "무엇을 반영할지"만 호출한다 (이슈 #94)
    private final TrustProfileRepository trustProfileRepository;
    // "활동이 끝났는지" 판정은 TimeConfig의 Clock 빈으로만 한다 — 경계 시각(now == scheduledEndAt)
    // 테스트를 시계 고정으로 쓸 수 있게 하기 위함 (채팅 도메인과 동일한 방식, #94)
    private final Clock clock;

    public ActivityFeedbackService(ActivityMatchRepository activityMatchRepository,
                                   MatchParticipantRepository matchParticipantRepository,
                                   ActivityReviewRepository activityReviewRepository,
                                   NoShowReportRepository noShowReportRepository,
                                   TrustProfileRepository trustProfileRepository,
                                   Clock clock) {
        this.activityMatchRepository = activityMatchRepository;
        this.matchParticipantRepository = matchParticipantRepository;
        this.activityReviewRepository = activityReviewRepository;
        this.noShowReportRepository = noShowReportRepository;
        this.trustProfileRepository = trustProfileRepository;
        this.clock = clock;
    }

    // 활동 후기 작성.
    // @Transactional: "후기 저장"과 "trust_profile 갱신" 두 DB 작업을 하나의 트랜잭션으로 묶어서,
    // 중간에 하나라도 실패하면(예: 갱신 쿼리 오류) 후기 저장까지 통째로 롤백되게 함
    @Transactional
    public void submitReview(Long activityMatchId, Long reviewerId, ReviewCreateRequest request) {
        Long revieweeId = validateAndGetCounterpart(activityMatchId, reviewerId);

        // 같은 활동에 대해 후기든 신고든 이미 뭔가 제출했다면 또 제출 못 하게 막음.
        // 이 existsBy 체크와 save() 사이에는 시간차가 있어서, 같은 사용자가 거의 동시에
        // 두 번 요청을 보내면(더블클릭 등) 둘 다 이 체크를 통과할 수 있다 — 그다음은
        // uq_activity_review_once UNIQUE 제약이 최종 방어선이고, 그 위반을 아래 catch에서
        // FeedbackAlreadySubmittedException(409)으로 바꿔준다(안 그러면 500이 나감).
        // "후기든 신고든 하나만" 규칙 자체는 activity_review/no_show_report가 서로 다른
        // 테이블이라 각 테이블의 UNIQUE 제약만으로는 교차 보장되지 않는다 — 그래서 위
        // validateAndGetCounterpart()에서 activity_match 행을 비관적 락으로 먼저 잠가,
        // 같은 사용자가 같은 활동에 후기와 신고를 거의 동시에 보내는 경우를 직렬화한다.
        // 이 existsBy 체크는 그 락 덕분에 항상 "다른 트랜잭션이 이미 커밋한 결과"를 정확히
        // 보고 판단한다 (PR #83 리뷰로 발견된 레이스, ActivityMatchRepository.findByIdForUpdate 참고).
        if (activityReviewRepository.existsByActivityMatchIdAndReviewerUserId(activityMatchId, reviewerId)
                || noShowReportRepository.existsByActivityMatchIdAndReporterUserId(activityMatchId, reviewerId)) {
            throw new FeedbackAlreadySubmittedException("이미 이 활동에 대한 후기 또는 신고를 제출했어요.");
        }

        try {
            activityReviewRepository.save(new ActivityReview(
                    activityMatchId, reviewerId, revieweeId,
                    request.rating(), request.perceivedTalkLevel(), request.comment()
            ));
        } catch (DataIntegrityViolationException e) {
            throw new FeedbackAlreadySubmittedException("이미 이 활동에 대한 후기 또는 신고를 제출했어요.");
        }

        trustProfileRepository.applyReview(revieweeId, request.rating(), request.perceivedTalkLevel());
    }

    // 노쇼 신고 접수. submitReview()와 구조는 거의 동일하고, 저장하는 엔티티와
    // trust_profile에 반영하는 값(no_show_report_count)만 다르다.
    @Transactional
    public void submitNoShowReport(Long activityMatchId, Long reporterId, NoShowReportCreateRequest request) {
        Long reportedId = validateAndGetCounterpart(activityMatchId, reporterId);

        // submitReview()와 동일한 이유로 existsBy 체크 + UNIQUE 제약 위반 catch를 같이 둔다.
        if (activityReviewRepository.existsByActivityMatchIdAndReviewerUserId(activityMatchId, reporterId)
                || noShowReportRepository.existsByActivityMatchIdAndReporterUserId(activityMatchId, reporterId)) {
            throw new FeedbackAlreadySubmittedException("이미 이 활동에 대한 후기 또는 신고를 제출했어요.");
        }

        try {
            noShowReportRepository.save(new NoShowReport(
                    activityMatchId, reporterId, reportedId,
                    request.reason(), request.detail()
            ));
        } catch (DataIntegrityViolationException e) {
            throw new FeedbackAlreadySubmittedException("이미 이 활동에 대한 후기 또는 신고를 제출했어요.");
        }

        trustProfileRepository.incrementNoShowReportCount(reportedId);
    }

    // "지금 이 활동에 대해 후기/신고를 낼 수 있는지" 프론트가 미리 물어보는 용도.
    // submitReview/submitNoShowReport와 달리 예외를 던지지 않고, canSubmit=false로만 알려준다
    // (참가자가 아니면 아예 조회 자체를 막는 게 자연스러워서 그 경우만 예외로 처리).
    @Transactional(readOnly = true)
    public FeedbackStatusResponse getFeedbackStatus(Long activityMatchId, Long userId) {
        ActivityMatch activityMatch = activityMatchRepository.findById(activityMatchId)
                .orElseThrow(() -> new ActivityMatchNotFoundException("존재하지 않는 매칭이에요."));

        List<MatchParticipant> participants = matchParticipantRepository.findByActivityMatchId(activityMatchId);
        boolean isParticipant = participants.stream().anyMatch(p -> p.getUserId().equals(userId));
        if (!isParticipant) {
            throw new NotMatchParticipantException("본인이 참여한 매칭만 조회할 수 있어요.");
        }

        boolean reviewed = activityReviewRepository.existsByActivityMatchIdAndReviewerUserId(activityMatchId, userId);
        boolean reported = noShowReportRepository.existsByActivityMatchIdAndReporterUserId(activityMatchId, userId);
        boolean alreadySubmitted = reviewed || reported;
        // 후기와 신고 둘 다 안 냈으면 submittedType은 null
        String submittedType = reviewed ? "REVIEW" : (reported ? "NO_SHOW_REPORT" : null);

        boolean wasConfirmed = activityMatch.getStatus() == ActivityMatchStatus.CONFIRMED
                || activityMatch.getStatus() == ActivityMatchStatus.ENDED;
        boolean activityEnded = !OffsetDateTime.now(clock).isBefore(activityMatch.getScheduledEndAt());

        boolean canSubmit = wasConfirmed && activityEnded && !alreadySubmitted;

        return new FeedbackStatusResponse(canSubmit, alreadySubmitted, submittedType);
    }

    // submitReview/submitNoShowReport 공통 검증.
    // 0) 이 활동 행을 비관적 락으로 먼저 잠근다 — activity_review/no_show_report가 서로 다른
    //    테이블이라 "후기든 신고든 하나만" 규칙을 DB UNIQUE 제약만으로 교차 보장할 수 없어서,
    //    같은 사용자가 같은 활동에 후기와 신고를 거의 동시에 보내는 경우를 이 락으로 직렬화한다
    //    (ActivityMatchRepository.findByIdForUpdate 참고, PR #83 리뷰로 발견된 레이스).
    //    이 메서드는 submitReview/submitNoShowReport 양쪽에서 항상 먼저 호출되므로, 두 제출
    //    경로 모두 같은 activityMatchId에 대해 이 잠금으로 순서가 강제된다.
    // 1) 매칭이 확정된 적 있는지(PROPOSED 상태로 끝난 매칭엔 애초에 활동 자체가 없었음)
    // 2) 요청자가 실제 이 매칭의 참가자인지
    // 3) 활동이 실제로 끝났는지 — status가 ENDED로 바뀌는 걸 기다리지 않고
    //    scheduledEndAt과 지금 시각을 직접 비교한다. ENDED 전환은 1분 주기 스케줄러(MatchExpireScheduler)가
    //    처리하는데, 그 배치가 아직 안 돈 최대 1분 사이의 "사실은 끝났는데 아직 CONFIRMED인" 상태를
    //    놓치지 않기 위함 (PR 리뷰로 발견된 포인트)
    // 검증을 통과하면 "상대방(내가 후기/신고를 남길 대상)"의 userId를 반환한다.
    private Long validateAndGetCounterpart(Long activityMatchId, Long requesterId) {
        ActivityMatch activityMatch = activityMatchRepository.findByIdForUpdate(activityMatchId)
                .orElseThrow(() -> new ActivityMatchNotFoundException("존재하지 않는 매칭이에요."));

        boolean wasConfirmed = activityMatch.getStatus() == ActivityMatchStatus.CONFIRMED
                || activityMatch.getStatus() == ActivityMatchStatus.ENDED;
        if (!wasConfirmed) {
            throw new FeedbackNotAllowedException("확정된 적 없는 매칭이에요.");
        }

        List<MatchParticipant> participants = matchParticipantRepository.findByActivityMatchId(activityMatchId);

        boolean isParticipant = participants.stream().anyMatch(p -> p.getUserId().equals(requesterId));
        if (!isParticipant) {
            throw new NotMatchParticipantException("본인이 참여한 매칭만 가능해요.");
        }

        if (OffsetDateTime.now(clock).isBefore(activityMatch.getScheduledEndAt())) {
            throw new FeedbackNotAllowedException("아직 활동이 끝나지 않았어요.");
        }

        // 참가자는 항상 2명(A/B)이므로, 나를 뺀 나머지 한 명이 상대방
        return participants.stream()
                .filter(p -> !p.getUserId().equals(requesterId))
                .findFirst()
                .map(MatchParticipant::getUserId)
                .orElseThrow(() -> new NotMatchParticipantException("상대방 정보를 찾을 수 없어요."));
    }
}
