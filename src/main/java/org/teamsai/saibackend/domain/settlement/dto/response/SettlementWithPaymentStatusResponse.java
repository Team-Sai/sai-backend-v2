package org.teamsai.saibackend.domain.settlement.dto.response;

public record SettlementWithPaymentStatusResponse(
        SettlementListResponse settlement,
        SettlementPaymentStatusResponse paymentStatus
) {
}
