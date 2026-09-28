package org.teamsai.saibackend.domain.settlement.dto.request;

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

    private BigDecimal amount;
}