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
import org.teamsai.saibackend.domain.settlement.service.SettlementPaymentStatusQueryService;
import org.teamsai.saibackend.domain.settlement.service.SettlementQueryService;
import org.teamsai.saibackend.domain.settlement.service.SettlementSummaryQueryService;
import org.teamsai.saibackend.domain.settlement.type.SettlementStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SettlementSummaryQueryServiceTest {

    @Mock
    private SettlementQueryService settlementQueryService;

    @Mock
    private SettlementPaymentStatusQueryService settlementPaymentStatusService;

    @InjectMocks
    private SettlementSummaryQueryService settlementSummaryQueryService;

    @Test
    void sumsOwnerReceivablesAndOnlyCurrentMembersPayables() {
        given(settlementQueryService.getSettlementList(7L)).willReturn(List.of(
                settlement(1L, "OWNER", SettlementStatus.IN_PROGRESS),
                settlement(2L, "MEMBER", SettlementStatus.IN_PROGRESS),
                settlement(3L, "OWNER", SettlementStatus.CLOSED)
        ));
        given(settlementPaymentStatusService.getPaymentStatus(1L, 7L)).willReturn(
                SettlementPaymentStatusResponse.builder()
                        .totalRemainingAmount(new BigDecimal("5000"))
                        .build()
        );
        given(settlementPaymentStatusService.getPaymentStatus(2L, 7L)).willReturn(
                SettlementPaymentStatusResponse.builder()
                        .obligations(List.of(
                                obligation(7L, "2000"),
                                obligation(8L, "3000")
                        ))
                        .build()
        );

        SettlementSummaryResponse result = settlementSummaryQueryService.getSummary(7L);

        assertThat(result.receivableAmount()).isEqualByComparingTo("5000");
        assertThat(result.receivableCount()).isEqualTo(1);
        assertThat(result.payableAmount()).isEqualByComparingTo("2000");
        assertThat(result.payableCount()).isEqualTo(1);
        verify(settlementPaymentStatusService, never()).getPaymentStatus(3L, 7L);
    }

    @Test
    void excludesZeroAndMissingRemainingAmounts() {
        given(settlementQueryService.getSettlementList(7L)).willReturn(List.of(
                settlement(1L, "OWNER", SettlementStatus.IN_PROGRESS),
                settlement(2L, "MEMBER", SettlementStatus.IN_PROGRESS)
        ));
        given(settlementPaymentStatusService.getPaymentStatus(1L, 7L)).willReturn(
                SettlementPaymentStatusResponse.builder().build()
        );
        given(settlementPaymentStatusService.getPaymentStatus(2L, 7L)).willReturn(
                SettlementPaymentStatusResponse.builder()
                        .obligations(List.of(obligation(7L, "0")))
                        .build()
        );

        SettlementSummaryResponse result = settlementSummaryQueryService.getSummary(7L);

        assertThat(result.receivableAmount()).isEqualByComparingTo("0");
        assertThat(result.receivableCount()).isZero();
        assertThat(result.payableAmount()).isEqualByComparingTo("0");
        assertThat(result.payableCount()).isZero();
    }

    private SettlementListResponse settlement(Long id, String role, SettlementStatus status) {
        return new SettlementListResponse(
                id, null, role, null, null, null, status.name(),
                null, null, null, null, null, null
        );
    }

    private SettlementPaymentObligationResponse obligation(Long userId, String amount) {
        return SettlementPaymentObligationResponse.builder()
                .userId(userId)
                .remainingAmount(new BigDecimal(amount))
                .build();
    }
}
