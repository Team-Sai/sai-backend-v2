package org.teamsai.saibackend.domain.settlement.dto.request;

import jakarta.validation.constraints.Digits;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementParticipantCreateRequest {
    private String userToken;
    @Digits(
            integer = 13,
            fraction = 0,
            message = "정산 금액은 원 단위로 입력해 주세요."
    )
    private BigDecimal amount;
}