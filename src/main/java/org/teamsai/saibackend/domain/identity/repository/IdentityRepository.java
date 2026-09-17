package org.teamsai.saibackend.domain.identity.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.identity.dto.IdentityStateDTO;
import org.teamsai.saibackend.domain.identity.entity.Identity;
import org.teamsai.saibackend.domain.identity.type.IdentityPurpose;

import java.time.LocalDateTime;
import java.util.Optional;

public interface IdentityRepository
        extends JpaRepository<Identity, Long> {

    Optional<Identity> findByIdentityVerificationId(
            String identityVerificationId
    );

    @Modifying
    @Query("""
            update Identity i
            set i.status =
                    org.teamsai.saibackend.domain.identity.type.IdentityStatus.VERIFIED,
                i.verifiedAt = :verifiedAt,
                i.expiresAt = :expiresAt,
                i.failureReason = null
            where i.identityVerificationId = :identityVerificationId
              and i.status =
                    org.teamsai.saibackend.domain.identity.type.IdentityStatus.REQUESTED
            """)
    int updateVerified(
            @Param("identityVerificationId")
            String identityVerificationId,
            @Param("verifiedAt")
            LocalDateTime verifiedAt,
            @Param("expiresAt")
            LocalDateTime expiresAt
    );

    @Modifying
    @Query("""
            update Identity i
            set i.status =
                    org.teamsai.saibackend.domain.identity.type.IdentityStatus.FAILED,
                i.failureReason = :failureReason
            where i.identityVerificationId = :identityVerificationId
              and i.status =
                    org.teamsai.saibackend.domain.identity.type.IdentityStatus.REQUESTED
            """)
    int updateFailed(
            @Param("identityVerificationId")
            String identityVerificationId,
            @Param("failureReason")
            String failureReason
    );

    @Modifying
    @Query("""
            update Identity i
            set i.status =
                    org.teamsai.saibackend.domain.identity.type.IdentityStatus.USED,
                i.usedAt = CURRENT_TIMESTAMP
            where i.identityVerificationId = :identityVerificationId
              and i.user.userId = :userId
              and i.purpose = :purpose
              and i.status =
                    org.teamsai.saibackend.domain.identity.type.IdentityStatus.VERIFIED
              and i.expiresAt > CURRENT_TIMESTAMP
              and i.usedAt is null
            """)
    int consume(
            @Param("identityVerificationId")
            String identityVerificationId,
            @Param("userId")
            Long userId,
            @Param("purpose")
            IdentityPurpose purpose
    );

    @Query("""
        select new org.teamsai.saibackend.domain.identity.dto.IdentityStateDTO(
            i.identityVerificationId,
            i.user.userId,
            i.status,
            i.verifiedAt,
            i.expiresAt
        )
        from Identity i
        where i.identityVerificationId = :identityVerificationId
        """)
    Optional<IdentityStateDTO> findStateByIdentityVerificationId(
            @Param("identityVerificationId")
            String identityVerificationId
    );
}