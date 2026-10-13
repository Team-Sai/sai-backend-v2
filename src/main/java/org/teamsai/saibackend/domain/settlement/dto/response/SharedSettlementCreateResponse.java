package org.teamsai.saibackend.domain.settlement.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.type.SettlementStatus;
import org.teamsai.saibackend.domain.settlement.type.SettlementType;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SharedSettlementCreateResponse {
    private Long settlementId;
    private SettlementType settlementType;
    private SettlementStatus settlementStatus;
    private String title;
    private LocalDateTime createdAt;

    public static SharedSettlementCreateResponse from(Settlement settlement) {
        return SharedSettlementCreateResponse.builder()
                .settlementId(settlement.getSettlementId())
                .settlementType(settlement.getSettlementType())
                .settlementStatus(settlement.getSettlementStatus())
                .title(settlement.getTitle())
                .createdAt(settlement.getCreatedAt())
                .build();
    }
}
