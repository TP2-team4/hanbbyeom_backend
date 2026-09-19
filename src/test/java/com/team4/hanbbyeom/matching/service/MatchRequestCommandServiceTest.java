package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.dto.MatchRequestCreateRequest;
import com.team4.hanbbyeom.matching.exception.InvalidMatchRequestException;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.run.service.RunConditionService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class MatchRequestCommandServiceTest {

    // 서비스가 TimeConfig의 Clock 빈으로 "지금"을 읽으므로(#94), 테스트에서는 고정 시계를 넣어
    // 실행 시점과 무관하게 항상 같은 결과가 나오게 한다.
    private static final Instant FIXED_NOW = Instant.parse("2026-09-19T10:00:00Z");
    private final Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private final MatchRequestRepository matchRequestRepository = mock(MatchRequestRepository.class);
    private final RunConditionService runConditionService = mock(RunConditionService.class);
    private final MatchRequestCommandService service =
            new MatchRequestCommandService(matchRequestRepository, runConditionService, fixedClock);

    @Test
    void 시작_시각이_너무_임박하면_예외가_발생한다() {
        MatchRequestCreateRequest request = new MatchRequestCreateRequest(
                1L,
                "뚝섬유원지역 3번 출구",
                7000,
                9000,
                360,
                400,
                OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC).plusMinutes(30), // 최소 리드타임(3시간)보다 훨씬 임박함
                "SILENT"
        );

        assertThatThrownBy(() -> service.create(1L, request))
                .isInstanceOf(InvalidMatchRequestException.class);
    }
}
