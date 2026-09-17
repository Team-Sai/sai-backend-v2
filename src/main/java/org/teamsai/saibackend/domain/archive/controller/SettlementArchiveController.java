package org.teamsai.saibackend.domain.archive.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.teamsai.saibackend.domain.archive.service.SettlementArchiveService;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementArchivePreviewResponse;

@Tag(
        name = "정산 보관함 API",
        description = "정산 이행현황 기록 조회 API"
)
@Slf4j
@Controller
@RequiredArgsConstructor
public class SettlementArchiveController {

    private final SettlementArchiveService settlementArchiveService;

    @Hidden
    @GetMapping("/archive/settlements/{settlementId}")
    public String archivePreviewPage(
            @PathVariable Long settlementId,
            Model model
    ) {
        model.addAttribute("settlementId", settlementId);
        return "archive/settlement-detail";
    }

    @Operation(
            summary = "정산 보관함 기록 조회",
            description = "정산 기본정보·이행현황·참여자별 납부 현황·상세 납부 내역·수취 계좌 정보를 조회합니다. " +
                    "종료된 정산은 최초 조회 시점에 고정된 기록을 반환하고, 진행 중인 정산은 요청마다 최신 이행현황을 반환합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
            @ApiResponse(responseCode = "403", description = "해당 정산에 대한 접근 권한이 없음"),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 정산")
    })
    @ResponseBody
    @GetMapping("/api/settlements/{settlementId}/archive-preview")
    public SettlementArchivePreviewResponse getArchivePreview(
            @Parameter(hidden = true)
            @AuthenticationPrincipal(expression = "userId")
            Long userId,

            @PathVariable
            Long settlementId
    ) {
        return settlementArchiveService.getArchivePreview(settlementId, userId);
    }
}
