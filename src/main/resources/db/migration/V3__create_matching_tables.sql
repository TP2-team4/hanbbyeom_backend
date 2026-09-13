-- ============================================================
-- Matching 도메인: 게시글 기반 동행 매칭
-- ------------------------------------------------------------
-- 흐름: 사용자가 조건을 등록하면 match_request(게시글)가 되어 모집 탭에 노출된다.
--       다른 사용자가 그 게시글에 신청하면 activity_match(제안)가 생기고,
--       양쪽 참가자(match_participant)의 수락 상태에 따라 확정/거절된다.
--       matching_mutex는 동시에 여러 신청이 들어올 때 순서를 직렬화하기 위한 잠금 행이다.
--
-- users(id)는 V1__create_users.sql에서 이미 생성됨. 여기서는 그 PK(BIGSERIAL → bigint)만
-- 참조하므로, email/password_hash 등 users의 다른 컬럼 구조가 무엇이든 이 파일에는 영향이 없다.
-- ============================================================

-- ------------------------------------------------------------
-- 1. match_request — 매칭 조건이자 "모집 게시글"
--    화면상 "조건 설정" 화면에서 만들어지고, "모집 탭" 목록에 이 테이블의
--    SEARCHING 상태 행들이 노출된다. 신청이 들어오면 PENDING_CONFIRMATION으로,
--    확정되면 MATCHED로 바뀐다.
-- ------------------------------------------------------------
CREATE TABLE match_request (
    -- 게시글 고유 ID. 다른 테이블(activity_match 등)에서 이 값을 참조한다.
                               id BIGSERIAL,

    -- 게시글 작성자(요청한 사용자). users.id를 참조하며, 탈퇴 회원이어도
    -- 과거 매칭 이력 보존을 위해 삭제하지 않고 그대로 둔다(ON DELETE 지정 안 함).
                               user_id BIGINT NOT NULL,

    -- 활동 유형. 1차 MVP(Quiet Run)는 러닝만 지원하므로 'RUN' 고정.
    -- 추후 '혼밥메이트(Quiet Meal)' 등이 추가되면 이 컬럼 값 종류가 늘어날 수 있다.
                               activity_type VARCHAR(10) NOT NULL DEFAULT 'RUN',

    -- 희망 활동 시작 일시 (예: 화면의 "9월 12일 (금) 07:00").
                               scheduled_at TIMESTAMPTZ NOT NULL,

    -- 이번 활동에서 원하는 대화 수준. run_match_condition(Run 도메인)의 코스/페이스와
    -- 달리 대화 수준은 매칭 성사 여부에 직접 관련되어 이 테이블에 둔다.
                               talk_level VARCHAR(20) NOT NULL,

    -- 게시글 상태. 모집 탭 목록 조회는 반드시 SEARCHING만 노출해야 한다(다른 상태는
    -- 이미 신청이 진행 중이거나 끝난 글이므로 목록에 다시 뜨면 안 됨).
    --   SEARCHING            : 모집 중, 목록에 노출됨
    --   PENDING_CONFIRMATION : 누군가 신청해서 호스트 응답 대기 중, 목록에서 숨김
    --   MATCHED              : 확정됨, 영구히 목록에서 제외
    --   CANCELLED            : 작성자가 직접 취소
    --   EXPIRED              : search_expires_at 또는 decision_expires_at 경과로 자동 만료
    --   CLOSED               : 기타 종료(예비)
                               status VARCHAR(24) NOT NULL DEFAULT 'SEARCHING',

    -- 게시글이 모집 탭에 노출되는 마감 기한. 이 시각이 지나면 스케줄러가 EXPIRED로 전환한다.
                               search_expires_at TIMESTAMPTZ NOT NULL,

                               created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                               updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

                               CONSTRAINT pk_match_request PRIMARY KEY (id),

    -- 작성자가 실제 존재하는 사용자인지 보장.
                               CONSTRAINT fk_match_request_user FOREIGN KEY (user_id) REFERENCES users(id),

    -- match_participant가 (match_request_id, user_id) 조합으로 이 테이블을 복합 참조할 수 있도록
    -- 만들어두는 유니크 제약. "이 게시글은 반드시 이 작성자 소유"라는 사실을 DB 레벨에서 강제한다.
                               CONSTRAINT uq_match_request_id_user UNIQUE (id, user_id),

    -- 활동 유형 값 제한. 지금은 RUN만 허용.
                               CONSTRAINT chk_match_request_type CHECK (activity_type IN ('RUN')),

    -- 대화 수준 값 제한. 프론트엔드 조건 설정 화면의 2개 옵션과 정확히 일치해야 한다.
                               CONSTRAINT chk_match_request_talk CHECK (talk_level IN ('SILENT', 'LIGHT_CHAT')),

    -- 게시글 상태 값 제한.
                               CONSTRAINT chk_match_request_status CHECK (status IN (
                                                                                     'SEARCHING', 'PENDING_CONFIRMATION', 'MATCHED', 'CANCELLED', 'EXPIRED', 'CLOSED'
                                   )),

    -- 시간 순서 보장: 생성 시각 < 게시 마감 기한 < 희망 활동 시작 시각.
    -- 이 순서가 깨지면 "마감되기도 전에 활동이 시작하는" 논리적 모순이 생긴다.
                               CONSTRAINT chk_match_request_time CHECK (
                                   created_at < search_expires_at AND search_expires_at < scheduled_at
                                   )
);

