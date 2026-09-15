package org.teamsai.saibackend.domain.identity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.teamsai.saibackend.domain.identity.entity.Identity;
import org.teamsai.saibackend.domain.identity.type.IdentityPurpose;
import org.teamsai.saibackend.domain.identity.type.IdentityStatus;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdentityDTO {

    private Long identityId;
    private String identityVerificationId;
    private Long userId;

    private IdentityPurpose purpose;
    private IdentityStatus status;

    private LocalDateTime requestedAt;
    private LocalDateTime verifiedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime usedAt;

    private String failureReason;

    public static IdentityDTO from(
            Identity identity
    ) {
        return IdentityDTO.builder()
                .identityId(
                        identity.getIdentityId()
                )
                .identityVerificationId(
                        identity.getIdentityVerificationId()
                )
                .userId(
                        identity.getUserId()
                )
                .purpose(
                        identity.getPurpose()
                )
                .status(
                        identity.getStatus()
                )
                .requestedAt(
                        identity.getRequestedAt()
                )
                .verifiedAt(
                        identity.getVerifiedAt()
                )
                .expiresAt(
                        identity.getExpiresAt()
                )
                .usedAt(
                        identity.getUsedAt()
                )
                .failureReason(
                        identity.getFailureReason()
                )
                .build();
    }
}
