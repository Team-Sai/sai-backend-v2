package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.account.dto.response.LinkedBankAccountResponse;
import org.teamsai.saibackend.domain.account.dto.type.ConnectionStatus;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.contract.type.ContractAccountStatus;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.entity.ContractAccount;
import org.teamsai.saibackend.domain.contract.entity.LoanContract;
import org.teamsai.saibackend.domain.contract.exception.LoanContractErrorCode;
import org.teamsai.saibackend.domain.contract.repository.ContractAccountRepository;
import org.teamsai.saibackend.domain.contract.repository.LoanContractRepository;

import java.util.Objects;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ContractAccountService {

    private final ContractAccountRepository contractAccountRepository;
    private final LoanContractRepository loanContractRepository;
    private final LinkedBankAccountService linkedBankAccountService;

    @Transactional(readOnly = true)
    public List<LinkedBankAccountResponse> getSelectableAccounts(Long userId) {
        return linkedBankAccountService.getLinkedAccounts(userId).stream()
                .filter(account -> ConnectionStatus.AVAILABLE.name()
                        .equals(account.connectionStatus()))
                .toList();
    }

    @Transactional(readOnly = true)
    public LinkedBankAccountResponse getCurrentAccount(
            Long contractId,
            Long userId
    ) {
        LoanContract contract = findContract(contractId);
        validateCreditor(contract, userId);

        ContractAccount activeAccount = contractAccountRepository
                .findLatestByContractIdAndStatus(contractId, ContractAccountStatus.ACTIVE)
                .orElseThrow(LoanContractErrorCode.CONTRACT_ACCOUNT_NOT_FOUND::toException);

        Long activeLinkedAccountId = activeAccount.getLinkedAccount().getLinkedAccountId();

        return linkedBankAccountService.getLinkedAccounts(userId).stream()
                .filter(account -> Objects.equals(account.linkedAccountId(), activeLinkedAccountId))
                .findFirst()
                .orElseThrow(
                        LoanContractErrorCode.INVALID_LINKED_ACCOUNT
                                ::toException
                );
    }

    @Transactional
    public void createContractAccount(Long contractId, Long userId, Long linkedAccountId) {
        if (linkedAccountId == null) return;

        loanContractRepository.findWithLockByContractId(contractId)
                .orElseThrow(LoanContractErrorCode.CONTRACT_NOT_FOUND::toException);

        validateSelectable(userId, linkedAccountId);

        try {
            retireActiveAccount(contractId, ContractAccountStatus.REPLACED);
        } catch (RuntimeException e) {
        }

        insertActiveAccount(contractId, linkedAccountId);
    }

    @Transactional
    public void changeContractAccount(Long contractId, Long userId, Long newLinkedAccountId) {
        loanContractRepository.findWithLockByContractId(contractId)
                .orElseThrow(LoanContractErrorCode.CONTRACT_NOT_FOUND::toException);

        validateContractOwner(contractId, userId);
        validateSelectable(userId, newLinkedAccountId);

        retireActiveAccount(contractId, ContractAccountStatus.REPLACED);
        insertActiveAccount(contractId, newLinkedAccountId);
    }

    @Transactional
    public void deactivateContractAccount(Long contractId, Long userId) {
        loanContractRepository.findWithLockByContractId(contractId)
                .orElseThrow(LoanContractErrorCode.CONTRACT_NOT_FOUND::toException);

        validateContractOwner(contractId, userId);
        retireActiveAccount(contractId, ContractAccountStatus.DISABLED);
    }

    private void retireActiveAccount(Long contractId, ContractAccountStatus status) {
        ContractAccount activeAccount = contractAccountRepository
                .findLatestByContractIdAndStatus(contractId, ContractAccountStatus.ACTIVE)
                .orElseThrow(LoanContractErrorCode.CONTRACT_ACCOUNT_NOT_FOUND::toException);

        activeAccount.deactivate(status);
    }

    private void validateContractOwner(Long contractId, Long userId) {
        LoanContract contract = findContract(contractId);
        validateCreditor(contract, userId);

        if (contract.getStatus() != ContractStatus.DRAFT && contract.getStatus() != ContractStatus.PENDING) {
            throw LoanContractErrorCode.CONTRACT_ACCESS_DENIED.toException();
        }
    }

    private LoanContract findContract(Long contractId) {
        return loanContractRepository.findById(contractId)
                .orElseThrow(LoanContractErrorCode.CONTRACT_NOT_FOUND::toException);
    }

    private void validateCreditor(
            LoanContract contract,
            Long userId
    ) {
        if (!Objects.equals(contract.getCreditor().getUserId(), userId)) {
            throw LoanContractErrorCode.CONTRACT_ACCESS_DENIED.toException();
        }
    }

    private void validateSelectable(Long userId, Long linkedAccountId) {
        boolean isSelectable = getSelectableAccounts(userId).stream()
                .anyMatch(account -> account.linkedAccountId().equals(linkedAccountId));

        if (!isSelectable) {
            throw LoanContractErrorCode.INVALID_LINKED_ACCOUNT.toException();
        }
    }

    private void insertActiveAccount(Long contractId, Long linkedAccountId) {
        ContractAccount contractAccount = ContractAccount.builder()
                .linkedAccount(linkedBankAccountService.getReferenceById(linkedAccountId))
                .loanContract(loanContractRepository.getReferenceById(contractId))
                .accountStatus(ContractAccountStatus.ACTIVE)
                .build();

        contractAccountRepository.save(contractAccount);
    }
}
