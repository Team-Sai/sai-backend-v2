package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.archive.service.ArchiveService;
import org.teamsai.saibackend.domain.contract.controller.ContractDetailQueryController;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDetailResponse;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.exception.LoanContractErrorCode;
import org.teamsai.saibackend.domain.contract.service.ContractDetailQueryService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractDetailQueryController 서명 조회 단위 테스트")
class ContractDetailQueryControllerTest {

    private static final Long CONTRACT_ID = 1L;
    private static final Long USER_ID = 10L;

    @Mock
    private ContractDetailQueryService contractDetailQueryService;

    @Mock
    private ArchiveService archiveService;

    @InjectMocks
    private ContractDetailQueryController contractDetailQueryController;

    private ContractDetailResponse detailWith(String creditorSignature, String debtorSignature) {
        LoanContractResponse contract = LoanContractResponse.builder()
                .contractId(CONTRACT_ID)
                .creditorSignature(creditorSignature)
                .debtorSignature(debtorSignature)
                .build();

        return ContractDetailResponse.builder()
                .contract(contract)
                .isCreditor(true)
                .canRequestChange(false)
                .address("서울시 채권자로 1")
                .build();
    }

    @Test
    @DisplayName("양측 서명이 모두 등록되어 있으면 두 data URI를 모두 반환한다")
    void returnsBothSignatureDataUris() {
        given(contractDetailQueryService.getCheck(CONTRACT_ID, USER_ID))
                .willReturn(detailWith("creditor.png", "debtor.png"));
        given(archiveService.loadSignatureDataUri("creditor.png")).willReturn("data:image/png;base64,creditor");
        given(archiveService.loadSignatureDataUri("debtor.png")).willReturn("data:image/png;base64,debtor");

        Map<String, String> result = contractDetailQueryController.getSignatures(CONTRACT_ID, USER_ID);

        assertThat(result.get("creditorSignatureDataUri")).isEqualTo("data:image/png;base64,creditor");
        assertThat(result.get("debtorSignatureDataUri")).isEqualTo("data:image/png;base64,debtor");
    }

    @Test
    @DisplayName("아직 서명이 등록되지 않은 쪽은 null을 반환한다")
    void returnsNullForMissingSignature() {
        given(contractDetailQueryService.getCheck(CONTRACT_ID, USER_ID))
                .willReturn(detailWith("creditor.png", null));
        given(archiveService.loadSignatureDataUri("creditor.png")).willReturn("data:image/png;base64,creditor");
        given(archiveService.loadSignatureDataUri(null)).willReturn(null);

        Map<String, String> result = contractDetailQueryController.getSignatures(CONTRACT_ID, USER_ID);

        assertThat(result.get("creditorSignatureDataUri")).isEqualTo("data:image/png;base64,creditor");
        assertThat(result.get("debtorSignatureDataUri")).isNull();
    }

    @Test
    @DisplayName("계약 당사자가 아니면 예외가 그대로 전파된다")
    void propagatesExceptionWhenNotContractParty() {
        given(contractDetailQueryService.getCheck(CONTRACT_ID, USER_ID))
                .willThrow(LoanContractErrorCode.CONTRACT_ACCESS_DENIED.toException());

        assertThatThrownBy(() -> contractDetailQueryController.getSignatures(CONTRACT_ID, USER_ID))
                .isInstanceOf(RuntimeException.class);
    }
}
