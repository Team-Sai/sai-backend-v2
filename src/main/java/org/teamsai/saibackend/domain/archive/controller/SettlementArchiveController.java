package org.teamsai.saibackend.domain.archive.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.teamsai.saibackend.domain.archive.entity.ArchiveStatus;
import org.teamsai.saibackend.domain.archive.entity.File;
import org.teamsai.saibackend.domain.archive.service.ArchiveService;
import org.teamsai.saibackend.domain.archive.service.SettlementArchiveService;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementArchivePreviewResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Tag(
        name = "정산 보관함 API",
        description = "정산 이행현황 기록 조회 API"
)
@Slf4j
@Controller
@RequiredArgsConstructor
public class SettlementArchiveController {

    private final SettlementArchiveService settlementArchiveService;
    private final ArchiveService archiveService;

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

    @Operation(
            summary = "정산 보관함 PDF 조회",
            description = "이전에 저장된 정산 PDF 파일이 있으면 그 파일을 그대로 내려줍니다. 저장된 파일이 없으면 404를 반환합니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "저장된 PDF가 없음"),
            @ApiResponse(responseCode = "403", description = "해당 정산에 대한 접근 권한이 없음")
    })
    @GetMapping("/api/settlements/{settlementId}/pdf")
    public ResponseEntity<Resource> getSettlementPdf(
            @Parameter(hidden = true)
            @AuthenticationPrincipal(expression = "userId")
            Long userId,

            @PathVariable
            Long settlementId
    ) {
        settlementArchiveService.getArchivePreview(settlementId, userId);

        List<File> savedFiles = archiveService.findFilesByReference(ArchiveStatus.SETTLEMENT, settlementId);
        if (savedFiles.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        File latestFile = savedFiles.get(0);
        Resource resource = archiveService.loadFileAsResource(latestFile.getSavedFilename());

        return pdfResponse(resource, latestFile.getOriginalFilename());
    }

    @Operation(
            summary = "정산 보관함 PDF 저장",
            description = "클라이언트에서 생성한 정산 PDF를 보관합니다. 정산이 종료(CLOSED) 상태가 아니면 저장하지 않습니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "저장 성공(또는 저장 대상이 아니어서 건너뜀)"),
            @ApiResponse(responseCode = "403", description = "해당 정산에 대한 접근 권한이 없음")
    })
    @PostMapping("/api/settlements/{settlementId}/pdf")
    @ResponseBody
    public ResponseEntity<Void> saveSettlementPdf(
            @Parameter(hidden = true)
            @AuthenticationPrincipal(expression = "userId")
            Long userId,

            @PathVariable
            Long settlementId,

            @RequestParam("file")
            MultipartFile file
    ) {
        SettlementArchivePreviewResponse preview = settlementArchiveService.getArchivePreview(settlementId, userId);

        if ("CLOSED".equals(preview.settlementStatus())) {
            archiveService.saveFile(ArchiveStatus.SETTLEMENT.name(), settlementId, file);
        }

        return ResponseEntity.ok().build();
    }

    private ResponseEntity<Resource> pdfResponse(Resource resource, String originalFilename) {
        String encodedFilename = URLEncoder.encode(originalFilename, StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("application/pdf"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFilename)
                .body(resource);
    }
}
