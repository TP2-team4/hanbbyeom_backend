package com.team4.hanbbyeom.global.security.jwt;

import com.team4.hanbbyeom.global.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

// Provider: 어떤 값이나 기능을 만들어서 다른 곳에 제공하는 객체(관례)
// JwtTokenProvider(이 파일): Service가 직접 JJWT 라이브러리 코드를 다루지 않게 대신 맡아주는 역할
// Service나 Filter는 세부 방법을 모른 채 Provider에게 필요한 기능 요청! (JWT 관련 기술 로직은 Provider에 다 모음)

@Component
public class JwtTokenProvider {

    // @ConfigurationProperties로 등록된 JwtProperties Bean을 생성자 주입받아 사용
    private final JwtProperties jwtProperties;

    // secretBase64(Base64 문자열)를 서명에 실제로 사용할 SecretKey 객체로 미리 변환해서 보관
    // (매번 토큰을 만들 때마다 변환하면 비효율적이라 생성 시점에 한 번만 계산)
    // 문자열을 그대로 getBytes()해서 키로 쓰면 안전하지 않다고 JJWT 공식 문서가 명시하고 있어서
    // Base64로 안전하게 생성해둔 값을 Decoders.BASE64.decode()로 복원하는 방식 사용
    private final SecretKey secretKey; // JWT에 서명하고 서명을 검증할 때 사용하는 비밀키 (서버만 알고있어야 함)

    // 생성자
    public JwtTokenProvider(JwtProperties jwtProperties) {

        // Spring이 주입해준 JwtProperties를 현재 객체의 필드에 저장
        // JwtTokenProvider 전체에서 JwtProperties 사용 가능
        this.jwtProperties = jwtProperties;

        // 설정 파일에 문자열 형태로 저장된 JWT 비밀키를 꺼내서,
        // 실제 JWT 서명/검증에 사용할 SecretKey 객체로 만들어 this.secretKey에 저장하는 코드

        // Keys: JJWT 라이브러리에서 제공하는 암호화 키 관련 유틸리티 클래스
        // hmacShaKeyFor: 전달받은 byte[]를 HMAC-SHA 방식에서 사용할 수 있는 SecretKey 객체로 변환하는 메서드
        this.secretKey = Keys.hmacShaKeyFor(
                // Decoders: 여러 인코딩 형식을 다시 원래 데이터로 되돌릴 때 쓰는 도구 모음
                Decoders // JJWT 라이브러리에서 제공하는 Decoders 모음 클래스
                        .BASE64 // Base64용 디코더
                        .decode( // Base64 Decoder의 decode() 메서드를 호출
                                // JwtProperties에 저장된 JWT 비밀키의 Base64 문자열을 가져옴
                                jwtProperties.secretBase64()
                        )
        );
    }


    // Access Token 생성 (로그인 성공 시 발급, 짧은 만료시간)
    // 실제 JWT 만드는 로직은 둘 다 공통으로 createToken()에 맡기고, 여기서는 어떤 만료시간을 쓸지만 다르게 전달
    public String createAccessToken(Long userId) {
        return createToken(
                userId, // Access Token을 만들 사용자 Id
                jwtProperties.accessTokenExpiration() // 설정 파일에서 읽어온 Access Token의 유효시간
        );
    }

    // Refresh Token 생성 (Access Token 재발급용, 긴 만료시간)
    // 실제 JWT 만드는 로직은 둘 다 공통으로 createToken()에 맡기고, 여기서는 어떤 만료시간을 쓸지만 다르게 전달
    public String createRefreshToken(Long userId) {
        return createToken(
                userId, // Refresh Token을 만들 사용자 Id
                jwtProperties.refreshTokenExpiration() // 설정 파일에서 읽어온 Refresh Token의 유효시간
        );
    }


    // userId와 유효시간을 받아 JWT 문자열을 생성하는 메서드
    private String createToken(
            Long userId, // 토큰을 발급받는 사용자 Id
            Duration validity // 이 토큰을 얼마 동안 유효하게 할지 나타내는 시간 길이
    ) {
        Instant now = Instant.now(); // 현재 시각 가져옴 (발급시각, 만료시각 계산에 사용)

        return Jwts.builder() // JJWT 라이브러리에서 JWT를 만들기 위한 Builder 객체를 생성

                // Claims: Claim(JWT Payload 안의 정보)들을 Java에서 다룰 수 있게 담아놓은 객체

                // JWT의 Subject(sub: 누구에 대한 토큰인지) Claim 설정
                // JWT의 Subject는 문자열로 저장하므로 Long을 String으로 변환해 저장
                .subject(String.valueOf(userId))

                // JWT의 Issuer(iss: 누가 토큰을 발급했는지) Claim 설정
                .issuer(jwtProperties.issuer())

                // JWT의 Issued At(iat: 이 토큰을 언제 발급했는가) Claim을 설정
                // JJWT가 여기서는 Date 객체를 받으므로 Instant를 Date로 변환해 저장
                .issuedAt(Date.from(now))

                // JWT의 Expiration(exp: 이 토큰이 언제 만료되는지) Claim으로 저장
                // 현재 시간 + 토큰 유효시간
                .expiration(Date.from(now.plus(validity)))

                // signWith: 지금까지 만든 JWT에 SecretKey를 이용해서 서명(Signature)
                .signWith(secretKey)

                // 지금까지 설정한 모든 걸 최종 JWT 문자열로 만들어서 반환
                .compact();
    }


    // JWT의 서명, 형식, 만료시간, issuer를 검증한 뒤, 검증된 Payload(Claims)를 반환하는 메서드
    public Claims parseClaims(String token) {
        // parser(): JWT를 읽고 검증하기 위한 Parser 설정 시작
        return Jwts.parser()

                // verifyWith: secretKey를 사용해서 JWT의 Signature가 정상인지 검증
                .verifyWith(secretKey)

                // JWT 안의 iss Claim이 반드시 지정한 값과 같아야 한다는 검증 조건을 추가
                .requireIssuer(jwtProperties.issuer())

                // 설정한 조건으로 Parser 완성
                .build()

                // 전달받은 서명된 JWT 문자열을 실제로 파싱하고 검증(앞에 설정한 검증조건으로)하는 메서드
                // 문제가 있으면 예외 발생, 정상이면 파싱된 JWT 결과 객체 반환
                .parseSignedClaims(token)

                // 파싱과 검증이 끝난 JWT에서 Payload 부분(Claims 객체)을 꺼내는 메서드
                // JWT 구조: Header.Payload.Signature
                .getPayload();
    }


    // validateToken: JWT 문자열을 받아서 유효하면 true, 유효하지 않으면 false를 반환하는 메서드
    public boolean validateToken(String token) {
        try {
            // 토큰이 유효한지(서명 위조 없음 + 형식 정상 + 만료 전 + issuer 일치) 확인
            // 예외 없음 → JWT 검증에 성공
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // JwtException: JWT 관련 오류를 나타내는 상위 예외 타입
            // IllegalArgumentException: 전달한 인자 자체가 잘못됐을 때 발생할 수 있는 Java 예외
            // 둘 중 하나만 발생해도 false
            return false;
        }
    }


    // JWT 검증이 끝난 뒤, 이 요청을 보낸 사용자가 누구인지 알아낼 때 쓰는 메서드
    public Long getUserId(String token) {
        return Long.valueOf( // String 형태의 userId를 Long 타입으로 변환
                // JWT를 파싱·검증하고 반환된 Claims 객체의 sub(Subject)에 저장된 userId 문자열을 꺼냄
                parseClaims(token).getSubject()
        );
    }
}
