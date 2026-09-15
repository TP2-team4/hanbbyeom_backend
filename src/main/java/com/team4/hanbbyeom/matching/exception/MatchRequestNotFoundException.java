package com.team4.hanbbyeom.matching.exception;

public class MatchRequestNotFoundException extends RuntimeException {
    public MatchRequestNotFoundException(Long id) {
        super("존재하지 않는 모집글이에요. id=" + id);
    }
}
