package org.teamsai.saibackend.domain.identity.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.teamsai.saibackend.domain.identity.type.IdentityPurpose;
import org.teamsai.saibackend.domain.identity.type.IdentityStatus;

import java.time.LocalDateTime;

@Entity
@Table(name = "identity")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Identity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "identity_id")
    private Long identityId;

    @Column(
            name = "identity_verification_id",
            nullable = false
    )
    private String identityVerificationId;

    @Column(
            name = "user_id",
            nullable = false
    )
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "purpose",
            nullable = false
    )
    private IdentityPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false
    )
    private IdentityStatus status;

    @Column(
            name = "requested_at",
            nullable = false
    )
    private LocalDateTime requestedAt;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @Builder
    public Identity(
            Long identityId,
            String identityVerificationId,
            Long userId,
            IdentityPurpose purpose,
            IdentityStatus status,
            LocalDateTime requestedAt,
            LocalDateTime verifiedAt,
            LocalDateTime expiresAt,
            LocalDateTime usedAt,
            String failureReason
    ) {
        this.identityId =
                identityId;
        this.identityVerificationId =
                identityVerificationId;
        this.userId =
                userId;
        this.purpose =
                purpose;
        this.status =
                status;
        this.requestedAt =
                requestedAt;
        this.verifiedAt =
                verifiedAt;
        this.expiresAt =
                expiresAt;
        this.usedAt =
                usedAt;
        this.failureReason =
                failureReason;
    }
}