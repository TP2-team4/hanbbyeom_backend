package com.team4.hanbbyeom.global.time;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

// 테스트에서 "지금"을 원하는 시각으로 바꿔 가며 쓰는 Clock. TimeConfig의 Clock 빈(UTC)을 대신해
// @TestBean으로 컨텍스트에 넣고, 테스트 중간에 setInstant()로 시각을 옮긴다.
//
// Mockito 목(@MockitoBean Clock) 대신 이 클래스를 쓰는 이유: @EnableScheduling 때문에 테스트 컨텍스트에서도
// MatchExpireScheduler가 돌고, #94 이후 스케줄러 경로(MatchDecisionService)가 Clock 빈을 읽는다. Mockito 스텁은
// 스레드 안전하지 않아서 테스트 스레드가 when(clock.instant())...thenReturn(...)을 하는 사이에 스케줄러 스레드가
// 같은 목을 호출하면 스텁이 엉뚱한 메서드에 붙는다(실제로 getZone()에 Instant가 스텁돼 ClassCastException으로
// 깨진 이력이 있음). volatile 필드 하나면 어느 스레드가 읽어도 안전하다.
public class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant initial) {
        this(initial, ZoneOffset.UTC);
    }

    public MutableClock(Instant initial, ZoneId zone) {
        this.instant = initial;
        this.zone = zone;
    }

    public void setInstant(Instant instant) {
        this.instant = instant;
    }

    public void setInstant(OffsetDateTime dateTime) {
        this.instant = dateTime.toInstant();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant, zone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
