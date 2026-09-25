package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.settlement.support.SettlementPaymentData;
import org.teamsai.saibackend.domain.settlement.support.SettlementPaymentReader;
import org.teamsai.saibackend.domain.settlement.support.SettlementPaymentStatusChecker;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementPaymentStatusChecker 단위 테스트")
class SettlementPaymentStatusCheckerTest {

    private static final Long SETTLEMENT_ID = 1L;

    @Mock
    private SettlementPaymentReader settlementPaymentReader;

    @InjectMocks
    private SettlementPaymentStatusChecker checker;

    @Test
    @DisplayName("모든 납부의무가 완납되면 true를 반환한다")
    void areAllObligationsResolvedReturnsTrueWhenAllPaid() {
        PaymentObligationEntity o1 =
                obligation(
                        1001L,
                        101L,
                        10000,
                        ObligationStatus.ACTIVE
                );

        PaymentObligationEntity o2 =
                obligation(
                        1002L,
                        101L,
                        20000,
                        ObligationStatus.ACTIVE
                );

        given(
                settlementPaymentReader.read(
                        SETTLEMENT_ID
                )
        ).willReturn(
                new SettlementPaymentData(
                        List.of(),
                        List.of(o1, o2),
                        List.of(),
                        Map.of(
                                1001L, new BigDecimal("10000"),
                                1002L, new BigDecimal("20000")
                        )
                )
        );

        boolean result =
                checker.areAllObligationsResolved(
                        SETTLEMENT_ID
                );

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("미납이지만 WRITTEN_OFF 상태이면 해결된 것으로 판단한다")
    void areAllObligationsResolvedReturnsTrueWhenWrittenOff() {
        PaymentObligationEntity o1 =
                obligation(
                        1001L,
                        101L,
                        10000,
                        ObligationStatus.ACTIVE
                );

        PaymentObligationEntity o2 =
                obligation(
                        1002L,
                        101L,
                        20000,
                        ObligationStatus.WRITTEN_OFF
                );

        given(
                settlementPaymentReader.read(
                        SETTLEMENT_ID
                )
        ).willReturn(
                new SettlementPaymentData(
                        List.of(),
                        List.of(o1, o2),
                        List.of(),
                        Map.of(
                                1001L, new BigDecimal("10000")
                        )
                )
        );

        boolean result =
                checker.areAllObligationsResolved(
                        SETTLEMENT_ID
                );

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("해결되지 않은 납부의무가 하나라도 있으면 false를 반환한다")
    void areAllObligationsResolvedReturnsFalseWhenNotAllResolved() {
        PaymentObligationEntity o1 =
                obligation(
                        1001L,
                        101L,
                        10000,
                        ObligationStatus.ACTIVE
                );

        PaymentObligationEntity o2 =
                obligation(
                        1002L,
                        101L,
                        20000,
                        ObligationStatus.ACTIVE
                );

        given(
                settlementPaymentReader.read(
                        SETTLEMENT_ID
                )
        ).willReturn(
                new SettlementPaymentData(
                        List.of(),
                        List.of(o1, o2),
                        List.of(),
                        Map.of(
                                1001L, new BigDecimal("10000"),
                                1002L, new BigDecimal("10000")
                        )
                )
        );

        boolean result =
                checker.areAllObligationsResolved(
                        SETTLEMENT_ID
                );

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("납부의무가 하나도 없으면 false를 반환한다")
    void areAllObligationsResolvedReturnsFalseWhenObligationsAreEmpty() {
        given(
                settlementPaymentReader.read(
                        SETTLEMENT_ID
                )
        ).willReturn(
                SettlementPaymentData.empty()
        );

        boolean result =
                checker.areAllObligationsResolved(
                        SETTLEMENT_ID
                );

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("EXCLUDED 상태이면 해결된 것으로 판단한다")
    void areAllObligationsResolvedReturnsTrueWhenExcluded() {
        PaymentObligationEntity obligation =
                obligation(
                        1001L,
                        101L,
                        10000,
                        ObligationStatus.EXCLUDED
                );

        given(
                settlementPaymentReader.read(
                        SETTLEMENT_ID
                )
        ).willReturn(
                new SettlementPaymentData(
                        List.of(),
                        List.of(obligation),
                        List.of(),
                        Map.of()
                )
        );

        boolean result =
                checker.areAllObligationsResolved(
                        SETTLEMENT_ID
                );

        assertThat(result).isTrue();
    }

    private PaymentObligationEntity obligation(
            Long obligationId,
            Long participantId,
            long expectedAmount,
            ObligationStatus obligationStatus
    ) {
        PaymentObligationEntity obligation =
                mock(PaymentObligationEntity.class);

        lenient()
                .when(obligation.getPaymentObligationId())
                .thenReturn(obligationId);

        lenient()
                .when(obligation.getParticipantId())
                .thenReturn(participantId);

        lenient()
                .when(obligation.getExpectedAmount())
                .thenReturn(
                        BigDecimal.valueOf(expectedAmount)
                );

        lenient()
                .when(obligation.getObligationStatus())
                .thenReturn(obligationStatus);

        return obligation;
    }
}