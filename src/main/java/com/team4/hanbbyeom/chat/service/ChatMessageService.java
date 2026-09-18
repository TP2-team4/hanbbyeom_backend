package com.team4.hanbbyeom.chat.service;

import com.team4.hanbbyeom.chat.domain.ChatMessage;
import com.team4.hanbbyeom.chat.dto.ChatListItemResponse;
import com.team4.hanbbyeom.chat.dto.ChatMessageResponse;
import com.team4.hanbbyeom.chat.dto.ChatMessageSendRequest;
import com.team4.hanbbyeom.chat.exception.ChatUnavailableException;
import com.team4.hanbbyeom.chat.repository.ChatMessageRepository;
import com.team4.hanbbyeom.matching.domain.ActivityMatch;
import com.team4.hanbbyeom.matching.domain.ActivityMatchStatus;
import com.team4.hanbbyeom.matching.exception.ActivityMatchNotFoundException;
import com.team4.hanbbyeom.matching.exception.NotMatchParticipantException;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

// 채팅 참가 권한과 매칭 상태를 확인한 뒤 메시지 조회·저장을 처리
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatMessageService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final ChatMessageRepository chatMessageRepository;
    private final ActivityMatchRepository activityMatchRepository;
    private final MatchParticipantRepository matchParticipantRepository;
    private final Clock clock;

    public List<ChatListItemResponse> getChatList(Long currentUserId) {
        return chatMessageRepository.findChatListByUserId(currentUserId).stream()
                .map(row -> new ChatListItemResponse(
                        row.getActivityMatchId(),
                        row.getCounterpartUserId(),
                        row.getStatus(),
                        row.getCourseName(),
                        row.getLocation(),
                        row.getScheduledAt().atOffset(ZoneOffset.UTC),
                        row.getScheduledEndAt().atOffset(ZoneOffset.UTC),
                        row.getLastMessage(),
                        row.getLastMessageAt() == null
                                ? null
                                : row.getLastMessageAt().atOffset(ZoneOffset.UTC)
                ))
                .toList();
    }

    public List<ChatMessageResponse> getMessages(Long currentUserId, Long activityMatchId, long afterId) {
        if (afterId < 0) {
            throw new IllegalArgumentException("afterId는 0 이상이어야 합니다.");
        }

        ActivityMatch activityMatch = getActivityMatch(activityMatchId);
        validateParticipant(currentUserId, activityMatchId);
        validateChatOpened(activityMatch);

        // afterId가 없거나 0이면 입장 시 사용할 전체 메시지
        // 값이 있으면 폴링에 필요한 마지막 수신 메시지 이후의 새 메시지만 오래된 순서부터 반환
        List<ChatMessage> messages = afterId == 0
                ? chatMessageRepository.findByActivityMatchIdOrderByIdAsc(activityMatchId)
                : chatMessageRepository.findByActivityMatchIdAndIdGreaterThanOrderByIdAsc(
                        activityMatchId,
                        afterId
                );

        return messages.stream()
                .map(ChatMessageResponse::from)
                .toList();
    }

    @Transactional
    public ChatMessageResponse sendMessage(
            Long currentUserId,
            Long activityMatchId,
            ChatMessageSendRequest request
    ) {
        ActivityMatch activityMatch = getActivityMatch(activityMatchId);
        validateParticipant(currentUserId, activityMatchId);
        validateChatOpened(activityMatch);
        validateMessageSendable(activityMatch);

        // 발신자 ID는 요청 본문이 아니라 JWT 인증을 마친 현재 사용자 ID만 사용
        ChatMessage message = new ChatMessage(activityMatchId, currentUserId, request.content());
        return ChatMessageResponse.from(chatMessageRepository.save(message));
    }

    private ActivityMatch getActivityMatch(Long activityMatchId) {
        return activityMatchRepository.findById(activityMatchId)
                .orElseThrow(() -> new ActivityMatchNotFoundException("존재하지 않는 매칭이에요."));
    }

    private void validateParticipant(Long currentUserId, Long activityMatchId) {
        // releasedAt 조건을 사용하지 않아 활동이 끝난 뒤에도 실제 참가자는 과거 메시지 조회 가능
        if (!matchParticipantRepository.existsByActivityMatchIdAndUserId(activityMatchId, currentUserId)) {
            throw new NotMatchParticipantException("해당 매칭 참가자만 채팅을 이용할 수 있어요.");
        }
    }

    private void validateChatOpened(ActivityMatch activityMatch) {
        // 현재 상태만 보면 ENDED도 차단되므로, 한 번이라도 확정된 매칭인지 confirmedAt으로 판단
        if (activityMatch.getConfirmedAt() == null) {
            throw new ChatUnavailableException("확정된 매칭에서만 채팅을 이용할 수 있어요.");
        }
    }

    private void validateMessageSendable(ActivityMatch activityMatch) {
        boolean sendableStatus = activityMatch.getStatus() == ActivityMatchStatus.CONFIRMED
                || activityMatch.getStatus() == ActivityMatchStatus.ENDED;

        // 확정 후 취소 상태가 추가되더라도 자동으로 전송을 허용하지 않도록 현재 허용 상태만 명시
        if (!sendableStatus) {
            throw new ChatUnavailableException("현재 매칭 상태에서는 새 메시지를 보낼 수 없어요.");
        }

        // 약속 시간이 조금 늦어지더라도 같은 날에는 조율할 수 있도록 활동 예정일 자정까지 허용
        // 23:59:59를 직접 비교하지 않고 다음 날 00:00 미만으로 검사해 시간 정밀도 차이를 피함
        OffsetDateTime nextDayStart = activityMatch.getScheduledAt()
                .atZoneSameInstant(SERVICE_ZONE)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(SERVICE_ZONE)
                .toOffsetDateTime();

        // sendDeadline의 오프셋 표기를 KST로 통일하기 위한 변환 (비교는 순간 기준이라 정확성과는 무관)
        // → 변환 생략 시 분기에 따라 +09:00과 +00:00 혼재
        OffsetDateTime scheduledEndAt = activityMatch.getScheduledEndAt()
                .atZoneSameInstant(SERVICE_ZONE)
                .toOffsetDateTime();

        // 자정을 넘겨 끝나는 심야 활동은 예정 종료 시각까지 허용해 기존보다 마감이 빨라지지 않게 함
        OffsetDateTime sendDeadline = nextDayStart.isAfter(scheduledEndAt)
                ? nextDayStart
                : scheduledEndAt;

        // 매칭 종료 스케줄러가 ENDED로 전환했더라도 계산된 전송 마감 전에는 메시지 전송 가능
        if (!OffsetDateTime.now(clock).isBefore(sendDeadline)) {
            throw new ChatUnavailableException("채팅 가능 시간이 지나 새 메시지를 보낼 수 없어요.");
        }
    }
}
