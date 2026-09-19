package com.team4.hanbbyeom.matching.exception;

// 호스트가 수락하려는 신청의 신청자가 이미 탈퇴했을 때 사용
// 요청 값이나 권한 문제가 아니라 매칭의 현재 상태 문제이므로 409로 응답
// (apply()의 호스트 탈퇴 검사는 탈퇴 여부를 숨기려고 이 예외가 아닌 "마감된 글" 예외를 그대로 쓴다.
//  이쪽은 호스트가 왜 수락할 수 없는지, 거절하면 되는지를 알려줘야 해서 별도 예외로 분리)
public class ApplicantWithdrawnException extends RuntimeException {
    public ApplicantWithdrawnException(String message) {
        super(message);
    }
}