-- 한 사용자가 동시에 여러 개의 활성 게시글/신청을 가질 수 없도록 막는 부분 유니크 인덱스.
-- (부분 인덱스라서 CANCELLED/EXPIRED/CLOSED 상태인 과거 행은 유니크 검사에서 제외된다.)
CREATE UNIQUE INDEX uq_match_request_active_user ON match_request(user_id)
    WHERE status IN ('SEARCHING', 'PENDING_CONFIRMATION', 'MATCHED');

-- 모집 탭 목록 조회(GET /api/matching/board)가 status/activity_type/scheduled_at/talk_level로
-- 필터링하는 패턴과 정확히 맞춘 복합 인덱스.
CREATE INDEX idx_match_request_candidate ON match_request(status, activity_type, scheduled_at, talk_level);

COMMENT ON TABLE match_request IS '상대를 찾기 위한 공통 조건과 요청 상태를 저장. 모집 게시글 역할을 겸함';
COMMENT ON COLUMN match_request.id IS '매칭 요청(게시글) 고유 ID';
COMMENT ON COLUMN match_request.user_id IS '작성자(요청한) 사용자 ID. users.id 참조';
COMMENT ON COLUMN match_request.activity_type IS '활동 유형. 1차 MVP는 RUN만 허용';
COMMENT ON COLUMN match_request.scheduled_at IS '희망 시작 일시';
COMMENT ON COLUMN match_request.talk_level IS '이번 활동의 희망 대화 수준';
COMMENT ON COLUMN match_request.status IS '검색, 후보 확인, 확정, 취소, 만료, 종료 상태';
COMMENT ON COLUMN match_request.search_expires_at IS '게시 종료 기한';
COMMENT ON COLUMN match_request.created_at IS '요청 생성 시각';
COMMENT ON COLUMN match_request.updated_at IS '요청 상태 수정 시각';


