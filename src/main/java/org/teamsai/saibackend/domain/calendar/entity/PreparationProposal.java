package org.teamsai.saibackend.domain.calendar.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(
        name = "preparation_proposal",
        indexes = {
                @Index(
                        name = "idx_proposal_user_created",
                        columnList = "user_id,created_at"
                ),
                @Index(
                        name = "idx_proposal_cleanup",
                        columnList =
                                "confirmed_at,expires_at,proposal_id"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PreparationProposal {

    @Id
    @Column(name = "proposal_id", length = 36)
    private String proposalId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(
            name = "payload_json",
            nullable = false,
            columnDefinition = "LONGTEXT"
    )
    private String payloadJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(
            name = "result_json",
            columnDefinition = "LONGTEXT"
    )
    private String resultJson;

    public PreparationProposal(
            String proposalId,
            Long userId,
            String payloadJson,
            Instant createdAt,
            Instant expiresAt
    ) {
        this.proposalId = proposalId;
        this.userId = userId;
        this.payloadJson = payloadJson;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isConfirmed() {
        return confirmedAt != null;
    }

    public void confirm(Instant confirmedAt, String resultJson) {
        this.confirmedAt = confirmedAt;
        this.resultJson = resultJson;
    }
}