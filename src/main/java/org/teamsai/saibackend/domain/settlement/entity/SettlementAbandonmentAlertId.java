package org.teamsai.saibackend.domain.settlement.entity;


import lombok.*;

import java.io.Serializable;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class SettlementAbandonmentAlertId implements Serializable {

    private Long settlementId;
    private LocalDate referenceDate;
}