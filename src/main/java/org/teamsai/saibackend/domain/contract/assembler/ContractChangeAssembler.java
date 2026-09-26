package org.teamsai.saibackend.domain.contract.assembler;

import org.teamsai.saibackend.domain.contract.dto.request.ContractChangeRequest;
import org.teamsai.saibackend.domain.contract.type.ContractStatus;
import org.teamsai.saibackend.domain.contract.type.RepaymentMethod;
import org.teamsai.saibackend.domain.contract.dto.response.ChangeLoanContractResponse;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;

import java.time.LocalDateTime;

public final class ContractChangeAssembler {

    private ContractChangeAssembler() {
    }

    public static ChangeLoanContractResponse toChangedContract(
            LoanContractResponse contract,
            ContractChangeRequest request,
            Long previousContractId,
            LocalDateTime now
    ) {
        return ChangeLoanContractResponse.builder()
                .previousContractId(previousContractId)
                .creditorId(contract.getCreditorId())
                .debtorId(contract.getDebtorId())
                .relationType(contract.getRelationType())
                .principalAmount(contract.getPrincipalAmount())
                .interestRate(request.getNewInterestRate() != null ? request.getNewInterestRate() : contract.getInterestRate())
                .repaymentType(request.getNewRepaymentType() != null ? RepaymentMethod.valueOf(request.getNewRepaymentType()) : contract.getRepaymentType())
                .startDate(contract.getStartDate())
                .maturityDate(request.getNewMaturityDate() != null ? request.getNewMaturityDate() : contract.getMaturityDate())
                .repaymentDay(request.getNewRepaymentDate() != null ? request.getNewRepaymentDate() : contract.getRepaymentDay())
                .creditorAddress(contract.getCreditorAddress())
                .debtorAddress(contract.getDebtorAddress())
                .contractAlias(contract.getContractAlias())
                .terms(request.getNewTerms() != null ? request.getNewTerms() : contract.getTerms())
                .status(ContractStatus.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
