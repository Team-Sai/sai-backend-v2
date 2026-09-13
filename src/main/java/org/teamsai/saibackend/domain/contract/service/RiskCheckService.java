package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.repository.LoanContractRepository;
import org.teamsai.saibackend.domain.contract.type.ContractRelationType;

@Service
@RequiredArgsConstructor
public class RiskCheckService {

    private final LoanContractRepository loanContractRepository;

    @Transactional(readOnly = true)
    public Long sumCompletedPrincipalByCreditor(Long userId) {
        return loanContractRepository.sumPrincipalByCreditorAndStatusAndRelationType(
                userId,
                ContractStatus.COMPLETED,
                ContractRelationType.FAMILY
        );
    }
}
