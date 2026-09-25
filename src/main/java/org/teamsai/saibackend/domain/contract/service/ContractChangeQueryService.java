package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.entity.LoanContractChangeRequestEntity;
import org.teamsai.saibackend.domain.contract.exception.ContractChangeErrorCode;
import org.teamsai.saibackend.domain.contract.repository.ContractChangeRepository;
import org.teamsai.saibackend.domain.contract.type.ChangeRequestStatus;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContractChangeQueryService {

    private final LoanContractService loanContractService;
    private final ContractChangeRepository contractChangeRepository;
    private final LoanChangeService loanChangeService;


    public LoanContractResponse getContract(Long contractId, Long userID) {
        LoanContractResponse contract = loanContractService.findContract(contractId, userID);

        if(contract.getStatus() != ContractStatus.COMPLETED) {
            throw ContractChangeErrorCode.CONTRACT_NOT_COMPLETED.toException();
        }
        return contract;
    }

    public LoanContractChangeRequestEntity getChangeRequest(Long changeRequestId) {
        return contractChangeRepository.findById(changeRequestId)
                .orElseThrow(ContractChangeErrorCode.CHANGE_REQUEST_NOT_FOUND::toException);

    }

    public Long getPendingChangedContractId(Long contractId) {
        return loanChangeService.findPendingContractByPreviousId(contractId)
                .orElseThrow(ContractChangeErrorCode.CHANGE_REQUEST_NOT_FOUND::toException)
                .getContractId();
    }

    public boolean hasPendingChangeRequest(Long contractId) {
        List<LoanContractChangeRequestEntity> existingRequests = contractChangeRepository.findByContractId(contractId);
        return existingRequests.stream()
                .anyMatch(changeRequest -> ChangeRequestStatus.PENDING.equals(changeRequest.getStatus()));
    }


}
