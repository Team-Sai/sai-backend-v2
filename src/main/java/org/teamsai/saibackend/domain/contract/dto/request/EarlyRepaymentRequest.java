package org.teamsai.saibackend.domain.contract.dto.request;

import jakarta.validation.constraints.NotNull;

public record EarlyRepaymentRequest(

        @NotNull(message = "입금 내역을 선택해 주세요.")
        Long bankTransactionId
) {
}