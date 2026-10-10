package org.teamsai.saibackend.domain.contract.util;

import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.type.ContractStatus;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class VisibleContractSelector {

    private VisibleContractSelector() {
    }

    public static List<LoanContractResponse> select(
            List<LoanContractResponse> contracts
    ) {
        Set<Long> supersededIds = contracts.stream()
                .filter(c ->
                        c.getPreviousContractId() != null
                                && c.getStatus() == ContractStatus.COMPLETED)
                .map(LoanContractResponse::getPreviousContractId)
                .collect(Collectors.toSet());

        return contracts.stream()
                .filter(c -> c.getStatus() == ContractStatus.COMPLETED)
                .filter(c -> !supersededIds.contains(c.getContractId()))
                .toList();
    }
}