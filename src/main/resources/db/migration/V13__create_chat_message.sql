-- 확정된 매칭 참가자 간 1:1 채팅 메시지 저장
-- 별도 채팅방 테이블을 만들지 않고 activity_match.id를 채팅방 식별자로 사용
CREATE TABLE chat_message (
    id BIGSERIAL,

    -- 메시지가 속한 매칭이자 채팅방 ID
    activity_match_id BIGINT NOT NULL,

    -- 메시지를 보낸 사용자
    -- 클라이언트 요청값이 아니라 JWT 인증 사용자 ID를 저장할 예정
    sender_id BIGINT NOT NULL,

    -- 일반 입력과 프리셋 버튼 문구를 구분하지 않고 동일한 텍스트로 저장
    content VARCHAR(100) NOT NULL,

    -- 서버 기준 메시지 생성 시각
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_chat_message
        PRIMARY KEY (id),

    CONSTRAINT fk_chat_message_match
        FOREIGN KEY (activity_match_id)
        REFERENCES activity_match(id),

    CONSTRAINT fk_chat_message_sender
        FOREIGN KEY (sender_id)
        REFERENCES users(id),

    -- 한 매칭에 실제로 참가한 사용자만 해당 채팅의 발신자로 저장 가능
    -- match_participant의 uq_participant_match_user 유니크 제약을 참조
    CONSTRAINT fk_chat_message_sender_participant
        FOREIGN KEY (activity_match_id, sender_id)
        REFERENCES match_participant(activity_match_id, user_id),

    CONSTRAINT chk_chat_message_content
        CHECK (
            BTRIM(content) <> ''
            AND CHAR_LENGTH(content) <= 100
        )
);

-- 채팅방의 전체 메시지와 afterId 이후 메시지를 ID 순서로 조회하기 위한 인덱스
CREATE INDEX idx_chat_message_match_id
    ON chat_message(activity_match_id, id);

COMMENT ON TABLE chat_message IS
    '확정된 매칭 참가자 간 1:1 채팅 메시지';
COMMENT ON COLUMN chat_message.activity_match_id IS
    '채팅방으로 사용하는 activity_match ID';
COMMENT ON COLUMN chat_message.sender_id IS
    'JWT 인증으로 확인한 메시지 발신 사용자 ID';
COMMENT ON COLUMN chat_message.content IS
    '화면에 표시할 메시지 내용, 최대 100자';
COMMENT ON COLUMN chat_message.created_at IS
    '메시지 생성 시각';