-- ------------------------------------------------------------
-- 2. activity_match — 신청으로 생긴 "제안 ~ 확정" 상태의 동행 약속
--    신청자가 match_request에 신청하는 순간 이 행이 PROPOSED로 생성되고,
--    호스트가 수락하면 CONFIRMED(+ meeting_code 발급), 거절하면 REJECTED가 된다.
--    실제 만남 정보(장소/코스/합의 페이스 등)는 신청 시점 값을 그대로 복사해 고정한다
--    (나중에 원본 match_request/run_match_condition이 바뀌어도 이미 나간 제안은 안 바뀌게).
-- ------------------------------------------------------------
CREATE TABLE activity_match (
                                id BIGSERIAL,

    -- match_request와 동일하게 1차 MVP는 RUN 고정.
                                activity_type VARCHAR(10) NOT NULL DEFAULT 'RUN',

    -- 두 참가자에게 동일하게 안내되는 시작/종료 일시.
                                scheduled_at TIMESTAMPTZ NOT NULL,
                                scheduled_end_at TIMESTAMPTZ NOT NULL,

    -- 신청 시점에 고정된 대화 수준 (호스트 게시글의 값을 그대로 복사).
                                talk_level VARCHAR(20) NOT NULL,

    -- 실제 만남 장소 문자열 (예: "뚝섬유원지역 3번 출구"). running_course.meeting_point 복사본.
                                location VARCHAR(255) NOT NULL,

    -- 후보 생성 당시 코스 이름 스냅샷. running_course가 나중에 이름이 바뀌어도 영향 없게.
                                course_name VARCHAR(100) NOT NULL,

    -- 합의된 코스 거리(m).
                                distance_meters INT NOT NULL,

    -- 후보 생성 당시 경로 안내 스냅샷.
                                route_description VARCHAR(1000) NOT NULL,

    -- 두 사람의 희망 페이스 범위 중 겹치는 구간(교집합)의 하한/상한(초/km).
                                agreed_pace_min_sec INT NOT NULL,
                                agreed_pace_max_sec INT NOT NULL,

    -- 제안~확정 상태.
    --   PROPOSED  : 신청 접수, 호스트 응답 대기 중
    --   CONFIRMED : 호스트가 수락해서 확정됨 (meeting_code 발급)
    --   REJECTED  : 호스트가 거절함
    --   CANCELLED : 신청자 또는 호스트가 확정 전에 취소함
    --   EXPIRED   : decision_expires_at을 넘겨 자동 만료됨
    --   ENDED     : scheduled_end_at이 지나 활동이 자연 종료됨
                                status VARCHAR(24) NOT NULL DEFAULT 'PROPOSED',

    -- 호스트가 수락/거절해야 하는 응답 기한. 스케줄러가 이 시각이 지난 PROPOSED 건을
    -- 자동으로 EXPIRED 처리한다 (거절과 동일하게 양쪽 게시글을 SEARCHING으로 되돌림).
                                decision_expires_at TIMESTAMPTZ NOT NULL,

    -- 확정(CONFIRMED)된 경우에만 발급되는 현장 확인용 6자리 코드. 그 전까지는 NULL.
                                meeting_code VARCHAR(6),

    -- 호스트 수락으로 CONFIRMED가 된 시각.
                                confirmed_at TIMESTAMPTZ,

    -- 거절/취소/만료/종료로 이 매칭이 닫힌 시각.
                                closed_at TIMESTAMPTZ,

    -- 거절 또는 취소를 "직접 행동으로" 발생시킨 사용자. 자동 만료(EXPIRED)나
    -- 시간 경과 종료(ENDED)처럼 시스템이 처리한 경우는 NULL로 남긴다.
                                closed_by_user_id BIGINT,

                                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

                                CONSTRAINT pk_activity_match PRIMARY KEY (id),

    -- closed_by_user_id도 실제 사용자여야 하므로 FK로 보장.
                                CONSTRAINT fk_activity_match_closed_by FOREIGN KEY (closed_by_user_id) REFERENCES users(id),

                                CONSTRAINT chk_activity_match_type CHECK (activity_type IN ('RUN')),
                                CONSTRAINT chk_activity_match_talk CHECK (talk_level IN ('SILENT', 'LIGHT_CHAT')),
                                CONSTRAINT chk_activity_match_status CHECK (status IN (
                                                                                       'PROPOSED', 'CONFIRMED', 'REJECTED', 'CANCELLED', 'EXPIRED', 'ENDED'
                                    )),

    -- 시간 순서 보장: 생성 < 응답 기한 < 시작 시각, 그리고 종료 시각은 시작 시각보다 뒤여야 함.
                                CONSTRAINT chk_activity_match_time CHECK (
                                    created_at < decision_expires_at AND decision_expires_at < scheduled_at
                                        AND scheduled_end_at > scheduled_at
                                    ),

    -- 합의 페이스 범위가 러닝 조건의 허용 범위(240~600초/km, run_match_condition과 동일 기준) 안에
    -- 있어야 하고, 하한이 상한보다 작거나 같아야 한다.
                                CONSTRAINT chk_activity_match_pace CHECK (
                                    agreed_pace_min_sec BETWEEN 240 AND 600
                                        AND agreed_pace_max_sec BETWEEN 240 AND 600
                                        AND agreed_pace_min_sec <= agreed_pace_max_sec
                                    ),

    -- 거리는 항상 양수.
                                CONSTRAINT chk_activity_match_distance CHECK (distance_meters > 0)
);

-- 자동 만료 스케줄러가 "PROPOSED 상태이면서 decision_expires_at이 지난 건"을 찾는 조회 패턴 지원.
CREATE INDEX idx_activity_match_expiration ON activity_match(status, decision_expires_at);

