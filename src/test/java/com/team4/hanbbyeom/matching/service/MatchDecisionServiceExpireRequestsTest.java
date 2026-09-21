package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 모집 기한이 지난 게시글 만료(expireOverdueRequests)의 락 순서와 시각 사용을 검증한다(이슈 #107).
class MatchDecisionServiceExpireRequestsTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-19T10:00:00Z");

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MatchDecisionService service = new MatchDecisionService(
            mock(MatchRequestRepository.class), mock(ActivityMatchRepository.class),
            mock(MatchParticipantRepository.class), jdbcTemplate, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

    // 다른 상태 전이(신청·취소·수락·거절)와 같은 matching_mutex 락을 먼저 잡아야 한다. 락 없이 갱신하면 동시에 들어온
    // 신청·취소와 순서가 섞여 게시글과 매칭이 어긋날 수 있다.
    @Test
    void 만료_처리는_갱신하기_전에_matching_mutex_락을_먼저_잡는다() {
        service.expireOverdueRequests();

        InOrder order = inOrder(jdbcTemplate);
        order.verify(jdbcTemplate).queryForObject(contains("matching_mutex"), eq(Long.class));
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
        order.verify(jdbcTemplate).update(contains("UPDATE match_request"), eq(now), eq(now));
    }

    @Test
    void 만료된_건수를_반환한다() {
        when(jdbcTemplate.update(contains("UPDATE match_request"), org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any())).thenReturn(3);

        assertThat(service.expireOverdueRequests()).isEqualTo(3);
    }
}
