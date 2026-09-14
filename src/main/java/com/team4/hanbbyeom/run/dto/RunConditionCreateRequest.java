package com.team4.hanbbyeom.run.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RunConditionCreateRequest(
    @NotNull
    Long matchRequestId,

    @NotNull
    Long courseId,

    @NotBlank
    String meetingPoint,

    @NotNull
    Integer distanceMinMeters,

    @NotNull
    Integer distanceMaxMeters,

    @NotNull
    Integer paceMinSec,

    @NotNull
    Integer paceMaxSec
) {
}