package org.teamsai.saibackend.domain.contract.assembler;

import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.user.dto.response.UserResponse;

import java.util.Objects;

public final class LoanContractAssembler {

    private static final String CONTRACT_DISPLAY_ID_PREFIX = "LC-";

    private LoanContractAssembler() {
    }

    public static LoanContractResponse withPartyInfo(LoanContractResponse contract, UserResponse creditor, UserResponse debtor) {
        LoanContractResponse.LoanContractResponseBuilder enriched = contract.toBuilder()
                .creditorName(creditor.getName())
                .creditorBirthDate(creditor.getBirthDate() != null ? creditor.getBirthDate().toString() : null);

        if (debtor != null) {
            enriched.debtorName(debtor.getName())
                    .debtorBirthDate(debtor.getBirthDate() != null ? debtor.getBirthDate().toString() : null);
        }

        return enriched.build();
    }

    public static LoanContractResponse withDisplayId(LoanContractResponse contract, Long rootContractId) {
        String contractDisplayId = Objects.equals(rootContractId, contract.getContractId())
                ? CONTRACT_DISPLAY_ID_PREFIX + contract.getContractId()
                : CONTRACT_DISPLAY_ID_PREFIX + rootContractId + "-" + contract.getContractId();

        return contract.toBuilder()
                .contractDisplayId(contractDisplayId)
                .build();
    }
}
