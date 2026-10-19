package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementListResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentObligationResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementSummaryResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementWithPaymentStatusResponse;
import org.teamsai.saibackend.domain.settlement.service.SettlementQueryService;
import org.teamsai.saibackend.domain.settlement.service.SettlementSummaryQueryService;
import org.teamsai.saibackend.domain.settlement.type.SettlementStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SettlementSummaryQueryServiceTest {

    @Mock
    private SettlementQueryService settlementQueryService;

    @InjectMocks
    private SettlementSummaryQueryService settlementSummaryQueryService;

    @Test
    void sumsOwnerReceivablesAndOnlyCurrentMembersPayables() {
        given(settlementQueryService.getSettlementListWithPaymentStatus(7L)).willReturn(List.of(
                withStatus(
                        settlement(1L, "OWNER", SettlementStatus.IN_PROGRESS),
                        SettlementPaymentStatusResponse.builder()
                                .totalRemainingAmount(new BigDecimal("5000"))
                                .build()
                ),
                withStatus(
                        settlement(2L, "MEMBER", SettlementStatus.IN_PROGRESS),
                        SettlementPaymentStatusResponse.builder()
                                .obligations(List.of(
                                        obligation(7L, "2000"),
                                        obligation(8L, "3000")
                                ))
                                .build()
                ),
                withStatus(
                        settlement(3L, "OWNER", SettlementStatus.CLOSED),
                        SettlementPaymentStatusResponse.builder()
                                .totalRemainingAmount(new BigDecimal("9000"))
                                .build()
                )
        ));

        SettlementSummaryResponse result = settlementSummaryQueryService.getSummary(7L);

        assertThat(result.receivableAmount()).isEqualByComparingTo("5000");
        assertThat(result.receivableCount()).isEqualTo(1);
        assertThat(result.payableAmount()).isEqualByComparingTo("2000");
        assertThat(result.payableCount()).isEqualTo(1);
        verify(settlementQueryService, never()).getPaymentStatus(anyLong(), anyLong());
    }

    @Test
    void excludesZeroAndMissingRemainingAmounts() {
        given(settlementQueryService.getSettlementListWithPaymentStatus(7L)).willReturn(List.of(
                withStatus(
                        settlement(1L, "OWNER", SettlementStatus.IN_PROGRESS),
                        SettlementPaymentStatusResponse.builder().build()
                ),
                withStatus(
                        settlement(2L, "MEMBER", SettlementStatus.IN_PROGRESS),
                        SettlementPaymentStatusResponse.builder()
                                .obligations(List.of(obligation(7L, "0")))
                                .build()
                )
        ));

        SettlementSummaryResponse result = settlementSummaryQueryService.getSummary(7L);

        assertThat(result.receivableAmount()).isEqualByComparingTo("0");
        assertThat(result.receivableCount()).isZero();
        assertThat(result.payableAmount()).isEqualByComparingTo("0");
        assertThat(result.payableCount()).isZero();
    }

    private SettlementWithPaymentStatusResponse withStatus(
            SettlementListResponse settlement,
            SettlementPaymentStatusResponse paymentStatus
    ) {
        return new SettlementWithPaymentStatusResponse(settlement, paymentStatus);
    }

    private SettlementListResponse settlement(Long id, String role, SettlementStatus status) {
        return new SettlementListResponse(
                id, null, role, null, null, null, status.name(),
                null, null, null, null, null, null, null
        );
    }

    private SettlementPaymentObligationResponse obligation(Long userId, String amount) {
        return SettlementPaymentObligationResponse.builder()
                .userId(userId)
                .remainingAmount(new BigDecimal(amount))
                .build();
    }
}
