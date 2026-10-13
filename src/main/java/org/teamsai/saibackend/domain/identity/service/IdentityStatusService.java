package org.teamsai.saibackend.domain.identity.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.identity.repository.IdentityRepository;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class IdentityStatusService {

    private final IdentityRepository identityRepository;

    @Transactional
    public int updateVerified(
            String identityVerificationId,
            LocalDateTime verifiedAt,
            LocalDateTime expiresAt
    ) {
        return identityRepository.updateVerified(
                identityVerificationId,
                verifiedAt,
                expiresAt
        );
    }

    @Transactional
    public int updateFailed(
            String identityVerificationId,
            String failureReason
    ) {
        return identityRepository.updateFailed(
                identityVerificationId,
                failureReason
        );
    }
}
