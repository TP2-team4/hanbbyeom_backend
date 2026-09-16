package com.team4.hanbbyeom.run.exception;

public class RunMatchConditionNotFoundException extends RuntimeException {
    public RunMatchConditionNotFoundException(Long matchRequestId) {
        super("존재하지 않는 러닝 조건이에요. id=" + matchRequestId);
    }
}
