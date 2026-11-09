package org.teamsai.saibackend.domain.contract.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record EarlyRepaymentRequest(

        @NotNull(message = "입금 내역을 선택해 주세요.")
        Long bankTransactionId,

        @NotNull(message = "수수료를 입력해 주세요.")
        @PositiveOrZero(message = "수수료는 0원 이상이어야 합니다.")
        @Digits(integer = 13, fraction = 0, message = "수수료는 원 단위로 입력해 주세요.")
        BigDecimal feeAmount
) {
}