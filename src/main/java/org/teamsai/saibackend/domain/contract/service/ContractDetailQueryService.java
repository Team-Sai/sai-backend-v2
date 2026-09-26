package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDetailResponse;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ContractDetailQueryService {

    private final LoanContractService loanContractService;
    private final ContractChangeQueryService contractChangeQueryService;

    public boolean canRequestChange(Long contractId, Long userId) {
        LoanContractResponse contract = loanContractService.findContract(contractId, userId);
        boolean isCreditor = contract.getCreditorId().equals(userId);
        boolean isDebtor = Objects.equals(contract.getDebtorId(), userId);
        return (isCreditor || isDebtor) && !contractChangeQueryService.hasPendingChangeRequest(contractId);
    }


    public ContractDetailResponse getCheck(Long contractId, Long userId){
        LoanContractResponse contract = loanContractService.findContract(contractId, userId);

        boolean isCreditor = contract.getCreditorId().equals(userId);
        boolean isDebtor = Objects.equals(contract.getDebtorId(), userId);
        boolean canRequestChange = (isCreditor || isDebtor) && !contractChangeQueryService.hasPendingChangeRequest(contractId);

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
