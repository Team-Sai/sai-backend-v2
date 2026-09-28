package org.teamsai.saibackend.domain.contract.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.contract.type.ContractStatus;
import org.teamsai.saibackend.domain.contract.dto.response.ChangeLoanContractResponse;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.entity.LoanContract;
import org.teamsai.saibackend.domain.contract.exception.LoanContractErrorCode;
import org.teamsai.saibackend.domain.contract.repository.LoanContractRepository;
import org.teamsai.saibackend.domain.user.entity.User;

import java.util.Optional;


@Service
@RequiredArgsConstructor
public class LoanChangeService {

    private final LoanContractRepository contractRepository;
    private final LoanContractService loanContractService;
    private final EntityManager entityManager;

    @Transactional
    public void insertChangedContract(ChangeLoanContractResponse changedContract) {
        LoanContract contract = LoanContract.builder()
                .previousContract(changedContract.getPreviousContractId() != null
                        ? entityManager.getReference(LoanContract.class, changedContract.getPreviousContractId())
                        : null)
                .creditor(entityManager.getReference(User.class, changedContract.getCreditorId()))
                .debtor(changedContract.getDebtorId() != null
                        ? entityManager.getReference(User.class, changedContract.getDebtorId())
                        : null)
                .relationType(changedContract.getRelationType())
                .principalAmount(changedContract.getPrincipalAmount())
                .interestRate(changedContract.getInterestRate())
                .repaymentType(changedContract.getRepaymentType())
                .startDate(changedContract.getStartDate())
                .maturityDate(changedContract.getMaturityDate())
                .repaymentDay(changedContract.getRepaymentDay())
                .creditorAddress(changedContract.getCreditorAddress())
                .debtorAddress(changedContract.getDebtorAddress())
                .contractAlias(changedContract.getContractAlias())
                .terms(changedContract.getTerms())
                .status(changedContract.getStatus())
                .build();

        contractRepository.save(contract);
    }

    public Optional<LoanContractResponse> findPendingContractByPreviousId(Long previousContractId) {
        LoanContract previousContract = entityManager.getReference(LoanContract.class, previousContractId);
        return contractRepository.findByPreviousContractAndStatus(previousContract, ContractStatus.PENDING)
                .map(LoanContractResponse::from);
    }

    @Transactional
    public void rejectChangedContract(Long contractId) {
        LoanContract contract = contractRepository.findById(contractId)
                .orElseThrow(LoanContractErrorCode.CONTRACT_NOT_FOUND::toException);
        contract.changeStatus(ContractStatus.CHANGE_REJECTED);
    }

    @Transactional
    public void supersedeContract(Long contractId) {
        LoanContract contract = contractRepository.findById(contractId)
                .orElseThrow(LoanContractErrorCode.CONTRACT_NOT_FOUND::toException);
        contract.changeStatus(ContractStatus.SUPERSEDED);
    }

    @Transactional
    public void updateCreditorSignatureOnly(Long contractId, String signaturePath) {
        LoanContract contract = contractRepository.findById(contractId)
                .orElseThrow(LoanContractErrorCode.CONTRACT_NOT_FOUND::toException);

        if (!contract.signCreditorIfUnsigned(signaturePath, ContractStatus.COMPLETED)) {
            throw LoanContractErrorCode.CONTRACT_ALREADY_COMPLETED.toException();
        }
    }

    @Transactional
    public void updateDebtorSignatureOnly(Long contractId, String signaturePath) {
        LoanContract contract = contractRepository.findById(contractId)
                .orElseThrow(LoanContractErrorCode.CONTRACT_NOT_FOUND::toException);

        if (!contract.signDebtorIfUnsigned(signaturePath, ContractStatus.COMPLETED)) {
            throw LoanContractErrorCode.CONTRACT_ALREADY_COMPLETED.toException();
        }
    }

    public LoanContractResponse buildCompletedSnapshot(LoanContractResponse contract, boolean isCreditor, String signaturePath) {
        LoanContractResponse updated = contract.toBuilder()
                .creditorSignature(isCreditor ? signaturePath : contract.getCreditorSignature())
                .debtorSignature(!isCreditor ? signaturePath : contract.getDebtorSignature())
                .status(ContractStatus.COMPLETED)
                .build();

        return loanContractService.attachPartyInfo(updated);
    }
}
