package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;
import org.teamsai.saibackend.domain.contract.controller.ContractChangeController;
import org.teamsai.saibackend.domain.contract.dto.response.ContractChangeRequestResponse;
import org.teamsai.saibackend.domain.contract.dto.request.ContractChangeRejectRequest;
import org.teamsai.saibackend.domain.contract.dto.request.ContractChangeRequest;
import org.teamsai.saibackend.domain.contract.exception.ContractChangeErrorCode;
import org.teamsai.saibackend.domain.contract.service.ContractChangeService;
import org.teamsai.saibackend.domain.contract.type.ChangeRequestStatus;
import org.teamsai.saibackend.global.exception.DomainException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    private ContractChangeRequestResponse changeResponse() {
        return ContractChangeRequestResponse.builder()
                .changeRequestId(CHANGE_REQUEST_ID)
                .contractId(CONTRACT_ID)
                .status(ChangeRequestStatus.PENDING)
                .build();
    }

    private ContractChangeRequest request() {
        return ContractChangeRequest.builder()
                .changeReason("이자율 조정")
                .build();
    }

    @Nested
    @DisplayName("변경 요청 등록")
    class RequestChange {

        @Test
        @DisplayName("서명 파일이 있으면 서명과 함께 서비스에 위임한다")
        void requestChangeDelegatesToService() {
            ContractChangeRequest request = request();
            MultipartFile signature = mock(MultipartFile.class);
            given(signature.isEmpty()).willReturn(false);
            given(contractChangeService.requestChange(
                    CONTRACT_ID, request, signature, IDENTITY_VERIFICATION_ID, USER_ID
            )).willReturn(changeResponse());

            ContractChangeRequestResponse result = contractChangeController.requestChange(
                    CONTRACT_ID, request, signature, IDENTITY_VERIFICATION_ID, USER_ID
            );

            assertThat(result.getChangeRequestId()).isEqualTo(CHANGE_REQUEST_ID);
        }

        @Test
        @DisplayName("서명 파일이 없으면 예외가 발생하고 변경 요청을 만들지 않는다")
        void requestChangeFailsWhenSignatureIsNull() {
            assertSignatureRequired(null);
        }

        @Test
        @DisplayName("서명 파일이 비어있으면 예외가 발생하고 변경 요청을 만들지 않는다")
        void requestChangeFailsWhenSignatureIsEmpty() {
            MultipartFile signature = mock(MultipartFile.class);
            given(signature.isEmpty()).willReturn(true);

            assertSignatureRequired(signature);
        }

        @Test
        @DisplayName("multipart 요청의 JSON, 서명 파일, 본인인증 ID를 함께 바인딩한다")
        void bindsMultipartRequest() throws Exception {
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(contractChangeController).build();
            given(contractChangeService.requestChange(eq(CONTRACT_ID), any(), any(), eq(IDENTITY_VERIFICATION_ID), any()))
                    .willReturn(changeResponse());

            mockMvc.perform(multipart("/api/contracts/{contractId}/change-requests", CONTRACT_ID)
                            .file(new MockMultipartFile("request", "", MediaType.APPLICATION_JSON_VALUE,
                                    "{\"changeReason\":\"이자율 조정\",\"newMaturityDate\":\"2099-12-31\"}"
                                            .getBytes(StandardCharsets.UTF_8)))
                            .file(new MockMultipartFile("signature", "signature.png", MediaType.IMAGE_PNG_VALUE,
                                    new byte[]{1, 2, 3}))
                            .param("identityVerificationId", IDENTITY_VERIFICATION_ID))
                    .andExpect(status().isCreated());

            ArgumentCaptor<ContractChangeRequest> captor = ArgumentCaptor.forClass(ContractChangeRequest.class);
            verify(contractChangeService).requestChange(
                    eq(CONTRACT_ID), captor.capture(), any(), eq(IDENTITY_VERIFICATION_ID), any());
            assertThat(captor.getValue().getChangeReason()).isEqualTo("이자율 조정");
            assertThat(captor.getValue().getNewMaturityDate()).isEqualTo(LocalDate.of(2099, 12, 31));
        }

        private void assertSignatureRequired(MultipartFile signature) {
            assertThatThrownBy(() -> contractChangeController.requestChange(
                    CONTRACT_ID, request(), signature, IDENTITY_VERIFICATION_ID, USER_ID
            ))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ContractChangeErrorCode.SIGNATURE_REQUIRED)
                    );

            verify(contractChangeService, never()).requestChange(any(), any(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("변경 요청 반려는 반려 사유를 그대로 서비스에 전달한다")
    void rejectChangeDelegatesToService() {
        ContractChangeRejectRequest request = ContractChangeRejectRequest.builder()
                .returnReason("이율이 너무 높습니다")
                .build();
        given(contractChangeService.rejectChange(CONTRACT_ID, CHANGE_REQUEST_ID, "이율이 너무 높습니다", USER_ID))
                .willReturn(changeResponse());

        ContractChangeRequestResponse result =
                contractChangeController.rejectChange(CONTRACT_ID, CHANGE_REQUEST_ID, request, USER_ID);

        assertThat(result.getContractId()).isEqualTo(CONTRACT_ID);
    }
}
