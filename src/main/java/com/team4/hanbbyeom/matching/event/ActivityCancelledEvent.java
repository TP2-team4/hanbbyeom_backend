package com.team4.hanbbyeom.matching.event;

// 확정된 활동을 참가자가 직접 취소했을 때 발행한다(ActivityCancelService, 이슈 #109).
// 취소를 처리하는 트랜잭션 안에서 동기로 전달되고, 채팅 도메인이 받아 취소 메시지를 남긴다.
// matching이 chat을 직접 호출하면, 이미 matching을 참조하는 chat과 패키지가 서로를 참조하게 되므로 이벤트로 이어 둔다.
public record ActivityCancelledEvent(Long activityMatchId, Long cancelledByUserId) {
}
