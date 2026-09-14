package com.team4.hanbbyeom.run.repository;

import com.team4.hanbbyeom.run.domain.RunningCourse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
public class RunningCourseRepositoryTest {

    @Autowired
    private RunningCourseRepository runningCourseRepository;

    @Test
    void 시딩된_코스_5개가_조회된다() {
        List<RunningCourse> courses = runningCourseRepository.findAll();

        assertThat(courses).hasSize(5);
    }

    @Test
    void 코스를_저장하고_다시_조회할_수_있다() {
        RunningCourse course = RunningCourse.builder()
                .name("테스트 코스")
                .routeDescription("테스트 설명")
                .build();

        RunningCourse saved = runningCourseRepository.save(course);
        RunningCourse found = runningCourseRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getName()).isEqualTo("테스트 코스");
    }
}