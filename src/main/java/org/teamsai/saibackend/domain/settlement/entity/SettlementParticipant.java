package org.teamsai.saibackend.domain.settlement.entity;

import jakarta.persistence.*;
import lombok.*;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantRole;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;
import org.teamsai.saibackend.domain.user.entity.User;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "settlement_participant")
public class SettlementParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "participant_id")
    private Long participantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id",nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_id", nullable = false)
    private Settlement settlement;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_role", nullable = false)
    private SettlementParticipantRole participantRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "participant_status", nullable = false)
    private SettlementParticipantStatus participantStatus;

    @Column(name = "joined_at", nullable = false)
    private LocalDateTime joinedAt;

}
