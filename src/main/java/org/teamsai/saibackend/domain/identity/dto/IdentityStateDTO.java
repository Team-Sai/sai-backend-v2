package org.teamsai.saibackend.domain.identity.dto;

import org.teamsai.saibackend.domain.identity.type.IdentityStatus;

import java.time.LocalDateTime;

public record IdentityStateDTO(
        String identityVerificationId,
        Long userId,
        IdentityStatus status,
        LocalDateTime verifiedAt,
        LocalDateTime expiresAt
) {
}
