package org.teamsai.saibackend.domain.contract.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.teamsai.saibackend.domain.archive.entity.ArchiveStatus;
import org.teamsai.saibackend.domain.archive.service.ArchiveService;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.dto.request.LoanContractRequest;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.service.LoanContractService;
import org.teamsai.saibackend.global.security.CustomUserDetails;

@Tag(
        name = "차용증 API",
        description = "차용증 작성과 저장, 채무자에게 전송 API"
)
@RestController
@RequiredArgsConstructor
@Slf4j
public class LoanContractController {

    private final LoanContractService contractService;
    private final ArchiveService archiveService;

    @Operation(
            summary = "차용증 최초 생성",
            description = "채권자가 입력한 정보로 차용증 계약서를 최초 생성합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "차용증 생성 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
            @ApiResponse(responseCode = "404", description = "잘못된 입력값 요청")
    })
    @PostMapping("/api/contracts/write")
    public Long createContract(
            @Valid @RequestBody LoanContractRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return contractService.createContract(request, userDetails.getUserId());
    }

    @Operation(
            summary = "채권자 전자서명 제출 및 전송",
            description = "채권자가 수기로 남긴 서명 이미지를 저장하고, 상태를 대기(PENDING)로 변경하여 채무자에게 전송합니다."
    )
    @PatchMapping(value = "/api/contracts/{contractId}/signature", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ContractStatus submitSignature(
            @PathVariable Long contractId,
            @Parameter(description = "채무자 회원 토큰", example = "SAI_ABCD1234")
            @RequestParam("debtorUserToken") String debtorUserToken,
            @RequestParam("signature") MultipartFile signature,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return contractService.submitCreditorSignature(contractId, userDetails.getUserId(), debtorUserToken, signature);
    }

    @Operation(
            summary = "채무자 계약 합류",
            description = "로그인한 사용자를 차용증의 채무자로 연결합니다. 이미 로그인 세션에서 본인 확인이 되어 있으므로 별도의 본인인증 절차 없이 사용자 토큰(인증 정보)만으로 연결합니다. 본인인증은 이후 전자서명 제출 단계에서 검증합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "채무자 연결 성공"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 사용자"),
            @ApiResponse(responseCode = "404", description = "차용증을 찾을 수 없음"),
            @ApiResponse(responseCode = "409", description = "이미 채무자가 연결되었거나 채권자 본인이 채무자로 연결을 시도함")
    })
    @PatchMapping("/api/contracts/{contractId}/debtor")
    public void linkDebtor(
            @PathVariable Long contractId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        contractService.linkDebtor(contractId, userDetails.getUserId());
    }

    @Operation(
            summary = "채무자 승인 및 전자서명 제출",
            description = "채무자가 본인 주소를 입력하고 수기로 남긴 서명 이미지를 저장한 뒤, 상태를 완료(COMPLETED)로 변경합니다. 제출 직전 본인인증을 완료한 identityVerificationId를 소비하여 검증합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "서명 제출 성공"),
            @ApiResponse(responseCode = "400", description = "본인인증이 완료되지 않았거나 유효하지 않음"),
    })
    @PatchMapping(value = "/api/contracts/{contractId}/approve", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ContractStatus approveByDebtor(
            @PathVariable Long contractId,
            @Parameter(description = "채무자 본인 주소") @RequestParam("debtorAddress") String debtorAddress,
            @Parameter(description = "채무자 서명 이미지 파일") @RequestParam("signature") MultipartFile signature,
            @Parameter(description = "본인인증 요청 식별값", example = "identity-verification-a1b2c3d4")
            @RequestParam String identityVerificationId,
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return contractService.submitDebtorSignature(contractId, userDetails.getUserId(), debtorAddress, signature, identityVerificationId);
    }

    @Operation(
            summary = "차용증 보관함 PDF 저장",
            description = "클라이언트에서 생성한 차용증 PDF를 보관합니다. 계약이 완료(COMPLETED) 상태가 아니면 저장하지 않습니다."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "저장 성공(또는 저장 대상이 아니어서 건너뜀)"),
            @ApiResponse(responseCode = "403", description = "해당 차용증에 대한 접근 권한이 없음")
    })
    @PostMapping("/api/contracts/{contractId}/pdf")
    public ResponseEntity<Void> saveContractPdf(
            @PathVariable Long contractId,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam("file") MultipartFile file
    ) {
        LoanContractResponse contract = contractService.findContract(contractId, userDetails.getUserId());

        if (contract.getStatus() == ContractStatus.COMPLETED) {
            archiveService.saveFile(ArchiveStatus.CONTRACT.name(), contractId, file);
        }

        return ResponseEntity.ok().build();
    }
}
