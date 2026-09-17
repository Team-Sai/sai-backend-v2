package org.teamsai.saibackend.domain.archive;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsai.saibackend.domain.archive.entity.SettlementArchiveSnapshot;
import org.teamsai.saibackend.domain.archive.repository.SettlementArchiveRepository;
import org.teamsai.saibackend.domain.archive.service.SettlementArchiveService;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;
import org.teamsai.saibackend.domain.payment.type.SourceType;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementAccountResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementArchivePreviewResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementDetailResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentHistoryResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentObligationResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.service.SettlementAccountService;
import org.teamsai.saibackend.domain.settlement.service.SettlementPaymentHistoryService;
import org.teamsai.saibackend.domain.settlement.service.SettlementPaymentStatusService;
import org.teamsai.saibackend.domain.settlement.service.SettlementQueryService;
import org.teamsai.saibackend.domain.settlement.type.SettlementAccountStatus;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementArchiveService 단위 테스트")
class SettlementArchiveServiceTest {

    private static final Long SETTLEMENT_ID = 1L;
    private static final Long USER_ID = 10L;

    @Mock
    private SettlementArchiveRepository settlementArchiveRepository;

    @Mock
    private SettlementQueryService settlementQueryService;

    @Mock
    private SettlementPaymentStatusService settlementPaymentStatusService;

    @Mock
    private SettlementPaymentHistoryService settlementPaymentHistoryService;

    @Mock
    private SettlementAccountService settlementAccountService;

    private SettlementArchiveService settlementArchiveService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new JsonMapper();

        settlementArchiveService = new SettlementArchiveService(
                settlementArchiveRepository,
                objectMapper,
                settlementQueryService,
                settlementPaymentStatusService,
                settlementPaymentHistoryService,
                settlementAccountService
        );

