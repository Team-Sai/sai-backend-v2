package org.teamsai.saibackend.domain.archive.assembler;

import org.teamsai.saibackend.domain.settlement.dto.response.SettlementAccountResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementArchivePreviewResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementDetailResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentHistoryResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;

import java.util.List;


public final class SettlementArchiveAssembler {

    private static final String DOCUMENT_VERSION = "v1";
    private static final String SETTLEMENT_DISPLAY_ID_PREFIX = "ST-";

    private SettlementArchiveAssembler() {
    }

    public static SettlementArchivePreviewResponse toPreviewResponse(
            SettlementDetailResponse detail,
            SettlementPaymentStatusResponse paymentStatus,
            List<SettlementPaymentHistoryResponse> paymentHistory,
            SettlementAccountResponse settlementAccount
    ) {
        return SettlementArchivePreviewResponse.builder()
                .settlementId(detail.settlementId())
                .settlementDisplayId(SETTLEMENT_DISPLAY_ID_PREFIX + detail.settlementId())
                .title(detail.title())
                .ownerName(detail.ownerName())
                .settlementType(detail.settlementType())
                .settlementCategory(detail.settlementCategory())
                .settlementStatus(detail.settlementStatus())
                .splitType(detail.splitType())
                .dueDate(detail.dueDate())
                .createdAt(detail.createdAt())
                .paymentStatus(paymentStatus)
                .paymentHistory(paymentHistory)
                .settlementAccount(settlementAccount)
                .documentVersion(DOCUMENT_VERSION)
                .build();
    }
}
