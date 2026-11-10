package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDetailResponse;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.type.ContractStatus;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ContractDetailQueryService {

    private final LoanContractService loanContractService;
    private final ContractChangeQueryService contractChangeQueryService;

    public boolean canRequestChange(Long contractId, Long userId) {
        LoanContractResponse contract = loanContractService.findContract(contractId, userId);
        return canRequestChange(contract, contractId, userId);
    }

    
    private boolean canRequestChange(LoanContractResponse contract, Long contractId, Long userId) {
        boolean isCreditor = contract.getCreditorId().equals(userId);
        boolean isDebtor = Objects.equals(contract.getDebtorId(), userId);
        return contract.getStatus() == ContractStatus.COMPLETED
                && (isCreditor || isDebtor)
                && !contractChangeQueryService.hasPendingChangeRequest(contractId);
    }

    public ContractDetailResponse getCheck(Long contractId, Long userId){
        LoanContractResponse contract = loanContractService.findContract(contractId, userId);

        boolean isCreditor = contract.getCreditorId().equals(userId);
        boolean canRequestChange = canRequestChange(contract, contractId, userId);

        String address = isCreditor
                ? contract.getCreditorAddress()
                : contract.getDebtorAddress();

        return ContractDetailResponse.builder()
                .contract(contract)
                .canRequestChange(canRequestChange)
                .isCreditor(isCreditor)
                .address(address)
                .build();
    }
}
