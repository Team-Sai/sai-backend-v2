package org.teamsai.saibackend.domain.archive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.archive.service.SettlementArchiveQueryService;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;
import org.teamsai.saibackend.domain.payment.type.SourceType;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementArchivePreviewResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementDetailResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentHistoryResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentObligationResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.service.SettlementAccountService;
import org.teamsai.saibackend.domain.settlement.service.SettlementPaymentHistoryQueryService;
import org.teamsai.saibackend.domain.settlement.service.SettlementPaymentStatusQueryService;
import org.teamsai.saibackend.domain.settlement.service.SettlementQueryService;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementArchiveQueryService 단위 테스트")
class SettlementArchiveQueryServiceTest {

    private static final Long SETTLEMENT_ID = 1L;
    private static final Long USER_ID = 10L;

    @Mock
    private SettlementQueryService settlementQueryService;

    @Mock
    private SettlementPaymentStatusQueryService settlementPaymentStatusService;

    @Mock
    private SettlementPaymentHistoryQueryService settlementPaymentHistoryService;

    @Mock
    private SettlementAccountService settlementAccountService;

    private SettlementArchiveQueryService settlementArchiveService;

    @BeforeEach
    void setUp() {
        settlementArchiveService = new SettlementArchiveQueryService(
                settlementQueryService,
                settlementPaymentStatusService,
                settlementPaymentHistoryService,
                settlementAccountService
        );
    }

    @Nested
    @DisplayName("정산 미리보기 조회")
    class ArchivePreviewRetrieval {

        private void stubLiveDataDependencies() {
            given(settlementPaymentStatusService.getPaymentStatus(SETTLEMENT_ID, USER_ID))
                    .willReturn(paymentStatus());
            given(settlementPaymentHistoryService.getPaymentHistory(SETTLEMENT_ID, USER_ID))
                    .willReturn(List.of());
            given(settlementAccountService.findCurrentAccount(USER_ID, SETTLEMENT_ID))
                    .willThrow(SettlementErrorCode.SETTLEMENT_ACCOUNT_NOT_FOUND.toException());
        }

        @Test
        @DisplayName("종료된 정산도 매번 최신 이행현황을 실시간으로 조회한다 (캐시 없음)")
        void alwaysBuildsFromLiveDataForClosedSettlement() {
            stubLiveDataDependencies();
            given(settlementQueryService.getSettlementDetail(SETTLEMENT_ID, USER_ID))
                    .willReturn(detail("CLOSED"));

            SettlementArchivePreviewResponse preview = settlementArchiveService.getArchivePreview(SETTLEMENT_ID, USER_ID);

            assertThat(preview.settlementId()).isEqualTo(SETTLEMENT_ID);
            assertThat(preview.settlementStatus()).isEqualTo("CLOSED");
            verify(settlementPaymentStatusService).getPaymentStatus(SETTLEMENT_ID, USER_ID);
        }

        @Test
        @DisplayName("진행 중인 정산도 매번 최신 이행현황을 실시간으로 조회한다")
        void alwaysBuildsFromLiveDataForInProgressSettlement() {
            stubLiveDataDependencies();
            given(settlementQueryService.getSettlementDetail(SETTLEMENT_ID, USER_ID))
                    .willReturn(detail("IN_PROGRESS"));

            SettlementArchivePreviewResponse preview = settlementArchiveService.getArchivePreview(SETTLEMENT_ID, USER_ID);

            assertThat(preview.settlementStatus()).isEqualTo("IN_PROGRESS");
            verify(settlementPaymentStatusService).getPaymentStatus(SETTLEMENT_ID, USER_ID);
        }

        @Test
        @DisplayName("같은 정산을 다시 조회하면 매번 라이브 데이터를 새로 조회한다")
        void everyCallQueriesLiveDataAgain() {
            stubLiveDataDependencies();
            given(settlementQueryService.getSettlementDetail(SETTLEMENT_ID, USER_ID))
                    .willReturn(detail("CLOSED"));

            settlementArchiveService.getArchivePreview(SETTLEMENT_ID, USER_ID);
            settlementArchiveService.getArchivePreview(SETTLEMENT_ID, USER_ID);

            verify(settlementPaymentStatusService, times(2)).getPaymentStatus(SETTLEMENT_ID, USER_ID);
        }
    }

    @Nested
    @DisplayName("정산 수취 계좌 조회 예외 처리")
    class SettlementAccountHandling {

        @BeforeEach
        void stubLiveDataDependencies() {
            given(settlementQueryService.getSettlementDetail(SETTLEMENT_ID, USER_ID))
                    .willReturn(detail("IN_PROGRESS"));
            given(settlementPaymentStatusService.getPaymentStatus(SETTLEMENT_ID, USER_ID))
                    .willReturn(paymentStatus());
            given(settlementPaymentHistoryService.getPaymentHistory(SETTLEMENT_ID, USER_ID))
                    .willReturn(List.of());
        }

        @Test
        @DisplayName("정산 수취 계좌가 설정되어 있지 않아도 예외 없이 미리보기를 생성한다")
        void buildsPreviewWhenSettlementAccountIsNotSet() {
            given(settlementAccountService.findCurrentAccount(USER_ID, SETTLEMENT_ID))
                    .willThrow(SettlementErrorCode.SETTLEMENT_ACCOUNT_NOT_FOUND.toException());

            SettlementArchivePreviewResponse preview = settlementArchiveService.getArchivePreview(SETTLEMENT_ID, USER_ID);

            assertThat(preview.settlementAccount()).isNull();
        }

