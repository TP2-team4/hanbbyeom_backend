package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.dto.MatchRequestCreateRequest;
import com.team4.hanbbyeom.matching.exception.InvalidMatchRequestException;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.run.service.RunConditionService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class MatchRequestCommandServiceTest {

    private final MatchRequestRepository matchRequestRepository = mock(MatchRequestRepository.class);
    private final RunConditionService runConditionService = mock(RunConditionService.class);
    private final MatchRequestCommandService service = new MatchRequestCommandService(matchRequestRepository, runConditionService);

    @Test
    void 시작_시각이_너무_임박하면_예외가_발생한다() {
        MatchRequestCreateRequest request = new MatchRequestCreateRequest(
                1L,
                "뚝섬유원지역 3번 출구",
                7000,
                9000,
                360,
                400,
                OffsetDateTime.now().plusMinutes(30), // 최소 리드타임(3시간)보다 훨씬 임박함
                "SILENT"
        );

        assertThatThrownBy(() -> service.create(1L, request))
                .isInstanceOf(InvalidMatchRequestException.class);
    }
}