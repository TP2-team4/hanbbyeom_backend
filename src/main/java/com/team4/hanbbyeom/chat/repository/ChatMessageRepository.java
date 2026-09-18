package com.team4.hanbbyeom.chat.repository;

import com.team4.hanbbyeom.chat.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

// 채팅 메시지 저장과 매칭별 시간순 조회 담당
// 메시지 ID는 생성 순서대로 증가하므로 폴링 커서로도 사용
// 폴링 커서: 내가 마지막으로 받은 메시지가 어디까지인지 표시하는 기준
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

    // 채팅 목록 화면에 필요한 매칭 정보와 최근 메시지를 한 번의 쿼리로 조회
    // released_at 조건을 사용하지 않아 종료된 매칭도 남기고, 메시지가 없으면 confirmed_at을
    // 정렬 기준으로 사용한다. LATERAL 조회는 매칭별 최근 메시지 1건만 가져오기 위한 용도다.
    @Query(value = """
            SELECT am.id AS activityMatchId,
                   other.user_id AS counterpartUserId,
                   am.status AS status,
                   am.course_name AS courseName,
                   am.location AS location,
                   am.scheduled_at AS scheduledAt,
                   am.scheduled_end_at AS scheduledEndAt,
                   latest.content AS lastMessage,
                   latest.created_at AS lastMessageAt
            FROM match_participant mine
            JOIN activity_match am ON am.id = mine.activity_match_id
            JOIN match_participant other
              ON other.activity_match_id = am.id
             AND other.user_id <> :currentUserId
            LEFT JOIN LATERAL (
                SELECT cm.content, cm.created_at
                FROM chat_message cm
                WHERE cm.activity_match_id = am.id
                ORDER BY cm.id DESC
                LIMIT 1
            ) latest ON TRUE
            WHERE mine.user_id = :currentUserId
              AND am.confirmed_at IS NOT NULL
            ORDER BY COALESCE(latest.created_at, am.confirmed_at) DESC, am.id DESC
            """, nativeQuery = true)
    List<ChatListRow> findChatListByUserId(@Param("currentUserId") Long currentUserId);

    // 네이티브 쿼리의 별칭과 getter 이름을 연결해 채팅 목록 한 행을 받는 프로젝션
    interface ChatListRow {
        Long getActivityMatchId();
        Long getCounterpartUserId();
        String getStatus();
        String getCourseName();
        String getLocation();
        Instant getScheduledAt();
        Instant getScheduledEndAt();
        String getLastMessage();
        Instant getLastMessageAt();
    }
}