        @Test
        @DisplayName("계좌 미설정이 아닌 다른 예외는 그대로 전파한다")
        void propagatesNonAccountNotFoundExceptions() {
            given(settlementAccountService.findCurrentAccount(USER_ID, SETTLEMENT_ID))
                    .willThrow(SettlementErrorCode.SETTLEMENT_ACCESS_DENIED.toException());

            assertThatThrownBy(() ->
                    settlementArchiveService.getArchivePreview(SETTLEMENT_ID, USER_ID)
            ).isInstanceOfSatisfying(
                    DomainException.class,
                    exception -> assertThat(exception.getErrorCode())
                            .isEqualTo(SettlementErrorCode.SETTLEMENT_ACCESS_DENIED)
            );
        }
    }

    @Nested
    @DisplayName("정산 미리보기 내용 검증")
    class ArchivePreviewContent {

        @Test
        @DisplayName("정산 기본정보, 이행현황, 상세 납부내역이 미리보기에 그대로 반영된다")
        void reflectsSettlementDataIntoPreview() {
            given(settlementQueryService.getSettlementDetail(SETTLEMENT_ID, USER_ID))
                    .willReturn(detail("CLOSED"));
            given(settlementPaymentStatusService.getPaymentStatus(SETTLEMENT_ID, USER_ID))
                    .willReturn(paymentStatusWithObligation());
            given(settlementPaymentHistoryService.getPaymentHistory(SETTLEMENT_ID, USER_ID))
                    .willReturn(List.of(historyRecord()));
            given(settlementAccountService.findCurrentAccount(USER_ID, SETTLEMENT_ID))
                    .willThrow(SettlementErrorCode.SETTLEMENT_ACCOUNT_NOT_FOUND.toException());

            SettlementArchivePreviewResponse preview = settlementArchiveService.getArchivePreview(SETTLEMENT_ID, USER_ID);

            assertThat(preview.title()).isEqualTo("여행 정산");
            assertThat(preview.settlementType()).isEqualTo("SHARED");
            assertThat(preview.settlementStatus()).isEqualTo("CLOSED");
            assertThat(preview.splitType()).isEqualTo("EQUAL");
            assertThat(preview.settlementDisplayId()).isEqualTo("ST-1");
            assertThat(preview.paymentStatus().getObligations()).hasSize(1);
            assertThat(preview.paymentStatus().getObligations().get(0).getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
            assertThat(preview.paymentHistory()).hasSize(1);
            assertThat(preview.paymentHistory().get(0).sourceType()).isEqualTo(SourceType.AUTO_MATCH);
            assertThat(preview.settlementAccount()).isNull();
        }
    }

    private SettlementDetailResponse detail(String settlementStatus) {
        return new SettlementDetailResponse(
                SETTLEMENT_ID,
                "여행 정산",
                "홍길동",
                "생활비",
                "SHARED",
                settlementStatus,
                "EQUAL",
                LocalDate.of(2026, 8, 31),
                null,
                null,
                LocalDateTime.of(2026, 8, 1, 10, 0),
                "OWNER"
        );
    }

    private SettlementPaymentStatusResponse paymentStatus() {
        return SettlementPaymentStatusResponse.builder()
                .settlementId(SETTLEMENT_ID)
                .obligations(List.of())
                .totalExpectedAmount(BigDecimal.ZERO)
                .totalPaidAmount(BigDecimal.ZERO)
                .totalRemainingAmount(BigDecimal.ZERO)
                .paidCount(0)
                .partiallyPaidCount(0)
                .unpaidCount(0)
                .progressRate(BigDecimal.ZERO)
                .closable(false)
                .build();
    }

    private SettlementPaymentStatusResponse paymentStatusWithObligation() {
        return SettlementPaymentStatusResponse.builder()
                .settlementId(SETTLEMENT_ID)
                .obligations(List.of(
                        SettlementPaymentObligationResponse.builder()
                                .paymentObligationId(100L)
                                .participantId(200L)
                                .participantName("홍길동")
                                .expectedAmount(new BigDecimal("10000"))
                                .paidAmount(new BigDecimal("10000"))
                                .remainingAmount(BigDecimal.ZERO)
                                .paymentStatus(PaymentStatus.PAID)
                                .build()
                ))
                .totalExpectedAmount(new BigDecimal("10000"))
                .totalPaidAmount(new BigDecimal("10000"))
                .totalRemainingAmount(BigDecimal.ZERO)
                .paidCount(1)
                .partiallyPaidCount(0)
                .unpaidCount(0)
                .progressRate(new BigDecimal("100.00"))
                .closable(true)
                .build();
    }

    private SettlementPaymentHistoryResponse historyRecord() {
        return SettlementPaymentHistoryResponse.builder()
                .paymentRecordId(1L)
                .recordedAt(LocalDateTime.of(2026, 8, 18, 12, 0))
                .payerName("홍길동")
                .amount(new BigDecimal("10000"))
                .sourceType(SourceType.AUTO_MATCH)
                .bankTransactionId(300L)
                .counterpartyName("카카오뱅크 홍길동")
                .externalTransactionId("TX-EXTERNAL-1")
                .build();
    }
}
