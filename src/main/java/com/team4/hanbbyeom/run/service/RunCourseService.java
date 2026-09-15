package com.team4.hanbbyeom.run.service;


import com.team4.hanbbyeom.run.dto.RunCourseResponse;
import com.team4.hanbbyeom.run.repository.RunningCourseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 러닝 코스 관련 비즈니스 로직을 처리하는 서비스 클래스
// 컨트롤러에서 요청을 받아, DB 조회 및 DTO 변환 등의 로직을 수행하고 결과를 반환함

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RunCourseService {

    // 전체 러닝 코스 목록 조회에 필요한 JPA Repository 객체 주입
    private final RunningCourseRepository runningCourseRepository;
    // @return DB에서 조회한 RunningCourse 엔티티 목록을 RunCourseResponse DTO 리스트로 변환하여 반환
    public List<RunCourseResponse> getCourses() {
        // 1. runningCourseRepository.findAll() : DB에서 전체 RunningCourse 엔티티 목록을 조회
        // 2. .stream()                         : 엔티티 리스트를 자바 스트림으로 변환하여 순회 준비
        // 3. .map(RunCourseResponse::from)     : 각 엔티티 객체를 DTO 객체(RunCourseResponse)로 1:1 변환
        // 4. .toList()                         : 변환된 DTO 스트림을 최종 List 컬렉션으로 모아서 반환 (Java 16+)
        return runningCourseRepository.findAll().stream()
                .map(RunCourseResponse::from)
                .toList();
    }
}
