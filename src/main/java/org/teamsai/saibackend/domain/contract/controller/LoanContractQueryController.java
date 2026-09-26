package org.teamsai.saibackend.domain.contract.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.teamsai.saibackend.domain.archive.entity.ArchiveStatus;
import org.teamsai.saibackend.domain.archive.service.ArchiveService;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.service.LoanContractService;
import org.teamsai.saibackend.global.security.CustomUserDetails;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Tag(
        name = "차용증 API",
        description = "차용증 상세 내용과 보관된 차용증 PDF를 조회하는 API"
)
@RestController
@RequiredArgsConstructor
public class LoanContractQueryController {

    private final LoanContractService contractService;
    private final ArchiveService archiveService;

    @Operation(
            summary = "차용증 상세 조회",
            description = "로그인이 된 사용자가 차용증 ID로 차용증 상세 내용을 조회합니다."
    )
    @GetMapping(value = "/api/contracts/{contractId}/listdetails")
    public LoanContractResponse getContractDetails(
            @PathVariable Long contractId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return contractService.findContract(contractId, userDetails.getUserId());
    }

    @GetMapping("/api/contracts/{contractId}")
    public LoanContractResponse getContract(
            @PathVariable Long contractId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return contractService.findContract(contractId, userDetails.getUserId());
    }

    @Operation(
            summary = "차용증 보관함 PDF 조회",
            description = "이전에 저장된 차용증 PDF 파일이 있으면 그 파일을 그대로 내려줍니다. 저장된 파일이 없으면 404를 반환합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "저장된 PDF가 없음"),
            @ApiResponse(responseCode = "403", description = "해당 차용증에 대한 접근 권한이 없음")
    })
    @GetMapping("/api/contracts/{contractId}/pdf")
    public ResponseEntity<Resource> getContractPdf(
            @PathVariable Long contractId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        contractService.findContract(contractId, userDetails.getUserId());

        List<org.teamsai.saibackend.domain.archive.entity.ArchiveFile> savedFiles =
                archiveService.findFilesByReference(ArchiveStatus.CONTRACT, contractId);
        if (savedFiles.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        org.teamsai.saibackend.domain.archive.entity.ArchiveFile latestFile = savedFiles.get(0);
        Resource resource = archiveService.loadFileAsResource(latestFile.getSavedFilename());

        String encodedFilename = URLEncoder.encode(latestFile.getOriginalFilename(), StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("application/pdf"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFilename)
                .body(resource);
    }
}