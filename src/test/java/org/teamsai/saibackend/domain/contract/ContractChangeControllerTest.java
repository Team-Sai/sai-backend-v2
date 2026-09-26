package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;
import org.teamsai.saibackend.domain.contract.controller.ContractChangeController;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractChangeResponse;
import org.teamsai.saibackend.domain.contract.dto.request.ContractChangeRejectRequest;
import org.teamsai.saibackend.domain.contract.dto.request.ContractChangeRequest;
import org.teamsai.saibackend.domain.contract.exception.ContractChangeErrorCode;
import org.teamsai.saibackend.domain.contract.service.ContractChangeService;
import org.teamsai.saibackend.domain.contract.type.ChangeRequestStatus;
import org.teamsai.saibackend.global.exception.DomainException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractChangeController 단위 테스트")
class ContractChangeControllerTest {

    private static final Long CONTRACT_ID = 1L;
    private static final Long CHANGE_REQUEST_ID = 5L;
    private static final Long USER_ID = 10L;
    private static final String IDENTITY_VERIFICATION_ID = "identity-verification-id";

    @Mock
    private ContractChangeService contractChangeService;

    @InjectMocks
    private ContractChangeController contractChangeController;

    private LoanContractChangeResponse changeResponse() {
        return LoanContractChangeResponse.builder()
                .changeRequestId(CHANGE_REQUEST_ID)
                .contractId(CONTRACT_ID)
                .status(ChangeRequestStatus.PENDING)
                .build();
    }

    @Test
    @DisplayName("변경 요청 등록은 서비스 결과를 그대로 반환한다")
    void requestChangeDelegatesToService() {
        ContractChangeRequest request = ContractChangeRequest.builder()
                .changeReason("이자율 조정")
                .build();
        given(contractChangeService.requestChange(CONTRACT_ID, request, USER_ID))
                .willReturn(changeResponse());

        LoanContractChangeResponse result = contractChangeController.requestChange(CONTRACT_ID, request, USER_ID);

        assertThat(result.getChangeRequestId()).isEqualTo(CHANGE_REQUEST_ID);
    }

    @Test
    @DisplayName("변경 요청 반려는 반려 사유를 그대로 서비스에 전달한다")
    void rejectChangeDelegatesToService() {
        ContractChangeRejectRequest request = ContractChangeRejectRequest.builder()
                .returnReason("이율이 너무 높습니다")
                .build();
        given(contractChangeService.rejectChange(CONTRACT_ID, CHANGE_REQUEST_ID, "이율이 너무 높습니다", USER_ID))
                .willReturn(changeResponse());

        LoanContractChangeResponse result =
                contractChangeController.rejectChange(CONTRACT_ID, CHANGE_REQUEST_ID, request, USER_ID);

        assertThat(result.getContractId()).isEqualTo(CONTRACT_ID);
    }

    @Test
    @DisplayName("변경 요청 취소는 서비스에 그대로 위임한다")
    void cancelChangeRequestDelegatesToService() {
        contractChangeController.cancelChangeRequest(CONTRACT_ID, CHANGE_REQUEST_ID, USER_ID);

        verify(contractChangeService).cancelChangeRequest(CONTRACT_ID, CHANGE_REQUEST_ID, USER_ID);
    }

    @Nested
    @DisplayName("변경 요청 전자서명 제출")
    class SubmitRequesterSignature {

        @Test
        @DisplayName("서명 파일이 있으면 서비스에 위임한다")
        void submitRequesterSignatureSuccess() {
            MultipartFile signature = mock(MultipartFile.class);
            given(signature.isEmpty()).willReturn(false);
            given(contractChangeService.submitRequesterSignature(
                    CONTRACT_ID, CHANGE_REQUEST_ID, USER_ID, signature, IDENTITY_VERIFICATION_ID
            )).willReturn(changeResponse());

            LoanContractChangeResponse result = contractChangeController.submitRequesterSignature(
                    CONTRACT_ID, CHANGE_REQUEST_ID, signature, IDENTITY_VERIFICATION_ID, USER_ID
            );

            assertThat(result.getChangeRequestId()).isEqualTo(CHANGE_REQUEST_ID);
        }

        @Test
        @DisplayName("서명 파일이 없으면 예외가 발생하고 서비스는 호출되지 않는다")
        void submitRequesterSignatureFailsWhenSignatureIsNull() {
            assertThatThrownBy(() -> contractChangeController.submitRequesterSignature(
                    CONTRACT_ID, CHANGE_REQUEST_ID, null, IDENTITY_VERIFICATION_ID, USER_ID
            ))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ContractChangeErrorCode.SIGNATURE_REQUIRED)
                    );

            verify(contractChangeService, never())
                    .submitRequesterSignature(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("서명 파일이 비어있으면 예외가 발생하고 서비스는 호출되지 않는다")
        void submitRequesterSignatureFailsWhenSignatureIsEmpty() {
            MultipartFile signature = mock(MultipartFile.class);
            given(signature.isEmpty()).willReturn(true);

            assertThatThrownBy(() -> contractChangeController.submitRequesterSignature(
                    CONTRACT_ID, CHANGE_REQUEST_ID, signature, IDENTITY_VERIFICATION_ID, USER_ID
            ))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ContractChangeErrorCode.SIGNATURE_REQUIRED)
                    );

            verify(contractChangeService, never())
                    .submitRequesterSignature(any(), any(), any(), any(), any());
        }
    }
}
