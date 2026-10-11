package org.teamsai.saibackend.domain.settlement.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.teamsai.saibackend.domain.settlement.dto.request.RecurringSettlementCreateRequest;
import org.teamsai.saibackend.domain.settlement.dto.response.RecurringSettlementCreateResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.RecurringSettlementCycleListResponse;
import org.teamsai.saibackend.domain.settlement.service.RecurringSettlementQueryService;
import org.teamsai.saibackend.domain.settlement.service.RecurringSettlementService;
import org.teamsai.saibackend.global.security.CustomUserDetails;

@Tag(
        name = "정기정산 API",
        description = "정기정산 설정 및 관리 API"
)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/settlements/recurring")
public class RecurringSettlementController {

    private final RecurringSettlementService recurringSettlementService;
    private final RecurringSettlementQueryService recurringSettlementQueryService;

    @Operation(
            summary = "정기정산 생성",
            description = "정기정산을 등록하고 최초 정산 회차를 생성합니다."
    )
    @PostMapping
    public ResponseEntity<RecurringSettlementCreateResponse>
    createRecurringSettlement(
            @AuthenticationPrincipal CustomUserDetails userDetails,

            @Valid
            @RequestBody RecurringSettlementCreateRequest request
    ) {

        RecurringSettlementCreateResponse response =
                recurringSettlementService.create(
                        userDetails.getUserId(),
                        request
                );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @Operation(
            summary = "정기정산 회차별 현황 조회",
            description = "정기정산에서 생성된 회차(진행 중·종료 모두)의 정산 상태와 납부 현황을 최신 회차부터 조회합니다. " +
                    "생성자는 전체 회차를, 참여자는 본인이 참여 중인 회차만 조회합니다."
    )
    @GetMapping("/{recurringSettlementId}/cycles")
    public RecurringSettlementCycleListResponse getCycles(
            @AuthenticationPrincipal CustomUserDetails userDetails,

            @PathVariable Long recurringSettlementId
    ) {
        return recurringSettlementQueryService.getCycles(
                recurringSettlementId,
                userDetails.getUserId()
        );
    }
}
