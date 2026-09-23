package org.teamsai.saibackend.domain.contract.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.teamsai.saibackend.domain.archive.service.ArchiveService;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDetailResponse;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.service.ContractDetailService;

import java.util.HashMap;
import java.util.Map;

@Tag(
        name = "차용증 API",
        description = "체결이 완료된 계약서 원문 내용을 조회하는 API"
)

@RestController
@RequiredArgsConstructor
public class ContractDetailQueryController {

    private final ContractDetailService contractDetailService;
    private final ArchiveService archiveService;

    @Operation(
            summary = "계약서 상세 조회",
            description = "계약 당사자(채권자 또는 채무자)가 계약서의 원금, 이자율, 기간, 상환방식, 특약사항 등 전체 내용을 조회합니다." +
                    "당사자가 아니면 조회할 수 없습니다."
    )

    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
            @ApiResponse(responseCode = "403", description = "계약 당사자가 아님"),
            @ApiResponse(responseCode = "404", description = "계약을 찾을 수 없음")
    })
    @GetMapping("/api/contracts/{contractId}/contract-detail")
    public ContractDetailResponse responseDetail(
            @PathVariable Long contractId,
            @AuthenticationPrincipal(expression = "userId") Long userId
    ) {
        return contractDetailService.getCheck(contractId, userId);
    }

    @Operation(
            summary = "차용증 서명 이미지 조회",
            description = "계약 당사자(채권자 또는 채무자)가 자신이 속한 계약의 서명 이미지를 data URI 형태로 조회합니다. " +
                    "서명이 아직 등록되지 않은 경우 해당 값은 null로 내려갑니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
            @ApiResponse(responseCode = "403", description = "계약 당사자가 아님"),
            @ApiResponse(responseCode = "404", description = "계약을 찾을 수 없음")
    })
    @GetMapping("/api/contracts/{contractId}/signatures")
    public Map<String, String> getSignatures(
            @PathVariable Long contractId,
            @AuthenticationPrincipal(expression = "userId") Long userId
    ) {
        LoanContractResponse contract = contractDetailService.getCheck(contractId, userId).getContract();

        Map<String, String> signatures = new HashMap<>();
        signatures.put("creditorSignatureDataUri", archiveService.loadSignatureDataUri(contract.getCreditorSignature()));
        signatures.put("debtorSignatureDataUri", archiveService.loadSignatureDataUri(contract.getDebtorSignature()));
        return signatures;
    }
}
