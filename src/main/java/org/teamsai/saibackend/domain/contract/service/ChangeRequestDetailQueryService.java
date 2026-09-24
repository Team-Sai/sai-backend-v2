package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.contract.assembler.ChangeRequestDetailAssembler;
import org.teamsai.saibackend.domain.contract.dto.response.ChangeRequestDetailResponse;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.entity.LoanContractChangeRequestEntity;
import org.teamsai.saibackend.domain.contract.type.ChangeRequestStatus;
import org.teamsai.saibackend.domain.contract.exception.ChangeRequestDetailErrorCode;
import org.teamsai.saibackend.domain.contract.util.RepaymentCalculator;

import java.math.BigDecimal;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ChangeRequestDetailQueryService {

    private final ContractChangeService contractChangeService;

    public ChangeRequestDetailResponse getDetail(Long contractId, Long changeRequestId, Long userId) {

        LoanContractResponse contract = contractChangeService.getContract(contractId, userId);
        LoanContractChangeRequestEntity changeRequest = contractChangeService.getChangeRequest(changeRequestId);

        if (!changeRequest.getContractId().equals(contractId)) {
            throw ChangeRequestDetailErrorCode.CHANGE_REQUEST_NOT_FOUND.toException();
        }

        boolean requesterIsParty = Objects.equals(changeRequest.getUserId(), contract.getCreditorId())
                || Objects.equals(changeRequest.getUserId(), contract.getDebtorId());
        if (!requesterIsParty) {
            throw ChangeRequestDetailErrorCode.CHANGE_REQUEST_NOT_FOUND.toException();
        }

        BigDecimal currentMonthlyPayment = RepaymentCalculator.calculate(
                contract.getPrincipalAmount(),
                contract.getInterestRate(),
                contract.getRepaymentType().name(),
                contract.getStartDate(),
                contract.getMaturityDate()
        );

        BigDecimal newMonthlyPayment = RepaymentCalculator.calculate(
                contract.getPrincipalAmount(),
                ChangeRequestDetailAssembler.effectiveInterestRate(contract, changeRequest),
                ChangeRequestDetailAssembler.effectiveRepaymentType(contract, changeRequest),
                contract.getStartDate(),
                ChangeRequestDetailAssembler.effectiveMaturityDate(contract, changeRequest)
        );

        Long newContractId = changeRequest.getStatus() == ChangeRequestStatus.PENDING
                ? contractChangeService.getPendingChangedContractId(contractId)
                : null;

        return ChangeRequestDetailAssembler.toDetail(
                contract, changeRequest, newContractId, currentMonthlyPayment, newMonthlyPayment);
    }
}