        ReflectionTestUtils.invokeMethod(settlementArchiveService, "initSnapshotObjectMapper");
    }

    @Nested
    @DisplayName("아카이브 캐시 동작")
    class CachingBehavior {

        private void stubLiveDataDependencies() {
            given(settlementPaymentStatusService.getPaymentStatus(SETTLEMENT_ID, USER_ID))
                    .willReturn(paymentStatus());
            given(settlementPaymentHistoryService.getPaymentHistory(SETTLEMENT_ID, USER_ID))
                    .willReturn(List.of());
            given(settlementAccountService.findCurrentAccount(USER_ID, SETTLEMENT_ID))
                    .willThrow(SettlementErrorCode.SETTLEMENT_ACCOUNT_NOT_FOUND.toException());
        }

        @Test
        @DisplayName("종료된 정산이고 저장된 스냅샷이 없으면 새로 만들어서 JSON으로 저장한다")
        void buildsAndSavesSnapshotWhenClosedSettlementHasNoCachedSnapshot() {
            stubLiveDataDependencies();
            given(settlementQueryService.getSettlementDetail(SETTLEMENT_ID, USER_ID))
                    .willReturn(detail("CLOSED"));
            given(settlementArchiveRepository.findBySettlementId(SETTLEMENT_ID))
                    .willReturn(Optional.empty());

            SettlementArchivePreviewResponse preview = settlementArchiveService.getArchivePreview(SETTLEMENT_ID, USER_ID);

            assertThat(preview.settlementId()).isEqualTo(SETTLEMENT_ID);
            assertThat(preview.settlementStatus()).isEqualTo("CLOSED");

            ArgumentCaptor<SettlementArchiveSnapshot> captor = ArgumentCaptor.forClass(SettlementArchiveSnapshot.class);
            verify(settlementArchiveRepository).save(captor.capture());

            SettlementArchiveSnapshot saved = captor.getValue();
            assertThat(saved.getSettlementId()).isEqualTo(SETTLEMENT_ID);
            assertThat(saved.getSnapshotJson()).contains("\"settlementId\"");
        }

        @Test
        @DisplayName("종료된 정산이고 저장된 스냅샷이 있으면 라이브 데이터를 조회하지 않고 캐시된 스냅샷을 그대로 반환한다")
        void returnsCachedSnapshotWithoutQueryingLiveDataWhenClosedSettlementHasCachedSnapshot() {
            given(settlementQueryService.getSettlementDetail(SETTLEMENT_ID, USER_ID))
                    .willReturn(detail("CLOSED"));

            SettlementArchivePreviewResponse cachedSnapshot = previewSnapshot();
            String snapshotJson = snapshotJson(cachedSnapshot);
            given(settlementArchiveRepository.findBySettlementId(SETTLEMENT_ID))
                    .willReturn(Optional.of(SettlementArchiveSnapshot.builder()
                            .settlementId(SETTLEMENT_ID)
                            .snapshotJson(snapshotJson)
                            .build()));

            SettlementArchivePreviewResponse result = settlementArchiveService.getArchivePreview(SETTLEMENT_ID, USER_ID);

            assertThat(result.settlementId()).isEqualTo(cachedSnapshot.settlementId());
            assertThat(result.title()).isEqualTo(cachedSnapshot.title());
            assertThat(result.paymentStatus().getTotalPaidAmount()).isEqualByComparingTo(cachedSnapshot.paymentStatus().getTotalPaidAmount());
            assertThat(result.paymentHistory()).hasSize(1);
            assertThat(result.settlementAccount().getBankName()).isEqualTo("신한은행");

            verify(settlementPaymentStatusService, never()).getPaymentStatus(any(), any());
            verify(settlementPaymentHistoryService, never()).getPaymentHistory(any(), any());
            verify(settlementArchiveRepository, never()).save(any());
        }

        @Test
        @DisplayName("진행 중인 정산은 캐시를 조회하지 않고 매번 라이브 데이터로 새로 만들며 저장하지도 않는다")
        void alwaysRebuildsFromLiveDataAndNeverCachesInProgressSettlement() {
            stubLiveDataDependencies();
            given(settlementQueryService.getSettlementDetail(SETTLEMENT_ID, USER_ID))
                    .willReturn(detail("IN_PROGRESS"));

            SettlementArchivePreviewResponse preview = settlementArchiveService.getArchivePreview(SETTLEMENT_ID, USER_ID);

            assertThat(preview.settlementStatus()).isEqualTo("IN_PROGRESS");

            verify(settlementArchiveRepository, never()).findBySettlementId(any());
            verify(settlementArchiveRepository, never()).save(any());
            verify(settlementPaymentStatusService).getPaymentStatus(SETTLEMENT_ID, USER_ID);
        }

        @Test
        @DisplayName("같은 종료 정산을 다시 조회하면 첫 저장 시점의 스냅샷 값 그대로 반환된다")
        void secondCallReturnsSameSnapshotCapturedOnFirstCall() {
            stubLiveDataDependencies();
            given(settlementQueryService.getSettlementDetail(SETTLEMENT_ID, USER_ID))
                    .willReturn(detail("CLOSED"));

            List<SettlementArchiveSnapshot> savedSnapshots = new java.util.ArrayList<>();
            given(settlementArchiveRepository.findBySettlementId(SETTLEMENT_ID))
                    .willAnswer(invocation -> savedSnapshots.isEmpty()
                            ? Optional.empty()
                            : Optional.of(savedSnapshots.get(0)));
            org.mockito.Mockito.doAnswer(invocation -> {
                SettlementArchiveSnapshot saved = invocation.getArgument(0);
                savedSnapshots.add(saved);
                return saved;
            }).when(settlementArchiveRepository).save(any());

            SettlementArchivePreviewResponse first = settlementArchiveService.getArchivePreview(SETTLEMENT_ID, USER_ID);
            SettlementArchivePreviewResponse second = settlementArchiveService.getArchivePreview(SETTLEMENT_ID, USER_ID);

            assertThat(second.paymentStatus().getTotalPaidAmount()).isEqualByComparingTo(first.paymentStatus().getTotalPaidAmount());
            verify(settlementArchiveRepository, times(1)).save(any());
            // 두 번째 호출은 캐시된 스냅샷을 반환하므로 라이브 데이터를 다시 조회하지 않는다 (호출 1회 = 첫 호출뿐).
            verify(settlementPaymentStatusService, times(1)).getPaymentStatus(SETTLEMENT_ID, USER_ID);
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
            given(settlementArchiveRepository.findBySettlementId(SETTLEMENT_ID))
                    .willReturn(Optional.empty());
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

    private String snapshotJson(SettlementArchivePreviewResponse snapshot) {
        ObjectMapper snapshotMapper = JsonMapper.builder()
                .changeDefaultVisibility(visibility -> visibility.withFieldVisibility(JsonAutoDetect.Visibility.ANY))
                .build();
        return snapshotMapper.writeValueAsString(snapshot);
    }

    private SettlementArchivePreviewResponse previewSnapshot() {
        return SettlementArchivePreviewResponse.builder()
                .settlementId(SETTLEMENT_ID)
                .settlementDisplayId("ST-1")
                .title("여행 정산")
                .ownerName("홍길동")
                .settlementType("SHARED")
                .settlementCategory("생활비")
                .settlementStatus("CLOSED")
                .splitType("EQUAL")
                .dueDate(LocalDate.of(2026, 8, 31))
                .createdAt(LocalDateTime.of(2026, 8, 1, 10, 0))
                .paymentStatus(paymentStatusWithObligation())
                .paymentHistory(List.of(historyRecord()))
                .settlementAccount(SettlementAccountResponse.builder()
                        .settlementAccountId(1L)
                        .settlementId(SETTLEMENT_ID)
                        .linkedAccountId(2L)
                        .accountStatus(SettlementAccountStatus.ACTIVE)
                        .bankName("신한은행")
                        .maskedAccountNumber("110-***-456789")
                        .accountHolderName("홍길동")
                        .selectedAt(LocalDateTime.of(2026, 8, 1, 10, 0))
                        .build())
                .documentVersion("v1")
                .build();
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
