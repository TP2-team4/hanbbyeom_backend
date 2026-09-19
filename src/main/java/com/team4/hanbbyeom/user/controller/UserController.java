package com.team4.hanbbyeom.user.controller;

import com.team4.hanbbyeom.global.security.CustomUserDetails;
import com.team4.hanbbyeom.matching.dto.TrustProfileResponse;
import com.team4.hanbbyeom.matching.service.TrustProfileLookupService;
import com.team4.hanbbyeom.user.dto.UserNicknameResponse;
import com.team4.hanbbyeom.user.dto.UserNicknameUpdateRequest;
import com.team4.hanbbyeom.user.dto.UserPreferencesResponse;
import com.team4.hanbbyeom.user.dto.UserPreferencesUpdateRequest;
import com.team4.hanbbyeom.user.dto.UserResponse;
import com.team4.hanbbyeom.user.dto.UserWithdrawRequest;
import com.team4.hanbbyeom.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

// 사용자 본인 정보 조회 및 기본 설정 변경 API
@Tag(name = "User", description = "사용자 본인 정보 및 기본 설정 API")
// Swagger UI에서 Authorize로 입력한 Bearer 토큰을 이 API 호출에 사용 (SwaggerConfig에 정의)
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final TrustProfileLookupService trustProfileLookupService;

    // 설정 변경에는 DB에서 관리 중인 User Entity가 필요하므로 UserService를 통해 처리
    public UserController(UserService userService, TrustProfileLookupService trustProfileLookupService) {
        this.userService = userService;
        this.trustProfileLookupService = trustProfileLookupService;
    }

    // 내 정보 조회: GET /api/users/me

    // SecurityConfig의 anyRequest().authenticated() 대상이므로(permitAll로 따로 열어놓지 않음) 인증 없이는 접근 불가
    // 보호된 엔드포인트(Protected Endpoint): 인증된 사용자만 접근할 수 있는 API
    // Spring Security는 Controller 앞에서 동작하므로, 인증되지 않은 요청은 이 Controller까지 도달 불가

    // 내 정보 조회는 JWT 필터가 이미 조회한 사용자 정보를 읽기만 하므로 DB를 다시 조회하지 않음
    // 기본 대화 수준 변경은 DB에 저장해야 하므로, @Transactional UserService에서 사용자를 다시 조회한 뒤 값을 변경
    @Operation(
            summary = "내 정보 조회",
            description = "인증이 필요한 내 정보 조회 엔드포인트입니다."
    )
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(
            // @AuthenticationPrincipal: (Spring Security 제공) SecurityContext에 저장된 인증 정보의
            // principal(현재 인증된 사용자)을 파라미터로 바로 꺼내주는 어노테이션
            // → JwtAuthenticationFilter가 토큰을 검증하고 넣어둔 CustomUserDetails가 그대로 전달됨
            //   (요청 헤더로 사용자 ID를 직접 받지 않으므로 다른 사람을 사칭할 수 없음)
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        // 필터가 DB에서 이미 조회해둔 User를 그대로 사용 (재조회 없음)
        return ResponseEntity
                .ok(UserResponse.from(principal.getUser()));
    }

    // 기본 대화 수준 변경: PATCH /api/users/me/preferences
    @Operation(
            summary = "기본 대화 수준 변경",
            description = "인증된 사용자 본인의 기본 대화 수준을 변경합니다. "
                    + "기존에 작성한 모집글과 확정된 매칭의 대화 수준은 변경되지 않습니다."
    )
    @PatchMapping("/me/preferences")
    public ResponseEntity<UserPreferencesResponse> updatePreferences(
            // @Valid: 요청 DTO의 @NotNull 검증을 실행해 값 누락을 Service 호출 전에 차단
            @Valid @RequestBody UserPreferencesUpdateRequest request,
            // @AuthenticationPrincipal: JWT 필터가 토큰을 검증한 뒤 SecurityContext에 저장한 현재 인증 사용자 정보를 전달
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        // 현재 인증된 사용자의 ID로 설정 변경
        UserPreferencesResponse response = userService
                .updatePreferences(principal.getUserId(), request);

        return ResponseEntity.ok(response);
    }

    // 닉네임 변경: PATCH /api/users/me/nickname
    @Operation(
            summary = "닉네임 변경",
            description = "인증된 사용자 본인의 닉네임을 변경합니다. 회원가입과 같은 규칙(2자 이상 16자 이하)이 적용되며 "
                    + "다른 사용자와 중복될 수 있습니다. 변경된 닉네임은 모집 목록·상세, 신청 내역, 활동 이력 등 "
                    + "닉네임이 표시되는 모든 곳에 바로 반영됩니다."
    )
    @PatchMapping("/me/nickname")
    public ResponseEntity<UserNicknameResponse> updateNickname(
            // @Valid: 요청 DTO의 @NotBlank·@Size 검증을 실행해 잘못된 값을 Service 호출 전에 차단
            @Valid @RequestBody UserNicknameUpdateRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        // 현재 인증된 사용자의 ID로 닉네임 변경 (다른 사용자의 닉네임은 변경할 수 없음)
        UserNicknameResponse response = userService
                .updateNickname(principal.getUserId(), request);

        return ResponseEntity.ok(response);
    }

    // 내 신뢰도 프로필 조회: GET /api/users/me/trust-profile
    // 실제 조회 로직은 TrustProfileLookupService가 이미 갖고 있다 — 지금까지는
    // 호스트/신청자 프로필 조회(다른 사람 대상)에서만 쓰였는데, 본인 조회용 경로가 없었다.
    @Operation(
            summary = "내 신뢰도 프로필 조회",
            description = "인증된 사용자 본인의 평균 별점·완료한 활동·노쇼 신고 횟수를 조회합니다. "
                    + "활동 이력이 전혀 없으면 기본값(0, null)이 반환됩니다."
    )
    @GetMapping("/me/trust-profile")
    public ResponseEntity<TrustProfileResponse> myTrustProfile(
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return ResponseEntity.ok(trustProfileLookupService.lookup(principal.getUserId()));
    }

    // 회원 탈퇴: POST /api/users/me/withdraw
    // 요청 바디로 현재 비밀번호를 받아 본인 확인을 한다. 응답 바디는 없음.
    // 탈퇴 즉시 개인정보가 NULL로 지워지므로, 지금 쓰고 있는 access token으로
    // 다음 요청부터는 401이 난다(findByIdAndDeletedAtIsNull이 JWT 인증 필터에서도 쓰이므로).
    @Operation(
            summary = "회원 탈퇴",
            description = "인증된 사용자 본인 계정을 탈퇴 처리합니다. 현재 비밀번호를 다시 확인하며, 일치하지 않으면 "
                    + "403으로 거부됩니다. 이메일·비밀번호·닉네임·기본 대화 수준이 모두 제거되며, "
                    + "탈퇴 후에는 같은 이메일로 재가입할 수 있습니다. 되돌릴 수 없습니다."
    )
    @PostMapping("/me/withdraw")
    public ResponseEntity<Void> withdraw(
            @AuthenticationPrincipal CustomUserDetails principal,
            @Valid @RequestBody UserWithdrawRequest request
    ) {
        userService.withdraw(principal.getUserId(), request.password());
        return ResponseEntity.noContent().build();
    }
}