-- 활동 종료(ENDED) 처리 배치가 "CONFIRMED 상태이면서 scheduled_end_at이 지난 건"을 찾는 패턴 지원.
CREATE INDEX idx_activity_match_end ON activity_match(status, scheduled_end_at);

COMMENT ON TABLE activity_match IS '매칭 후보와 두 사람이 합의할 약속 정보를 저장';
COMMENT ON COLUMN activity_match.id IS '매칭 고유 ID';
COMMENT ON COLUMN activity_match.activity_type IS '활동 유형. 1차 MVP는 RUN';
COMMENT ON COLUMN activity_match.scheduled_at IS '양쪽에 동일하게 안내하는 시작 일시';
COMMENT ON COLUMN activity_match.scheduled_end_at IS '시스템상 예정 종료 시각. 실제 참석 증명과 무관';
COMMENT ON COLUMN activity_match.talk_level IS '신청 시점에 고정된 대화 수준';
COMMENT ON COLUMN activity_match.location IS '고정된 만남 장소';
COMMENT ON COLUMN activity_match.course_name IS '후보 생성 당시 코스 이름 스냅샷';
COMMENT ON COLUMN activity_match.distance_meters IS '합의할 코스 거리(m)';
COMMENT ON COLUMN activity_match.route_description IS '후보 생성 당시 경로 안내 스냅샷';
COMMENT ON COLUMN activity_match.agreed_pace_min_sec IS '두 페이스 범위의 공통 하한(초/km)';
COMMENT ON COLUMN activity_match.agreed_pace_max_sec IS '두 페이스 범위의 공통 상한(초/km)';
COMMENT ON COLUMN activity_match.status IS '제안, 확정, 거절, 취소, 만료, 시간 종료 상태';
COMMENT ON COLUMN activity_match.decision_expires_at IS '호스트 응답(수락/거절) 기한';
COMMENT ON COLUMN activity_match.meeting_code IS '확정 참가자에게만 보여주는 현장 식별용 6자리 코드';
COMMENT ON COLUMN activity_match.confirmed_at IS '호스트 수락으로 확정된 시각';
COMMENT ON COLUMN activity_match.closed_at IS '거절, 취소, 만료 또는 시간 종료로 닫힌 시각';
COMMENT ON COLUMN activity_match.closed_by_user_id IS '거절 또는 취소한 사용자. 자동 처리는 NULL. users.id 참조';
COMMENT ON COLUMN activity_match.created_at IS '후보(제안) 생성 시각';


