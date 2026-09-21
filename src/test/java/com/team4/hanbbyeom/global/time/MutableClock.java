package com.team4.hanbbyeom.global.time;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

// 테스트에서 "지금"을 원하는 시각으로 바꿔 가며 쓰는 Clock. TimeConfig의 Clock 빈(UTC)을 대신해
// @TestBean으로 컨텍스트에 넣고, 테스트 중간에 setInstant()로 시각을 옮긴다.
//
// Mockito Clock Mock 대신 스레드 간에 안전하게 사용할 수 있는 테스트용 Clock을 사용한다.
//
// 동일한 Mock에 대해 테스트 스레드가 stubbing을 설정하는 동안
// 다른 스레드가 해당 Mock을 호출하면 stubbing이 잘못 연결되어 테스트가 불안정해질 수 있다.
// 이 클래스는 현재 시간을 volatile 필드로 관리하므로 여러 스레드에서 안전하게 읽을 수 있다.
//
// 과거 #94 이후 MatchExpireScheduler가 테스트 컨텍스트에서도 실행되면서
// 스케줄러 스레드와 테스트 스레드가 같은 Clock Mock을 동시에 사용했고,
// 그 결과 getZone()에 잘못된 값이 stubbing되어 ClassCastException이 간헐적으로 발생했다.
//
// 현재는 테스트 환경에서 스케줄링을 비활성화했지만,
// 이후 Clock을 사용하는 다른 백그라운드 스레드가 추가되더라도 같은 문제가 발생하지 않도록
// Mockito Mock 대신 이 테스트용 Clock을 계속 사용한다.
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
