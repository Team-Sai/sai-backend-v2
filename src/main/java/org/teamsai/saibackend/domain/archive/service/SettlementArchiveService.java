package org.teamsai.saibackend.domain.archive.service;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.teamsai.saibackend.domain.archive.entity.SettlementArchiveSnapshot;
import org.teamsai.saibackend.domain.archive.repository.SettlementArchiveRepository;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementAccountResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementArchivePreviewResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementDetailResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentHistoryResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.service.SettlementAccountService;
import org.teamsai.saibackend.domain.settlement.service.SettlementPaymentHistoryService;
import org.teamsai.saibackend.domain.settlement.service.SettlementPaymentStatusService;
import org.teamsai.saibackend.domain.settlement.service.SettlementQueryService;
import org.teamsai.saibackend.global.exception.DomainException;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementArchiveService {

    private static final String DOCUMENT_VERSION = "v1";
    private static final String SETTLEMENT_DISPLAY_ID_PREFIX = "ST-";

    private final SettlementArchiveRepository settlementArchiveRepository;
    private final ObjectMapper objectMapper;
    private final SettlementQueryService settlementQueryService;
    private final SettlementPaymentStatusService settlementPaymentStatusService;
    private final SettlementPaymentHistoryService settlementPaymentHistoryService;
    private final SettlementAccountService settlementAccountService;

    private ObjectMapper snapshotObjectMapper;

    @PostConstruct
    private void initSnapshotObjectMapper() {
        snapshotObjectMapper = objectMapper.rebuild()
                .changeDefaultVisibility(visibility -> visibility.withFieldVisibility(JsonAutoDetect.Visibility.ANY))
                .build();
    }

    public SettlementArchivePreviewResponse getArchivePreview(Long settlementId, Long userId) {
        SettlementDetailResponse detail = settlementQueryService.getSettlementDetail(settlementId, userId);
        boolean closed = "CLOSED".equals(detail.settlementStatus());

        if (closed) {
            Optional<SettlementArchivePreviewResponse> existingSnapshot = findExistingSnapshot(settlementId);
            if (existingSnapshot.isPresent()) {
                return existingSnapshot.get();
            }
        }

        SettlementArchivePreviewResponse preview = buildPreview(settlementId, userId, detail);

        if (closed) {
            saveSnapshot(settlementId, preview);
        }

        return preview;
    }

    private SettlementArchivePreviewResponse buildPreview(Long settlementId, Long userId, SettlementDetailResponse detail) {
        ArchiveData data = gatherArchiveData(settlementId, userId, detail);

        return SettlementArchivePreviewResponse.builder()
                .settlementId(detail.settlementId())
                .settlementDisplayId(data.settlementDisplayId())
                .title(detail.title())
                .ownerName(data.archiveDetail().ownerName())
                .settlementType(detail.settlementType())
                .settlementCategory(detail.settlementCategory())
                .settlementStatus(detail.settlementStatus())
                .splitType(detail.splitType())
                .dueDate(detail.dueDate())
                .createdAt(detail.createdAt())
                .paymentStatus(data.paymentStatus())
                .paymentHistory(data.paymentHistory())
                .settlementAccount(data.settlementAccount().orElse(null))
                .documentVersion(DOCUMENT_VERSION)
                .build();
    }

    private Optional<SettlementArchivePreviewResponse> findExistingSnapshot(Long settlementId) {
        return settlementArchiveRepository.findBySettlementId(settlementId)
                .flatMap(snapshot -> deserializeSnapshot(settlementId, snapshot.getSnapshotJson()));
    }

    private Optional<SettlementArchivePreviewResponse> deserializeSnapshot(Long settlementId, String snapshotJson) {
        try {
            return Optional.of(snapshotObjectMapper.readValue(snapshotJson, SettlementArchivePreviewResponse.class));
        } catch (JacksonException e) {
            log.warn("저장된 정산 기록 스냅샷 역직렬화 실패 - settlementId: {}", settlementId, e);
            return Optional.empty();
        }
    }

    private void saveSnapshot(Long settlementId, SettlementArchivePreviewResponse preview) {
        try {
            String snapshotJson = snapshotObjectMapper.writeValueAsString(preview);

            settlementArchiveRepository.save(
                    SettlementArchiveSnapshot.builder()
                            .settlementId(settlementId)
                            .snapshotJson(snapshotJson)
                            .build()
            );

            log.info("[정산 기록 스냅샷 저장] settlementId: {}", settlementId);
        } catch (JacksonException e) {
            log.error("정산 기록 스냅샷 저장 중 오류 발생 - settlementId: {}", settlementId, e);
            throw new RuntimeException("정산 기록 스냅샷 저장 처리 중 오류가 발생했습니다.", e);
        }
    }

    private ArchiveData gatherArchiveData(Long settlementId, Long userId, SettlementDetailResponse archiveDetail) {
        SettlementPaymentStatusResponse paymentStatus = settlementPaymentStatusService.getPaymentStatus(settlementId, userId);
        List<SettlementPaymentHistoryResponse> paymentHistory = settlementPaymentHistoryService.getPaymentHistory(settlementId, userId);
        Optional<SettlementAccountResponse> settlementAccount = findSettlementAccountIfExists(settlementId, userId);

        return new ArchiveData(
                archiveDetail,
                paymentStatus,
                paymentHistory,
                settlementAccount,
                SETTLEMENT_DISPLAY_ID_PREFIX + settlementId
        );
    }

    private record ArchiveData(
            SettlementDetailResponse archiveDetail,
            SettlementPaymentStatusResponse paymentStatus,
            List<SettlementPaymentHistoryResponse> paymentHistory,
            Optional<SettlementAccountResponse> settlementAccount,
            String settlementDisplayId
    ) {
    }

    private Optional<SettlementAccountResponse> findSettlementAccountIfExists(Long settlementId, Long userId) {
        try {
            return Optional.of(settlementAccountService.findCurrentAccount(userId, settlementId));
        } catch (DomainException e) {
            if (e.getErrorCode() == SettlementErrorCode.SETTLEMENT_ACCOUNT_NOT_FOUND) {
                log.warn("정산 수취 계좌 미설정 - settlementId: {}", settlementId);
                return Optional.empty();
            }
            throw e;
        }
    }
}
