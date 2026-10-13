package org.teamsai.saibackend.domain.contract.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import lombok.Getter;
import org.teamsai.saibackend.domain.contract.type.ContractRole;
import org.teamsai.saibackend.domain.contract.type.ContractDashboardStatus;
import org.teamsai.saibackend.domain.contract.type.ContractDashboardPaymentStatus;
import org.teamsai.saibackend.domain.contract.type.TransactionCategory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class ContractDashboardRowResponse {

    private Long contractId;
    private String contractAlias;
    private ContractRole role;
    private TransactionCategory category;
    private BigDecimal principalAmount;
    private BigDecimal totalRemainingAmount;
    private BigDecimal thisMonthDueAmount;
    private ContractDashboardStatus contractStatus;
    private String repaymentStatus;
    private ContractDashboardPaymentStatus paymentStatus;
    private LocalDate maturityDate;
    private LocalDate nearestScheduleDueDate;
    private BigDecimal nextDueAmount;

    @JsonIgnore
    private LocalDateTime createdAt;
}
