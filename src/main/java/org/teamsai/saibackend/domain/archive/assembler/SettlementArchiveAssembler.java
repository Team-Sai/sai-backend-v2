package org.teamsai.saibackend.domain.archive.assembler;

import org.teamsai.saibackend.domain.settlement.dto.response.*;

import java.math.BigDecimal;
import java.util.List;


public final class SettlementArchiveAssembler {

    private static final String SETTLEMENT_DISPLAY_ID_PREFIX = "ST-";

    private SettlementArchiveAssembler() {
    }

    public static SettlementArchivePreviewResponse toPreviewResponse(
            SettlementDetailResponse detail,
            SettlementPaymentStatusResponse paymentStatus,
            List<SettlementPaymentHistoryResponse> paymentHistory,
            SettlementAccountResponse settlementAccount
    ) {

        BigDecimal ownerAmount =
                detail.totalAmount()
                        .subtract(paymentStatus.getTotalExpectedAmount());

        return SettlementArchivePreviewResponse.builder()
                .settlementId(detail.settlementId())
                .settlementDisplayId(
                        SETTLEMENT_DISPLAY_ID_PREFIX + detail.settlementId()
                )
                .title(detail.title())
                .ownerName(detail.ownerName())
                .settlementType(detail.settlementType())
                .settlementCategory(detail.settlementCategory())
                .settlementStatus(detail.settlementStatus())
                .splitType(detail.splitType())
                .dueDate(detail.dueDate())
                .createdAt(detail.createdAt())
                .ownerAmount(ownerAmount)
                .paymentStatus(paymentStatus)
                .paymentHistory(paymentHistory)
                .settlementAccount(settlementAccount)
                .build();
    }
}
