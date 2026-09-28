package org.teamsai.saibackend.domain.settlement.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementParticipantCreateRequest {
    private String userToken;
}