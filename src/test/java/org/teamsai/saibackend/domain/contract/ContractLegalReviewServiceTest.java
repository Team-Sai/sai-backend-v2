package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.contract.client.RagApiClient;
import org.teamsai.saibackend.domain.contract.dto.request.ContractLegalReviewRequest;
import org.teamsai.saibackend.domain.contract.dto.request.RagContractReviewRequest;
import org.teamsai.saibackend.domain.contract.dto.response.ContractLegalReviewResponse;
import org.teamsai.saibackend.domain.contract.service.ContractLegalReviewService;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractLegalReviewService 단위 테스트")
class ContractLegalReviewServiceTest {

    @Mock
    private RagApiClient ragApiClient;

    private ContractLegalReviewService service;

    @BeforeEach
    void setUp() {
        service = new ContractLegalReviewService(ragApiClient);
    }

    @Nested
    @DisplayName("법률 검토 요청 변환")
    class RequestMapping {

        @Test
        @DisplayName("차용증 정보를 RAG 요청 형식으로 변환하고 응답을 반환한다")
        void review_mapsRequestAndReturnsResponse() {
            ContractLegalReviewRequest request = ContractLegalReviewRequest.builder()
                    .principalAmount(new BigDecimal("25000000"))
                    .interestRate(new BigDecimal("0.5"))
                    .lateInterestRate(new BigDecimal("12.0"))
                    .maturityDate(LocalDate.of(2027, 1, 1))
                    .build();
            ContractLegalReviewResponse expected = new ContractLegalReviewResponse();
            when(ragApiClient.reviewContract(any(RagContractReviewRequest.class))).thenReturn(expected);

            ContractLegalReviewResponse actual = service.review(request);

            assertSame(expected, actual);
            ArgumentCaptor<RagContractReviewRequest> captor =
                    ArgumentCaptor.forClass(RagContractReviewRequest.class);
            verify(ragApiClient).reviewContract(captor.capture());
            RagContractReviewRequest sent = captor.getValue();
            assertEquals(new BigDecimal("25000000"), sent.getPrincipal());
            assertEquals(new BigDecimal("0.5"), sent.getInterestRate());
            assertEquals(new BigDecimal("12.0"), sent.getLateInterestRate());
            assertEquals("2027-01-01", sent.getRepaymentDate());
            verifyNoMoreInteractions(ragApiClient);
        }

        @Test
        @DisplayName("만기일이 없으면 상환일을 null로 전달한다")
        void review_withNullMaturityDate_mapsNullRepaymentDate() {
            ContractLegalReviewRequest request = ContractLegalReviewRequest.builder()
                    .principalAmount(new BigDecimal("1000000"))
                    .build();

            service.review(request);

            ArgumentCaptor<RagContractReviewRequest> captor =
                    ArgumentCaptor.forClass(RagContractReviewRequest.class);
            verify(ragApiClient).reviewContract(captor.capture());
            assertNull(captor.getValue().getRepaymentDate());
        }
    }
}
