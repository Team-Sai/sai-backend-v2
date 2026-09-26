package org.teamsai.saibackend.domain.identity.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.identity.dto.IdentityStateDTO;
import org.teamsai.saibackend.domain.identity.dto.request.IdentityPrepareRequest;
import org.teamsai.saibackend.domain.identity.dto.response.IdentityCompleteResponse;
import org.teamsai.saibackend.domain.identity.dto.response.IdentityPrepareResponse;
import org.teamsai.saibackend.domain.identity.dto.response.PortOneIdentityResponse;
import org.teamsai.saibackend.domain.identity.entity.Identity;
import org.teamsai.saibackend.domain.identity.exception.IdentityErrorCode;
import org.teamsai.saibackend.domain.identity.repository.IdentityRepository;
import org.teamsai.saibackend.domain.identity.support.IdentityFailureReasonFormatter;
import org.teamsai.saibackend.domain.identity.support.IdentityValidator;
import org.teamsai.saibackend.domain.identity.type.IdentityPurpose;
import org.teamsai.saibackend.domain.identity.type.IdentityStatus;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.exception.UserErrorCode;
import org.teamsai.saibackend.domain.user.repository.UserRepository;
import org.teamsai.saibackend.global.exception.DomainException;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
public class IdentityService {

    private static final String PORTONE_STATUS_VERIFIED =
            "VERIFIED";

    private static final String PORTONE_STATUS_FAILED =
            "FAILED";

    private static final String IDENTITY_VERIFICATION_ID_PREFIX =
            "identity-verification-";

    private final IdentityRepository identityRepository;
    private final UserRepository userRepository;

    private final PortOneIdentityService portOneIdentityService;
    private final IdentityValidator identityValidator;
    private final IdentityStatusService identityStatusService;
    private final IdentityFailureReasonFormatter failureReasonFormatter;

    private final String storeId;
    private final String channelKey;
    private final long validMinutes;

    public IdentityService(
            IdentityRepository identityRepository,
            UserRepository userRepository,
            PortOneIdentityService portOneIdentityService,
            IdentityValidator identityValidator,
            IdentityStatusService identityStatusService,
            IdentityFailureReasonFormatter failureReasonFormatter,

            @Value("${portone.identity.store-id}")
            String storeId,

            @Value("${portone.identity.channel-key}")
            String channelKey,

            @Value("${portone.identity.valid-minutes:10}")
            long validMinutes
    ) {
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.portOneIdentityService = portOneIdentityService;
        this.identityValidator = identityValidator;
        this.identityStatusService = identityStatusService;
        this.failureReasonFormatter = failureReasonFormatter;

        this.storeId = storeId;
        this.channelKey = channelKey;
        this.validMinutes = validMinutes;
    }

    @Transactional
    public IdentityPrepareResponse prepare(
            Long userId,
            IdentityPrepareRequest request
    ) {
        identityValidator.validateUserId(userId);
        identityValidator.validatePrepareRequest(request);

        String identityVerificationId =
                generateIdentityVerificationId();

        LocalDateTime requestedAt =
                LocalDateTime.now();

        Identity identity = Identity.builder()
                .identityVerificationId(identityVerificationId)
                .user(userRepository.getReferenceById(userId))

                .purpose(request.purpose())
                .status(IdentityStatus.REQUESTED)
                .requestedAt(requestedAt)
                .build();

        identityRepository.save(identity);

        return new IdentityPrepareResponse(
                identityVerificationId,
                storeId,
                channelKey
        );
    }

