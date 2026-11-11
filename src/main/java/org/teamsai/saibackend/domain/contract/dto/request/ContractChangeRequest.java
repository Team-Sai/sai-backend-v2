package org.teamsai.saibackend.domain.contract.dto.request;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter@Builder
@NoArgsConstructor@AllArgsConstructor
public class ContractChangeRequest {

    @NotBlank(message = "변경 사유는 필수입니다.")
    private String changeReason;

    @Future(message = "변경 만기일은 오늘 이후 날짜여야 합니다.")
    private LocalDate newMaturityDate;

    @DecimalMin(value = "0", inclusive = false, message = "이율은 0보다 커야 합니다.")
    @DecimalMax(value = "20", message = "이율은 20% 이하여야 합니다.")
    private BigDecimal newInterestRate;

    @AssertTrue(message = "이율은 0.5% 단위로 입력해주세요.")
    public boolean isValidInterestRateIncrement() {
        if (newInterestRate == null) return true;
        return newInterestRate.remainder(new BigDecimal("0.5")).compareTo(BigDecimal.ZERO) == 0;
    }

    @Pattern(
            regexp = "EQUAL_PRINCIPAL_AND_INTEREST|EQUAL_PRINCIPAL|BULLET_REPAYMENT",
            message = "상환 방식이 올바르지 않습니다."
    )
    private String newRepaymentType;

    @Min(value = 1, message = "상환일은 1일 이상이어야 합니다.")
    @Max(value = 31, message = "상환일은 31일 이하여야 합니다.")
    private Integer newRepaymentDate;

    @Null(message = "특약사항은 변경 요청에서 바꿀 수 없습니다.")
    private String newTerms;

    @AssertTrue(message = "만기일·이율·상환 방식·상환일 중 하나 이상을 변경해야 합니다.")
    public boolean isAnyConditionChanged() {
        return newMaturityDate != null || newInterestRate != null
                || newRepaymentType != null || newRepaymentDate != null;
    }
}
