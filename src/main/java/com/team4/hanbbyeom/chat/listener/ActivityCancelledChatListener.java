package com.team4.hanbbyeom.chat.listener;

import com.team4.hanbbyeom.chat.domain.ChatMessage;
import com.team4.hanbbyeom.chat.repository.ChatMessageRepository;
import com.team4.hanbbyeom.matching.event.ActivityCancelledEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 확정된 활동이 취소되면, 상대가 채팅에서 바로 알 수 있도록 취소한 사용자 이름으로 취소 메시지를 남긴다(이슈 #109).
// 채팅은 afterId 폴링으로 갱신되므로, 상태만 바꿔서는 상대가 메시지 목록만으로는 취소를 알 수 없다.
// 프론트가 취소 전에 메시지를 따로 보내는 방식은 두 호출이 원자적이지 않고(취소가 409면 메시지만 남는다),
// 취소된 뒤에는 전송이 막혀(ChatMessageService.validateMessageSendable) 반대 순서도 불가능하다.
@Component
@RequiredArgsConstructor
public class ActivityCancelledChatListener {

    // 취소 확인 모달의 문구("활동 참여를 취소할까요?")와 맞춘다
    static final String CANCEL_MESSAGE = "활동 참여를 취소했어요.";

    private final ChatMessageRepository chatMessageRepository;

    // 취소와 같은 트랜잭션에서 동기로 실행된다. 저장이 실패하면 취소도 함께 롤백되어, 상태만 바뀌고 상대는 모르는 채로
    // 남지 않는다. MANDATORY: 트랜잭션 밖에서 발행되면 별도 커밋으로 조용히 넘어가지 않고 바로 실패시킨다.
    // 메시지는 일반 채팅 메시지와 같은 형식으로 저장한다(chat_message는 프리셋·일반 입력을 구분하지 않는다).
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onActivityCancelled(ActivityCancelledEvent event) {
        chatMessageRepository.save(new ChatMessage(
                event.activityMatchId(), event.cancelledByUserId(), CANCEL_MESSAGE));
    }
}
