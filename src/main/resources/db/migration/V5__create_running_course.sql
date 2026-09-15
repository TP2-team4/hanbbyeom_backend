-- 러닝 코스 정보를 저장하는 테이블 생성
CREATE TABLE running_course (
    -- PK, INSERT 시 값 안 넣어도 DB가 1씩 자동으로 채번함 (Entity의 @GeneratedValue(IDENTITY)와 대응)

    id BIGSERIAL PRIMARY KEY,

    -- 코스 이름 (예: "뚝섬 한강공원"), 필수값이라 NOT NULL
    name VARCHAR(100) NOT NULL,

    -- 코스 설명 문구 (예: "성수대교~영동대교·평지·야간 조명 좋음")
    route_description VARCHAR(255)
);

-- 초기 코스 데이터 5개 시딩 (#14)
-- 화면 디자인(03-1)에 나온 코스 이름과 설명 문구를 그대로 사용
INSERT INTO running_course (name, route_description) VALUES
    ('뚝섬 한강공원', '성수대교~영동대교·평지·야간 조명 좋음'),
    ('여의도 한강공원', '마포대교~원효대교·넓은 산책로'),
    ('잠실 한강공원', '잠실대교~올림픽대교·강변 직선 구간'),
    ('반포 한강공원', '반포대교~동작대교·분수 구간 초점'),
    ('안양천', '구로~금천 한강 대비 한적');
