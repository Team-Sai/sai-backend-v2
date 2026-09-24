package org.teamsai.saibackend.domain.contract.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.teamsai.saibackend.domain.contract.dto.response.DashboardResponse;
import org.teamsai.saibackend.domain.contract.service.DashboardQueryService;

@Tag(
        name = "차용증 API",
        description = "로그인한 사용자의 계약 목록을 요약, 검색, 정렬, 페이지네이션 하여 조회하는 API"
)
@RestController
@RequiredArgsConstructor
public class DashboardQueryController {

    private final DashboardQueryService dashboardQueryService;

    @Operation(
            summary = "계약 대시보드 조회",
            description = "로그인한 사용자가 채권자 또는 채무자로 참여 중인 계약 목록과 요약 정보를 조회합니다. " +
                    "키워드 검색, 역할 필터(전체/대여금/차용금), 정렬, 페이지네이션을 지원합니다."
    )

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "400", description = "roleFilter 또는 sortType 값이 유효하지 않음"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자")
    })

    @GetMapping("/api/dashboard")
    public DashboardResponse getDashboard(
            @AuthenticationPrincipal(expression = "userId") Long userId,
            @Parameter(description = "계약명 검색 키워드") @RequestParam(required = false) String keyword,

            @Parameter(description = "역할 필터 (ALL, LENT, BORROWED", example = "ALL")
            @RequestParam(required = false) String roleFilter,
            @RequestParam(required = false) String statusFilter,

            @Parameter(description = "정렬 기준 (ALPHABET, ROLE, CATEGORY, AMOUNT_DESC, AMOUNT_ASC, STATUS, DEADLINE")
            @RequestParam(required = false) String sortType,

            @Parameter(description = "페이지 번호 (1부터 시작)", example = "1")
            @RequestParam(defaultValue = "1") int page
    ) {
        return dashboardQueryService.getDashboard(userId, keyword, roleFilter, statusFilter, sortType, page);
    }

}
