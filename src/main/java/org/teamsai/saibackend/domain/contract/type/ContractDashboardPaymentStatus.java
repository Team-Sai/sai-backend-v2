package org.teamsai.saibackend.domain.contract.type;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum ContractDashboardPaymentStatus {

    ONGOING("납부중"),
    PAID("납부완료");

    private final String description;


}
