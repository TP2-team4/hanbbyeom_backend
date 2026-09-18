package com.team4.hanbbyeom.feedback.repository;

import com.team4.hanbbyeom.feedback.domain.NoShowReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface NoShowReportRepository extends JpaRepository<NoShowReport, Long> {

    boolean existsByActivityMatchIdAndReporterUserId(Long activityMatchId, Long reporterUserId);

    @Query("SELECT nsr.activityMatchId FROM NoShowReport nsr " +
            "WHERE nsr.reporterUserId = :reporterUserId AND nsr.activityMatchId IN :activityMatchIds")
    List<Long> findActivityMatchIdByReporterUserIdAndActivityMatchIdIn(
            @Param("reporterUserId") Long reporterUserId,
            @Param("activityMatchIds") Collection<Long> activityMatchIds);
}