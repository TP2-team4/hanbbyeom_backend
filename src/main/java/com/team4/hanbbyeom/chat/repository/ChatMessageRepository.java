package com.team4.hanbbyeom.chat.repository;

import com.team4.hanbbyeom.chat.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

// 채팅 메시지 저장과 매칭별 시간순 조회 담당
// 메시지 ID는 생성 순서대로 증가하므로 폴링 커서로도 사용
// 풀링 커서: 내가 마지막으로 받은 메시지가 어디까지인지 표시하는 기준
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    // 채팅방에 처음 들어왔을 때 기존 메시지를 오래된 순서부터 조회
    List<ChatMessage> findByActivityMatchIdOrderByIdAsc(Long activityMatchId);

    // 프론트가 마지막으로 받은 메시지 ID 이후에 저장된 새 메시지만 조회
    List<ChatMessage> findByActivityMatchIdAndIdGreaterThanOrderByIdAsc(
            Long activityMatchId,
            Long afterId
    );

    // 채팅 목록에서 가장 최근 메시지 내용과 시각을 표시할 때 사용
    Optional<ChatMessage> findTopByActivityMatchIdOrderByIdDesc(Long activityMatchId);
}
