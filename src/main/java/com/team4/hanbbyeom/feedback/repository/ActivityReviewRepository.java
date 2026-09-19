package com.team4.hanbbyeom.feedback.repository;

import com.team4.hanbbyeom.feedback.domain.ActivityReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ActivityReviewRepository extends JpaRepository<ActivityReview, Long> {

    boolean existsByActivityMatchIdAndReviewerUserId(Long activityMatchId, Long reviewerUserId);

    // 메서드 이름 추론(Query Method)만으로는 "activityMatchId 필드 하나만 뽑기"와
    // "activityMatchId로 필터링하기"가 이름에 동시에 등장해서 Spring이 이걸 프로젝션으로
    // 인식하지 못하는 문제가 있었음 — @Query로 JPQL을 직접 써서 명확하게 지정
    @Query("SELECT ar.activityMatchId FROM ActivityReview ar " +
            "WHERE ar.reviewerUserId = :reviewerUserId AND ar.activityMatchId IN :activityMatchIds")
    List<Long> findActivityMatchIdByReviewerUserIdAndActivityMatchIdIn(
            @Param("reviewerUserId") Long reviewerUserId,
            @Param("activityMatchIds") Collection<Long> activityMatchIds);
}