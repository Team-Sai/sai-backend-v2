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
import org.teamsai.saibackend.domain.contract.dto.request.ContractClauseRequest;
import org.teamsai.saibackend.domain.contract.dto.request.RagClauseSuggestionRequest;
import org.teamsai.saibackend.domain.contract.dto.response.ContractClauseResponse;
import org.teamsai.saibackend.domain.contract.service.ContractClauseAgentService;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractClauseAgentService 단위 테스트")
class ContractClauseAgentServiceTest {

    @Mock
    private RagApiClient ragApiClient;

    private ContractClauseAgentService service;

    @BeforeEach
    void setUp() {
        service = new ContractClauseAgentService(ragApiClient);
    }

    @Nested
    @DisplayName("특약 생성 요청 변환")
    class RequestMapping {

        @Test
        @DisplayName("계약 정보를 RAG 요청 형식으로 변환하고 메시지 앞뒤 공백을 제거한다")
        void suggestClause_mapsContractContextAndTrimsMessage() {
            ContractClauseRequest request = ContractClauseRequest.builder()
                    .principalAmount(new BigDecimal("25000000"))
                    .interestRate(new BigDecimal("0.5"))
                    .lateInterestRate(new BigDecimal("12"))
                    .startDate(LocalDate.of(2026, 10, 13))
                    .maturityDate(LocalDate.of(2027, 1, 1))
                    .repaymentDay(1)
                    .repaymentType("EQUAL_PRINCIPAL")
                    .contractAlias("생활비")
                    .terms("추가 합의")
                    .message("  연체 관련 특약을 만들어 줘  ")
                    .build();
            ContractClauseResponse expected = new ContractClauseResponse();
            when(ragApiClient.suggestClause(any(RagClauseSuggestionRequest.class))).thenReturn(expected);

            ContractClauseResponse actual = service.suggestClause(request);

            assertSame(expected, actual);
            ArgumentCaptor<RagClauseSuggestionRequest> captor =
                    ArgumentCaptor.forClass(RagClauseSuggestionRequest.class);
            verify(ragApiClient).suggestClause(captor.capture());
            RagClauseSuggestionRequest sent = captor.getValue();
            assertEquals("연체 관련 특약을 만들어 줘", sent.getMessage());
            RagClauseSuggestionRequest.ContractContext context = sent.getContract();
            assertEquals(new BigDecimal("25000000"), context.getPrincipal());
            assertEquals(new BigDecimal("0.5"), context.getInterestRate());
            assertEquals(new BigDecimal("12"), context.getLateInterestRate());
            assertEquals("2026-10-13", context.getStartDate());
            assertEquals("2027-01-01", context.getRepaymentDate());
            assertEquals(Integer.valueOf(1), context.getRepaymentDay());
            assertEquals("EQUAL_PRINCIPAL", context.getRepaymentType());
            assertEquals("생활비", context.getContractAlias());
            assertEquals("추가 합의", context.getTerms());
        }

        @Test
        @DisplayName("계약 날짜가 없으면 null로 전달한다")
        void suggestClause_withNullDates_preservesNullDates() {
            ContractClauseRequest request = ContractClauseRequest.builder()
                    .message("특약 제안")
                    .build();

            service.suggestClause(request);

            ArgumentCaptor<RagClauseSuggestionRequest> captor =
                    ArgumentCaptor.forClass(RagClauseSuggestionRequest.class);
            verify(ragApiClient).suggestClause(captor.capture());
            assertNull(captor.getValue().getContract().getStartDate());
            assertNull(captor.getValue().getContract().getRepaymentDate());
        }
    }

    @Nested
    @DisplayName("메시지 검증")
    class MessageValidation {

        @Test
        @DisplayName("요청 메시지가 null이거나 공백이면 예외를 발생시킨다")
        void suggestClause_rejectsNullBlankAndEmptyMessage() {
            for (String message : new String[]{null, "", "   ", "\t\n"}) {
                ContractClauseRequest request = ContractClauseRequest.builder()
                        .message(message)
                        .build();
                IllegalArgumentException exception = assertThrows(
                        IllegalArgumentException.class,
                        () -> service.suggestClause(request)
                );
                assertEquals("AI에게 요청할 내용을 입력해 주세요.", exception.getMessage());
            }
            verifyNoInteractions(ragApiClient);
        }
    }
}