    // 외부 API 호출은 트랜잭션 밖에서 수행한다.
    // 상태 갱신은 IdentityStatusService에서 커밋한 뒤 결과 또는 예외를 반환한다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IdentityCompleteResponse complete(
            Long userId,
            String identityVerificationId
    ) {
        identityValidator.validateUserId(userId);
        identityValidator.validateIdentityVerificationId(identityVerificationId);

        Identity identity =
                findIdentity(identityVerificationId);

        identityValidator.validateOwner(identity, userId);

        if (identity.getStatus() == IdentityStatus.VERIFIED) {
            return toCompleteResponse(identity);
        }

        if (identity.getStatus() == IdentityStatus.FAILED) {
            throw IdentityErrorCode
                    .PORTONE_VERIFICATION_NOT_VERIFIED
                    .toException();
        }

        if (identity.getStatus() != IdentityStatus.REQUESTED) {
            throw IdentityErrorCode
                    .INVALID_IDENTITY_VERIFICATION_STATUS
                    .toException();
        }

        PortOneIdentityResponse portOneResponse =
                portOneIdentityService.getIdentityVerification(
                        identityVerificationId
                );

        identityValidator.validatePortOneResponse(
                identityVerificationId,
                portOneResponse
        );

        if (PORTONE_STATUS_FAILED.equals(
                portOneResponse.status()
        )) {
            processFailure(
                    identityVerificationId,
                    failureReasonFormatter.createFailureReason(portOneResponse)
            );

            throw IdentityErrorCode
                    .PORTONE_VERIFICATION_NOT_VERIFIED
                    .toException();
        }

        if (!PORTONE_STATUS_VERIFIED.equals(
                portOneResponse.status()
        )) {
            throw IdentityErrorCode
                    .IDENTITY_VERIFICATION_NOT_COMPLETED
                    .toException();
        }

        User user =
                userRepository.findById(userId)
                        .orElseThrow(
                                UserErrorCode
                                        .USER_NOT_FOUND
                                        ::toException
                        );

        try {
            identityValidator.validateSameUser(
                    user,
                    portOneResponse.verifiedCustomer()
            );

        } catch (DomainException exception) {

            if (exception.getErrorCode()
                    == IdentityErrorCode.IDENTITY_INFORMATION_MISMATCH) {

                processFailure(
                        identityVerificationId,
                        "IDENTITY_INFORMATION_MISMATCH"
                );
            }

            throw exception;
        }

        LocalDateTime verifiedAt =
                LocalDateTime.now();

        LocalDateTime expiresAt =
                verifiedAt.plusMinutes(validMinutes);

        int updatedCount =
                identityStatusService.updateVerified(
                        identityVerificationId,
                        verifiedAt,
                        expiresAt
                );

        if (updatedCount != 1) {
            return handleConcurrentCompletion(
                    userId,
                    identityVerificationId
            );
        }

        return new IdentityCompleteResponse(
                identityVerificationId,
                IdentityStatus.VERIFIED,
                verifiedAt,
                expiresAt
        );
    }

    @Transactional
    public void consume(
            Long userId,
            String identityVerificationId,
            IdentityPurpose purpose
    ) {
        identityValidator.validateUserId(userId);
        identityValidator.validateIdentityVerificationId(identityVerificationId);
        identityValidator.validatePurpose(purpose);

        try {
            int updatedCount =
                    identityRepository.consume(
                            identityVerificationId,
                            userId,
                            purpose
                    );

            if (updatedCount != 1) {
                throw IdentityErrorCode
                        .IDENTITY_VERIFICATION_CONSUME_FAILED
                        .toException();
            }
        } catch (CannotAcquireLockException exception) {
            throw IdentityErrorCode
                    .IDENTITY_VERIFICATION_CONSUME_FAILED
                    .toException();
        }
    }

    private Identity findIdentity(
            String identityVerificationId
    ) {
        Identity identity =
                identityRepository
                        .findByIdentityVerificationId(
                                identityVerificationId
                        )
                        .orElseThrow(
                                IdentityErrorCode
                                        .IDENTITY_VERIFICATION_NOT_FOUND
                                        ::toException
                        );

        return identity;
    }


    private void processFailure(
            String identityVerificationId,
            String failureReason
    ) {
        int updatedCount =
                identityStatusService.updateFailed(
                        identityVerificationId,
                        failureReasonFormatter.truncateFailureReason(failureReason)
                );

        if (updatedCount == 1) {
            return;
        }

        IdentityStateDTO latestIdentity =
                findLatestIdentityState(
                        identityVerificationId
                );

        if (latestIdentity.status()
                == IdentityStatus.FAILED) {
            return;
        }

        throw IdentityErrorCode
                .IDENTITY_VERIFICATION_UPDATE_FAILED
                .toException();
    }

    private IdentityCompleteResponse handleConcurrentCompletion(
            Long userId,
            String identityVerificationId
    ) {
        IdentityStateDTO latestIdentity =
                findLatestIdentityState(
                        identityVerificationId
                );

        if (!Objects.equals(
                latestIdentity.userId(),
                userId
        )) {
            throw IdentityErrorCode
                    .IDENTITY_VERIFICATION_FORBIDDEN
                    .toException();
        }

        if (latestIdentity.status()
                == IdentityStatus.VERIFIED) {

            return new IdentityCompleteResponse(
                    latestIdentity.identityVerificationId(),
                    latestIdentity.status(),
                    latestIdentity.verifiedAt(),
                    latestIdentity.expiresAt()
            );
        }

        throw IdentityErrorCode
                .IDENTITY_VERIFICATION_UPDATE_FAILED
                .toException();
    }

    private IdentityStateDTO findLatestIdentityState(
            String identityVerificationId
    ) {
        return identityRepository
                .findStateByIdentityVerificationId(
                        identityVerificationId
                )
                .orElseThrow(
                        IdentityErrorCode
                                .IDENTITY_VERIFICATION_NOT_FOUND
                                ::toException
                );
    }

    private IdentityCompleteResponse toCompleteResponse(
            Identity identity
    ) {
        return new IdentityCompleteResponse(
                identity.getIdentityVerificationId(),
                identity.getStatus(),
                identity.getVerifiedAt(),
                identity.getExpiresAt()
        );
    }

    private String generateIdentityVerificationId() {
        return IDENTITY_VERIFICATION_ID_PREFIX
                + UUID.randomUUID()
                .toString()
                .replace("-", "");
    }
}