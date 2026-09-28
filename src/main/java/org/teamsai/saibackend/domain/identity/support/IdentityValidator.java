package org.teamsai.saibackend.domain.identity.support;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.teamsai.saibackend.domain.identity.dto.request.IdentityPrepareRequest;
import org.teamsai.saibackend.domain.identity.dto.response.PortOneIdentityResponse;
import org.teamsai.saibackend.domain.identity.entity.Identity;
import org.teamsai.saibackend.domain.identity.exception.IdentityErrorCode;
import org.teamsai.saibackend.domain.identity.type.IdentityPurpose;
import org.teamsai.saibackend.domain.user.entity.User;

import java.text.Normalizer;
import java.util.Objects;
import java.util.Set;

/**
 * 본인인증 서비스의 입력 및 업무 규칙을 검증한다.
 *
 * 요청값과 외부 응답의 null은 각 검증 메서드에서 도메인 예외로 처리한다.
 * DB 조회 대상의 존재 여부는 서비스에서 확인한다.
 * 소유권 검증에는 조회가 완료된 Identity와 검증된 사용자 ID를 전달한다.
 *
 * 저장, 상태 변경, 외부 API 호출은 수행하지 않는다.
 */
@Component
public class IdentityValidator {

    private static final Set<String> PORTONE_STATUSES =
            Set.of(
                    "READY",
                    "VERIFIED",
                    "FAILED"
            );

    public void validateUserId(
            Long userId
    ) {
        if (userId == null) {
            throw IdentityErrorCode
                    .UNAUTHENTICATED_USER
                    .toException();
        }
    }

    public void validatePrepareRequest(IdentityPrepareRequest request) {
        validatePurpose(request == null ? null : request.purpose());
    }

    public void validateIdentityVerificationId(
            String identityVerificationId
    ) {
        if (!StringUtils.hasText(
                identityVerificationId
        )) {
            throw IdentityErrorCode
                    .INVALID_IDENTITY_VERIFICATION_ID
                    .toException();
        }
    }

    /**
     * 전제조건:
     * - identity는 서비스에서 조회에 성공한 엔티티다.
     * - identity의 소유자와 소유자 ID는 존재한다.
     * - userId는 validateUserId()를 통과한 값이다.
     */
    public void validateOwner(
            Identity identity,
            Long userId
    ) {
        if (!Objects.equals(
                identity.getUser().getUserId(),
                userId
        )) {
            throw IdentityErrorCode
                    .IDENTITY_VERIFICATION_FORBIDDEN
                    .toException();
        }
    }

    public void validatePortOneResponse(
            String expectedIdentityVerificationId,
            PortOneIdentityResponse response
    ) {
        if (response == null
                || !StringUtils.hasText(response.id())
                || !StringUtils.hasText(response.status())) {

            throw IdentityErrorCode
                    .PORTONE_API_INVALID_RESPONSE
                    .toException();
        }

        if (!Objects.equals(
                expectedIdentityVerificationId,
                response.id()
        )) {
            throw IdentityErrorCode
                    .PORTONE_API_INVALID_RESPONSE
                    .toException();
        }

        if (!PORTONE_STATUSES.contains(response.status())) {
            throw IdentityErrorCode
                    .PORTONE_API_INVALID_RESPONSE
                    .toException();
        }
    }

    public void validateSameUser(
            User user,
            PortOneIdentityResponse.VerifiedCustomer verifiedCustomer
    ) {
        validateUserInformation(user);
        validateVerifiedCustomer(verifiedCustomer);

        boolean sameName =
                Objects.equals(
                        normalizeName(user.getName()),
                        normalizeName(verifiedCustomer.name())
                );

        boolean sameBirthDate =
                Objects.equals(
                        user.getBirthDate(),
                        verifiedCustomer.birthDate()
                );

        if (!sameName || !sameBirthDate) {
            throw IdentityErrorCode
                    .IDENTITY_INFORMATION_MISMATCH
                    .toException();
        }
    }

    public void validateUserInformation(
            User user
    ) {
        if (user == null
                || !StringUtils.hasText(user.getName())
                || user.getBirthDate() == null) {

            throw IdentityErrorCode
                    .IDENTITY_USER_INFORMATION_MISSING
                    .toException();
        }
    }

    private void validateVerifiedCustomer(
            PortOneIdentityResponse.VerifiedCustomer verifiedCustomer
    ) {
        if (verifiedCustomer == null
                || !StringUtils.hasText(verifiedCustomer.name())
                || verifiedCustomer.birthDate() == null) {

            throw IdentityErrorCode
                    .PORTONE_API_INVALID_RESPONSE
                    .toException();
        }
    }

    private String normalizeName(
            String name
    ) {
        return Normalizer.normalize(
                name.strip(),
                Normalizer.Form.NFC
        );
    }

    public void validatePurpose(IdentityPurpose purpose) {
        if (purpose == null) {
            throw IdentityErrorCode.INVALID_IDENTITY_PURPOSE.toException();
        }
    }
}