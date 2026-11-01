package org.example.backend.subscription.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.example.backend.auth.security.CustomUserDetails;
import org.example.backend.common.dto.ApiResponse;
import org.example.backend.subscription.dto.response.SubscriptionResponse;
import org.example.backend.subscription.service.SubscriptionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "구독", description = "유료 구독 신청/취소/조회 API")
@RestController
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    // 구독 시작은 결제 검증을 통과한 경로에서만 일어나야 하므로 별도 엔드포인트를 두지 않는다.
    // PortOne 연동 이전에 쓰던 POST /api/subscriptions는 결제 확인 없이 구독을 만들어 주는 통로였기에 제거했다.
    // 지금은 PaymentService가 결제 단건 재조회로 검증한 뒤 SubscriptionService.subscribe()를 직접 호출한다.

    @Operation(summary = "구독 해지", description = "다음 회차 자동 갱신만 멈춘다. 만료일까지는 계속 이용할 수 있다.")
    @DeleteMapping("/api/subscriptions")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> cancel(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        SubscriptionResponse response = subscriptionService.cancel(userDetails.getUser().getId());
        return ResponseEntity.ok(ApiResponse.success("자동 갱신이 해지되었습니다.", response));
    }

    @Operation(summary = "내 구독 조회")
    @GetMapping("/api/subscriptions/me")
    public ResponseEntity<ApiResponse<SubscriptionResponse>> getMy(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        SubscriptionResponse response = subscriptionService.getMy(userDetails.getUser().getId());
        return ResponseEntity.ok(ApiResponse.success("구독 조회 성공", response));
    }
}