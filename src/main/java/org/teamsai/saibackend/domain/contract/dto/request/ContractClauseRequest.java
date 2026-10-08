package org.teamsai.saibackend.domain.contract.dto.request;

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
    
    private BigDecimal principalAmount;

    private BigDecimal interestRate;

    private BigDecimal lateInterestRate;

    private LocalDate startDate;

    private LocalDate maturityDate;

    private Integer repaymentDay;

    private String repaymentType;

    private String contractAlias;

    private String terms;

    private String message;
}