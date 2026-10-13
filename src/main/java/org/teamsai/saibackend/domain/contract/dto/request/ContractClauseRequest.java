package org.teamsai.saibackend.domain.contract.dto.request;

import jakarta.validation.constraints.*;
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
public class ContractClauseRequest {

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

    private LocalDate startDate;

    private LocalDate maturityDate;

    private Integer repaymentDay;

    private String repaymentType;

    @Size(
            max = 100,
            message = "계약 별칭은 100자 이내로 입력해 주세요."
    )
    private String contractAlias;

    @Size(
            max = 5000,
            message = "특약 내용은 5,000자 이내로 입력해 주세요."
    )
    private String terms;

    @NotBlank(message = "AI에게 요청할 내용을 입력해 주세요.")
    @Size(
            max = 1000,
            message = "요청 내용은 1,000자 이내로 입력해 주세요."
    )
    private String message;
}