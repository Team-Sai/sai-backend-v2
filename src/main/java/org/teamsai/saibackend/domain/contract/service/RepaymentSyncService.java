package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.exception.RepaymentScheduleErrorCode;
import org.teamsai.saibackend.domain.contract.repository.ContractAccountRepository;
import org.teamsai.saibackend.domain.contract.type.ContractAccountStatus;
import org.teamsai.saibackend.domain.matching.model.AutoMatchingExecutionResult;
import org.teamsai.saibackend.domain.matching.type.MatchingTargetType;
import org.teamsai.saibackend.domain.transaction.service.TransactionSyncMatchService;

@Service
@RequiredArgsConstructor
public class RepaymentSyncService {

    private final LoanContractService loanContractService;
    private final ContractAccountRepository contractAccountRepository;
    private final TransactionSyncMatchService transactionSyncMatchService;

    public AutoMatchingExecutionResult sync(Long contractId, Long userId) {
        LoanContractResponse contract = loanContractService.findContract(contractId, userId); // 계약 당사자 확인

        Long linkedAccountId = contractAccountRepository
                .findLatestByContractIdAndStatus(contractId, ContractAccountStatus.ACTIVE)
                .orElseThrow(RepaymentScheduleErrorCode.EARLY_REPAYMENT_ACCOUNT_MISSING::toException)
                .getLinkedAccount()
                .getLinkedAccountId();

        // 수취 계좌 주인(채권자) 기준으로, 이 계약의 입금만 매칭한다.
        return transactionSyncMatchService.syncAndMatch(
                contract.getCreditorId(), linkedAccountId, MatchingTargetType.LOAN, contractId);
    }
}