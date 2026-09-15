package com.team4.hanbbyeom.matching.exception;

public class AlreadyHasActiveMatchRequestException extends RuntimeException {
    public AlreadyHasActiveMatchRequestException(String message) {
        super(message);
    }
}
