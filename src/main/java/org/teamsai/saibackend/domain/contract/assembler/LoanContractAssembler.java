package org.teamsai.saibackend.domain.contract.assembler;

import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.user.dto.response.UserResponse;

public final class LoanContractAssembler {

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
}
