package org.teamsai.saibackend.domain.archive.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.archive.assembler.SettlementArchiveAssembler;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementAccountResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementArchivePreviewResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementDetailResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentHistoryResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.service.SettlementAccountService;
import org.teamsai.saibackend.domain.settlement.service.SettlementPaymentHistoryQueryService;
import org.teamsai.saibackend.domain.settlement.service.SettlementPaymentStatusQueryService;
import org.teamsai.saibackend.domain.settlement.service.SettlementQueryService;
import org.teamsai.saibackend.global.exception.DomainException;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementArchiveQueryService {

    private final SettlementQueryService settlementQueryService;
    private final SettlementPaymentStatusQueryService settlementPaymentStatusService;
    private final SettlementPaymentHistoryQueryService settlementPaymentHistoryService;
    private final SettlementAccountService settlementAccountService;

    public SettlementArchivePreviewResponse getArchivePreview(Long settlementId, Long userId) {
        SettlementDetailResponse detail = settlementQueryService.getSettlementDetail(settlementId, userId);

        SettlementPaymentStatusResponse paymentStatus = settlementPaymentStatusService.getPaymentStatus(settlementId, userId);
        List<SettlementPaymentHistoryResponse> paymentHistory = settlementPaymentHistoryService.getPaymentHistory(settlementId, userId);
        SettlementAccountResponse settlementAccount = findSettlementAccountIfExists(settlementId, userId).orElse(null);

        return SettlementArchiveAssembler.toPreviewResponse(detail, paymentStatus, paymentHistory, settlementAccount);
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
