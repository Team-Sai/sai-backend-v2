package org.teamsai.saibackend.domain.contract.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContractLegalReviewRequest {

    @NotNull(message = "원금은 필수 입력값입니다.")
    @Positive(message = "원금은 0보다 커야 합니다.")
    private BigDecimal principalAmount;

    @NotNull(message = "이자율은 필수 입력값입니다.")
    @DecimalMin(
            value = "0.0",
            message = "이자율은 0 이상이어야 합니다."
    )
    private BigDecimal interestRate;

    @DecimalMin(
            value = "0.0",
            message = "연체 이자율은 0 이상이어야 합니다."
    )
    private BigDecimal lateInterestRate;

    private LocalDate maturityDate;
}