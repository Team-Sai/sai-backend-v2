package org.teamsai.saibackend.domain.contract.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.teamsai.saibackend.domain.contract.entity.LoanContract;
import org.teamsai.saibackend.domain.contract.type.ContractRelationType;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.dto.request.RepaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class LoanContractResponse {

    private Long contractId;
    private Long previousContractId;

    @JsonIgnore
    private Long creditorId;
    @JsonIgnore
    private Long debtorId;

    private String creditorName;
    private String creditorBirthDate;
    private String creditorAddress;
    private String creditorSignature;

    private String debtorName;
    private String debtorBirthDate;
    private String debtorAddress;
    private String debtorSignature;

    private ContractRelationType relationType;
    private BigDecimal principalAmount;
    private BigDecimal interestRate;
    private RepaymentMethod repaymentType;

    private LocalDate startDate;
    private LocalDate maturityDate;

    private Integer repaymentDay;

    private String contractAlias;
    private String terms;

    private ContractStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isCreditor(Long userId) {
        return Objects.equals(creditorId, userId);
    }

    public boolean isDebtor(Long userId) {
        return Objects.equals(debtorId, userId);
    }

    public static LoanContractResponse from(LoanContract contract) {
        return LoanContractResponse.builder()
                .contractId(contract.getContractId())
                .previousContractId(contract.getPreviousContract() != null ? contract.getPreviousContract().getContractId() : null)
                .creditorId(contract.getCreditor().getUserId())
                .debtorId(contract.getDebtor() != null ? contract.getDebtor().getUserId() : null)
                .creditorAddress(contract.getCreditorAddress())
                .creditorSignature(contract.getCreditorSignature())
                .debtorAddress(contract.getDebtorAddress())
                .debtorSignature(contract.getDebtorSignature())
                .relationType(contract.getRelationType())
                .principalAmount(contract.getPrincipalAmount())
                .interestRate(contract.getInterestRate())
                .repaymentType(contract.getRepaymentType())
                .startDate(contract.getStartDate())
                .maturityDate(contract.getMaturityDate())
                .repaymentDay(contract.getRepaymentDay())
                .contractAlias(contract.getContractAlias())
                .terms(contract.getTerms())
                .status(contract.getStatus())
                .createdAt(contract.getCreatedAt())
                .updatedAt(contract.getUpdatedAt())
                .build();
    }
}