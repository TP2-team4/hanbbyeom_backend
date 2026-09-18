package com.team4.hanbbyeom.matching.exception;

public class MatchRequestNotFoundException extends RuntimeException {
    public MatchRequestNotFoundException(Long id) {
        super("존재하지 않는 모집글이에요. id=" + id);
    }

    // id로 특정할 수 없는 경우(예: "내 활성 모집글이 아예 없음")에 쓰는 오버로드.
    public MatchRequestNotFoundException(String message) {
        super(message);
    }
}
