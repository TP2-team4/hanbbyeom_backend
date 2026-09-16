package com.team4.hanbbyeom.run.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "running_course")
public class RunningCourse {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String routeDescription; // 코스 경로 설명

    @Builder
    public RunningCourse(String name, String routeDescription) {
        this.name = name;
        this.routeDescription = routeDescription;
    }
}
