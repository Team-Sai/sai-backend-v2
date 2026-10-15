package org.teamsai.saibackend.domain.contract.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RagContractReviewRequest {

    private BigDecimal principal;

    private BigDecimal interestRate;

    private BigDecimal lateInterestRate;

    private String repaymentDate;
}
