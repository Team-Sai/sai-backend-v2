package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.contract.client.RagApiClient;
import org.teamsai.saibackend.domain.contract.dto.request.ContractClauseRequest;
import org.teamsai.saibackend.domain.contract.service.ContractClauseAgentService;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractClauseAgentService 스트리밍 테스트")
class ContractClauseAgentServiceStreamTest {

    @Mock
    private RagApiClient ragApiClient;

    @Test
    @DisplayName("요청 내용이 비어 있으면 스트림을 열기 전에 예외를 던진다 (기존처럼 400)")
    void emptyMessageIsRejectedBeforeStreaming() {
        ContractClauseAgentService service = new ContractClauseAgentService(ragApiClient);

        ContractClauseRequest request = ContractClauseRequest.builder()
                .principalAmount(new BigDecimal("1000000"))
                .interestRate(new BigDecimal("5"))
                .message("   ")
                .build();

        assertThrows(IllegalArgumentException.class, () -> service.streamClause(request));
        verifyNoInteractions(ragApiClient);
    }
}