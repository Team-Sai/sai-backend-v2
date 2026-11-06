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
import org.teamsai.saibackend.domain.contract.dto.request.ClauseReviewRequest;
import org.teamsai.saibackend.domain.contract.dto.request.ContractClauseRequest;
import org.teamsai.saibackend.domain.contract.dto.request.RagClauseReviewRequest;
import org.teamsai.saibackend.domain.contract.dto.request.RagClauseSuggestionRequest;
import org.teamsai.saibackend.domain.contract.dto.response.ClauseReviewResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ContractClauseResponse;
import org.teamsai.saibackend.domain.contract.service.ContractClauseAgentService;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
        @DisplayName("발표 시연 표시는 있으면 true, 없으면 false로 전달한다")
        void toRagRequest_mapsDemoFlag() {
            ContractClauseRequest normal = ContractClauseRequest.builder()
                    .principalAmount(new BigDecimal("1000000"))
                    .interestRate(new BigDecimal("5"))
                    .message("연체 특약을 만들어 줘")
                    .build();
            ContractClauseRequest demo = ContractClauseRequest.builder()
                    .principalAmount(new BigDecimal("1000000"))
                    .interestRate(new BigDecimal("5"))
                    .message("연체 특약을 만들어 줘")
                    .demo(true)
                    .build();

            assertFalse(service.toRagRequest(normal).isDemo());
            assertTrue(service.toRagRequest(demo).isDemo());
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

    @Nested
    @DisplayName("반영한 조항 다시 검토")
    class ReviewClause {

        @Test
        @DisplayName("계약 정보·원래 요청·쟁점과 공백을 정리한 조항을 AI에 보낸다")
        void reviewClause_sendsContractIssuesAndTrimmedClause() {
            ContractClauseRequest request = ContractClauseRequest.builder()
                    .principalAmount(new BigDecimal("5000000"))
                    .interestRate(new BigDecimal("5"))
                    .message("  중도상환 수수료 없이 해 주세요  ")
                    .build();
            ClauseReviewResponse expected = new ClauseReviewResponse(
                    "PASS", true, List.of(), List.of(), List.of(), null
            );
            when(ragApiClient.reviewClause(any(RagClauseReviewRequest.class))).thenReturn(expected);

            ClauseReviewResponse actual = service.reviewClause(new ClauseReviewRequest(
                    request,
                    List.of(Map.of("type", "EARLY_REPAYMENT")),
                    "  채무자는 언제든지 중도상환할 수 있다.  "
            ));

            assertSame(expected, actual);
            ArgumentCaptor<RagClauseReviewRequest> captor =
                    ArgumentCaptor.forClass(RagClauseReviewRequest.class);
            verify(ragApiClient).reviewClause(captor.capture());
            RagClauseReviewRequest sent = captor.getValue();
            assertEquals("중도상환 수수료 없이 해 주세요", sent.getMessage());
            assertEquals("채무자는 언제든지 중도상환할 수 있다.", sent.getClause());
            assertEquals("EARLY_REPAYMENT", sent.getIssues().get(0).get("type"));
            assertEquals(new BigDecimal("5000000"), sent.getContract().getPrincipal());
        }

        @Test
        @DisplayName("쟁점에서 type만 골라 보내고, 쓸 수 있는 type이 없으면 거절한다")
        void reviewClause_sendsOnlyIssueTypes() {
            ContractClauseRequest request = ContractClauseRequest.builder()
                    .principalAmount(new BigDecimal("5000000"))
                    .interestRate(new BigDecimal("5"))
                    .message("중도상환 수수료 없이 해 주세요")
                    .build();
            when(ragApiClient.reviewClause(any(RagClauseReviewRequest.class)))
                    .thenReturn(new ClauseReviewResponse("PASS", true, List.of(), List.of(), List.of(), null));

            service.reviewClause(new ClauseReviewRequest(
                    request,
                    List.of(Map.of("type", "EARLY_REPAYMENT", "searchTerms", List.of("아주", "긴", "목록"))),
                    "채무자는 언제든지 중도상환할 수 있다."
            ));

            ArgumentCaptor<RagClauseReviewRequest> captor =
                    ArgumentCaptor.forClass(RagClauseReviewRequest.class);
            verify(ragApiClient).reviewClause(captor.capture());
            assertEquals(List.of(Map.of("type", "EARLY_REPAYMENT")), captor.getValue().getIssues());

            assertThrows(IllegalArgumentException.class, () -> service.reviewClause(new ClauseReviewRequest(
                    request,
                    List.of(Map.of("reason", "type 없음")),
                    "채무자는 언제든지 중도상환할 수 있다."
            )));
        }

        @Test
        @DisplayName("AI 응답의 내부 칸(validatorMode 등)은 받을 때 버린다")
        void reviewResponse_dropsInternalFields() {
            String aiJson = """
                    {"status":"REVIEW","isSafeToSuggest":true,"checks":[],"warnings":["확인"],
                     "openSlots":["사전 통지 기간"],"suggestedClause":null,
                     "validatorMode":"LLM","debug":{"llmReasons":["x"]}}
                    """;

            ClauseReviewResponse response = JsonMapper.builder().build()
                    .readValue(aiJson, ClauseReviewResponse.class);

            assertEquals("REVIEW", response.status());
            assertEquals(List.of("사전 통지 기간"), response.openSlots());
            String sentToBrowser = JsonMapper.builder().build().writeValueAsString(response);
            assertFalse(sentToBrowser.contains("validatorMode"));
            assertFalse(sentToBrowser.contains("debug"));
        }
    }
}