-- ------------------------------------------------------------
-- 3. match_participant — activity_match에 연결된 두 참가자(호스트/신청자)
--    slot A는 게시글 작성자(호스트), slot B는 신청자로 고정해서 사용한다.
--    accept_status는 각자의 개별 수락 여부를 나타내며, 신청자는 신청하는 순간
--    본인 행을 ACCEPTED로 만들고(신청 자체가 곧 본인 동의), 호스트는 별도의
--    수락/거절 API를 호출해야 PENDING에서 벗어난다.
-- ------------------------------------------------------------
CREATE TABLE match_participant (
                                   id BIGSERIAL,

    -- 이 참여가 속한 매칭(제안~확정) 건.
                                   activity_match_id BIGINT NOT NULL,

    -- 이 참가자가 원래 등록했던 게시글/조건. 호스트는 자신이 올린 글, 신청자는
    -- (신청을 위해 이미 등록해뒀던) 본인 조건을 가리킨다.
                                   match_request_id BIGINT NOT NULL,

    -- 참가 사용자. match_request_id가 가리키는 게시글의 소유자와 항상 일치해야 한다
    -- (아래 fk_participant_request_owner 복합 FK로 강제됨).
                                   user_id BIGINT NOT NULL,

    -- 참가자 자리. 'A' = 호스트(게시글 작성자), 'B' = 신청자. 매칭당 최대 2명 고정.
                                   slot VARCHAR(1) NOT NULL,

    -- 개별 수락 상태. 둘 다 ACCEPTED가 되는 순간 activity_match가 CONFIRMED로 바뀐다.
                                   accept_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    -- 수락 또는 거절을 실제로 누른 시각. 신청자는 신청과 동시에 채워짐(ACCEPTED이므로).
                                   responded_at TIMESTAMPTZ,

    -- 이 참여 연결이 끝난(더 이상 활성이 아닌) 시각. NULL이면 지금도 유효한 연결이라는 뜻이며,
    -- 아래 부분 유니크 인덱스 두 개가 이 값을 기준으로 "활성 연결은 하나뿐"을 강제한다.
                                   released_at TIMESTAMPTZ,

                                   CONSTRAINT pk_match_participant PRIMARY KEY (id),

    -- 참여가 속한 매칭 건이 실제 존재해야 함.
                                   CONSTRAINT fk_participant_match FOREIGN KEY (activity_match_id) REFERENCES activity_match(id),

    -- 참가 사용자가 실제 존재해야 함.
                                   CONSTRAINT fk_participant_user FOREIGN KEY (user_id) REFERENCES users(id),

    -- "이 게시글은 반드시 이 사용자 소유"라는 사실을 match_request의 복합 유니크 제약과 엮어서
    -- 강제한다. 즉 다른 사람 소유의 게시글 ID를 이 사용자 이름으로 끼워 넣는 것을 DB가 막아준다.
                                   CONSTRAINT fk_participant_request_owner FOREIGN KEY (match_request_id, user_id)
                                       REFERENCES match_request(id, user_id),

    -- 한 매칭 안에서 slot A/B는 각각 한 번만 존재해야 함(호스트 중복, 신청자 중복 방지).
                                   CONSTRAINT uq_participant_match_slot UNIQUE (activity_match_id, slot),

    -- 한 매칭에 같은 사용자가 두 번 참여할 수 없음(자기 자신에게 신청하는 등 방지).
                                   CONSTRAINT uq_participant_match_user UNIQUE (activity_match_id, user_id),

    -- 한 매칭에 같은 게시글이 두 번 연결될 수 없음.
                                   CONSTRAINT uq_participant_match_request UNIQUE (activity_match_id, match_request_id),

                                   CONSTRAINT chk_participant_slot CHECK (slot IN ('A', 'B')),
                                   CONSTRAINT chk_participant_accept CHECK (accept_status IN ('PENDING', 'ACCEPTED', 'REJECTED'))
);

-- 부분 유니크 인덱스: 같은 게시글(match_request_id)에 "현재 유효한(released_at IS NULL)"
-- 참여 연결은 동시에 하나만 존재할 수 있다. 즉 게시글 하나가 이미 신청을 받아 진행 중이면
-- 다른 사람이 같은 글에 또 신청해서 별도 행을 만들 수 없다(신청 경합 방지의 핵심 제약).
CREATE UNIQUE INDEX uq_participant_active_request ON match_participant(match_request_id)
    WHERE released_at IS NULL;

-- 부분 유니크 인덱스: 한 사용자는 동시에 하나의 활성 참여만 가질 수 있다
-- (이미 다른 매칭에 참여 중이면 또 다른 매칭에 참여할 수 없음).
CREATE UNIQUE INDEX uq_participant_active_user ON match_participant(user_id)
    WHERE released_at IS NULL;

-- 특정 게시글의 과거 매칭 이력을 시간순으로 조회하는 패턴 지원.
CREATE INDEX idx_participant_request_history ON match_participant(match_request_id, activity_match_id);

-- 특정 사용자의 과거 매칭 이력을 시간순으로 조회하는 패턴 지원(마이페이지 등에서 사용 가능).
CREATE INDEX idx_participant_user_history ON match_participant(user_id, activity_match_id);

COMMENT ON TABLE match_participant IS '참가자의 원래 요청(slot A=호스트/B=신청자), 수락 여부와 매칭 이력을 저장';
COMMENT ON COLUMN match_participant.id IS '참여 기록 고유 ID';
COMMENT ON COLUMN match_participant.activity_match_id IS '참여한 매칭 ID';
COMMENT ON COLUMN match_participant.match_request_id IS '이 참가자가 등록했던 게시글/조건 ID';
COMMENT ON COLUMN match_participant.user_id IS '참가 사용자 ID. 게시글 소유자와 일치';
COMMENT ON COLUMN match_participant.slot IS '참가자 자리 A(호스트) 또는 B(신청자)';
COMMENT ON COLUMN match_participant.accept_status IS '응답 상태: 대기, 수락, 거절';
COMMENT ON COLUMN match_participant.responded_at IS '수락 또는 거절 시각';
COMMENT ON COLUMN match_participant.released_at IS '활성 연결 해제 시각. NULL이면 현재 유효한 연결';


