package com.team4.hanbbyeom.matching.dto;

public record TrustProfileResponse(
        Double averageRating,   // 평가가 하나도 없으면 null
        Integer reviewCount,
        Integer completedCount,
        Integer noShowReportCount
) {}