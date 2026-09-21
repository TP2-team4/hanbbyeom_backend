-- 이미 종료(ENDED)된 활동의 완료한 활동 수를 소급 집계한다(#96).
--
-- 완료한 활동은 activity_match가 ENDED로 전환될 때 두 참가자 모두 +1로 집계하기로 확정되었다(후기·노쇼 신고 여부와 무관).
-- 이 집계는 이 마이그레이션 이후 코드(MatchDecisionService)가 하므로, 그 전에 종료된 활동은 여기서 한 번에 채운다.
-- CANCELLED·EXPIRED·REJECTED와 아직 종료되지 않은 CONFIRMED는 세지 않는다(CONFIRMED는 이후 스케줄러가 종료하며 집계한다).
--
-- 증가(+1)가 아니라 계산한 값으로 설정한다 — 기존 값은 그동안 집계 코드가 없어 항상 0이었고, 이렇게 하면 재실행해도 결과가 같다.
-- trust_profile 행이 없는 사용자는 새로 만들며, 후기가 없는 새 행의 평균 별점은 NULL이다(V16에서 기본값 제거).
INSERT INTO trust_profile (user_id, completed_activity_count)
SELECT mp.user_id, COUNT(*)
FROM match_participant mp
JOIN activity_match am ON am.id = mp.activity_match_id
WHERE am.status = 'ENDED'
GROUP BY mp.user_id
ON CONFLICT (user_id) DO UPDATE SET
    completed_activity_count = EXCLUDED.completed_activity_count,
    updated_at = now();

COMMENT ON COLUMN trust_profile.completed_activity_count IS
    '완료한 활동 수. 활동(activity_match)이 ENDED로 전환될 때 두 참가자 모두 +1. 후기·노쇼 신고 여부와 무관';