-- ------------------------------------------------------------
-- 4. matching_mutex — 동시 신청 경합을 막기 위한 공용 잠금 행
--    users를 참조하지 않는 완전히 독립적인 테이블. 신청(apply) 트랜잭션 시작 시
--    "SELECT * FROM matching_mutex WHERE id = 1 FOR UPDATE"로 이 한 행을 먼저 잠그면,
--    동시에 들어온 여러 신청 트랜잭션이 순서대로(한 번에 하나씩) 처리되도록 강제할 수 있다.
--    행이 하나뿐이라 잠금 경합이 곧 "매칭 관련 쓰기는 한 번에 하나만"이라는 규칙이 된다.
-- ------------------------------------------------------------
CREATE TABLE matching_mutex (
    -- 항상 1 고정값만 허용(아래 CHECK). 이 값 자체는 의미가 없고, 잠글 대상 행이 하나만
    -- 존재하게 만드는 용도.
                                id BIGINT PRIMARY KEY,
                                CONSTRAINT chk_matching_mutex_singleton CHECK (id = 1)
);
COMMENT ON TABLE matching_mutex IS '매칭 상태 변경을 직렬화하기 위한 공용 DB 잠금 행';
COMMENT ON COLUMN matching_mutex.id IS '고정값 1. 상태 변경 트랜잭션이 먼저 잠그는 행';

-- 서비스 시작 전에 잠글 행이 미리 존재해야 하므로, 마이그레이션 시점에 1건을 심어둔다.
INSERT INTO matching_mutex(id) VALUES (1);


-- ============================================================
-- 애플리케이션 계층 필수 구현 항목 (참고용 — V1의 형식을 따름)
-- ============================================================
-- 게시글(match_request) 생성 Service: search_expires_at 계산 로직 (scheduled_at 기준 역산)
--                                    ※ scheduled_at을 너무 임박하게(예: 몇 분~1시간 이내) 등록하면
--                                      역산된 search_expires_at이 created_at보다 앞설 수 있어
--                                      chk_match_request_time 위반(500 에러)이 날 수 있음.
--                                      DB에 저장 시도하기 전에 "scheduled_at은 지금부터 최소
--                                      N시간 이후여야 한다" 같은 사전 검증을 넣고, 위반 시
--                                      400으로 응답할 것 (activity_match의 decision_expires_at
--                                      계산도 같은 이유로 동일한 사전 검증이 필요함)
-- 게시글 생성 Service: 이미 활성 게시글/참여가 있으면(uq_match_request_active_user 위반) 예외 처리
-- 모집 탭 목록 Service: status='SEARCHING' + activity_type='RUN' 조건으로만 조회, 본인 글 제외
-- 신청(apply) Service: matching_mutex 행을 SELECT ... FOR UPDATE로 먼저 잠그고 트랜잭션 시작
-- 신청 Service: 게시글이 여전히 SEARCHING인지 재확인 후 activity_match(PROPOSED) 생성
-- 신청 Service: match_participant 2건 생성 — 호스트(slot A, PENDING), 신청자(slot B, ACCEPTED)
-- 신청 Service: 신청 성공 시 양쪽 match_request.status를 PENDING_CONFIRMATION으로 전이
-- 호스트 수락 Service: 호스트 참여 행을 ACCEPTED로 변경 → 양쪽 ACCEPTED면 activity_match를
--                      CONFIRMED로 전이 + confirmed_at 기록 + meeting_code(6자리) 발급 +
--                      양쪽 match_request.status를 MATCHED로 전이
-- 호스트 거절 Service: 참여 행 REJECTED + activity_match REJECTED + closed_at/closed_by_user_id
--                      기록 + 양쪽 match_request.status를 다시 SEARCHING으로 되돌림
-- 자동 만료 Scheduler: decision_expires_at이 지난 PROPOSED 건을 거절과 동일하게 처리
-- 자동 만료 Scheduler: search_expires_at이 지난 SEARCHING 게시글을 EXPIRED로 전이
-- Entity: MatchRequest/ActivityMatch/MatchParticipant의 user_id는 User Entity 참조 없이
--         Long으로만 다룸(도메인 간 결합도를 낮추기 위한 의도적 선택)