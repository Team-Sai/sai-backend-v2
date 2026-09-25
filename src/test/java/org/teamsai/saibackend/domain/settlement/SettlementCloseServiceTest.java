package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementCloseResponse;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.service.SettlementCloseService;
import org.teamsai.saibackend.domain.settlement.service.SettlementPaymentStatusQueryService;
import org.teamsai.saibackend.domain.settlement.support.SettlementValidator;
import org.teamsai.saibackend.domain.settlement.type.SettlementStatus;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.global.exception.DomainException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementCloseService 단위 테스트")
class SettlementCloseServiceTest {

    private static final Long SETTLEMENT_ID = 1L;
    private static final Long OWNER_ID = 10L;
    private static final Long OTHER_USER_ID = 20L;

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private SettlementPaymentStatusQueryService paymentStatusService;

    @Mock
    private SettlementValidator settlementValidator;

    @InjectMocks
    private SettlementCloseService closeService;

    @Test
    @DisplayName("owner가 마감 가능한 정산을 CLOSED 처리한다")
    void closeSettlementSucceedsForOwnerWhenClosable() {

        Settlement settlement = inProgressSettlement();

        given(
                settlementRepository.findByIdForUpdate(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        given(
                paymentStatusService.areAllObligationsResolved(
                        SETTLEMENT_ID
                )
        ).willReturn(true);

        given(
                settlementRepository.closeSettlement(
                        eq(SETTLEMENT_ID),
                        any(LocalDateTime.class),
                        eq(SettlementStatus.IN_PROGRESS),
                        eq(SettlementStatus.CLOSED)
                )
        ).willReturn(1);

        SettlementCloseResponse response =
                closeService.close(
                        SETTLEMENT_ID,
                        OWNER_ID
                );

        assertThat(response.getSettlementId())
                .isEqualTo(SETTLEMENT_ID);

        assertThat(response.getSettlementStatus())
                .isEqualTo(SettlementStatus.CLOSED);

        assertThat(response.getClosedAt())
                .isNotNull();

        verify(settlementValidator)
                .validateOwner(
                        settlement,
                        OWNER_ID
                );

        verify(paymentStatusService)
                .areAllObligationsResolved(
                        SETTLEMENT_ID
                );

        verify(settlementRepository)
                .closeSettlement(
                        eq(SETTLEMENT_ID),
                        any(LocalDateTime.class),
                        eq(SettlementStatus.IN_PROGRESS),
                        eq(SettlementStatus.CLOSED)
                );
    }

    @Test
    @DisplayName("owner가 아니면 정산을 마감할 수 없다")
    void closeSettlementFailsWhenUserIsNotOwner() {

        Settlement settlement = inProgressSettlement();

        given(
                settlementRepository.findByIdForUpdate(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        doThrow(
                SettlementErrorCode
                        .SETTLEMENT_ACCESS_DENIED
                        .toException()
        ).when(settlementValidator)
                .validateOwner(
                        settlement,
                        OTHER_USER_ID
                );

        assertThatThrownBy(
                () -> closeService.close(
                        SETTLEMENT_ID,
                        OTHER_USER_ID
                )
        ).isInstanceOfSatisfying(
                DomainException.class,
                exception ->
                        assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                SettlementErrorCode
                                        .SETTLEMENT_ACCESS_DENIED
                        )
        );

        verify(paymentStatusService, never())
                .areAllObligationsResolved(
                        SETTLEMENT_ID
                );

        verify(settlementRepository, never())
                .closeSettlement(
                        any(),
                        any(),
                        any(),
                        any()
                );
    }

    @Test
    @DisplayName("모든 납부의무가 완료되지 않으면 정산을 마감할 수 없다")
    void closeSettlementFailsWhenNotClosable() {

        Settlement settlement = inProgressSettlement();

        given(
                settlementRepository.findByIdForUpdate(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        given(
                paymentStatusService.areAllObligationsResolved(
                        SETTLEMENT_ID
                )
        ).willReturn(false);

        assertThatThrownBy(
                () -> closeService.close(
                        SETTLEMENT_ID,
                        OWNER_ID
                )
        ).isInstanceOfSatisfying(
                DomainException.class,
                exception ->
                        assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                SettlementErrorCode
                                        .SETTLEMENT_NOT_CLOSABLE
                        )
        );

        verify(settlementValidator)
                .validateOwner(
                        settlement,
                        OWNER_ID
                );

        verify(paymentStatusService)
                .areAllObligationsResolved(
                        SETTLEMENT_ID
                );

        verify(settlementRepository, never())
                .closeSettlement(
                        any(),
                        any(),
                        any(),
                        any()
                );
    }

    @Test
    @DisplayName("이미 종료된 정산은 다시 마감할 수 없다")
    void closeSettlementFailsWhenAlreadyClosed() {

        Settlement settlement =
                Settlement.builder()
                        .settlementId(SETTLEMENT_ID)
                        .owner(createOwner())
                        .settlementStatus(
                                SettlementStatus.CLOSED
                        )
                        .build();

        given(
                settlementRepository.findByIdForUpdate(
                        SETTLEMENT_ID
                )
        ).willReturn(
                Optional.of(settlement)
        );

        assertThatThrownBy(
                () -> closeService.close(
                        SETTLEMENT_ID,
                        OWNER_ID
                )
        ).isInstanceOfSatisfying(
                DomainException.class,
                exception ->
                        assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(
                                SettlementErrorCode
                                        .ALREADY_CLOSED_SETTLEMENT
                        )
        );

        verify(settlementValidator)
                .validateOwner(
                        settlement,
                        OWNER_ID
                );

        verify(paymentStatusService, never())
                .areAllObligationsResolved(
                        SETTLEMENT_ID
                );

        verify(settlementRepository, never())
                .closeSettlement(
                        any(),
                        any(),
                        any(),
                        any()
                );
    }

    private Settlement inProgressSettlement() {
        return Settlement.builder()
                .settlementId(SETTLEMENT_ID)
                .owner(createOwner())
                .settlementStatus(
                        SettlementStatus.IN_PROGRESS
                )
                .build();
    }

    private User createOwner() {
        return User.builder()
                .userId(OWNER_ID)
                .build();
    }
}
