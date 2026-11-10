package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.contract.entity.LoanContractChangeRequest;
import org.teamsai.saibackend.domain.contract.exception.ContractChangeErrorCode;
import org.teamsai.saibackend.domain.contract.type.ChangeRequestStatus;
import org.teamsai.saibackend.global.exception.DomainException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LoanContractChangeRequest 검증 메서드 단위 테스트")
class LoanContractChangeRequestTest {

    private static final Long CONTRACT_ID = 1L;
    private static final Long USER_ID = 10L;

    private LoanContractChangeRequest request(ChangeRequestStatus status) {
        return new LoanContractChangeRequest(
                CONTRACT_ID, USER_ID, "사유",
                null, null, null, null, null,
                status, LocalDateTime.now(), LocalDateTime.now()
        );
    }

    private void assertErrorCode(Runnable action, ContractChangeErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    @Test
    @DisplayName("같은 계약이면 통과, 다른 계약이면 CHANGE_REQUEST_NOT_FOUND")
    void validateBelongsTo() {
        LoanContractChangeRequest request = request(ChangeRequestStatus.PENDING);

        assertThatCode(() -> request.validateBelongsTo(CONTRACT_ID)).doesNotThrowAnyException();
        assertErrorCode(() -> request.validateBelongsTo(999L), ContractChangeErrorCode.CHANGE_REQUEST_NOT_FOUND);
    }

    @Test
    @DisplayName("PENDING이면 통과, 이미 처리됐으면 ALREADY_BEING_REQUEST")
    void validatePending() {
        assertThatCode(() -> request(ChangeRequestStatus.PENDING).validatePending()).doesNotThrowAnyException();
        assertErrorCode(() -> request(ChangeRequestStatus.APPROVED).validatePending(), ContractChangeErrorCode.ALREADY_BEING_REQUEST);
    }

    @Test
    @DisplayName("요청자 서명이 있으면 통과, 없으면 REQUESTER_SIGNATURE_REQUIRED")
    void validateSigned() {
        LoanContractChangeRequest request = request(ChangeRequestStatus.PENDING);
        assertErrorCode(request::validateSigned, ContractChangeErrorCode.REQUESTER_SIGNATURE_REQUIRED);

        request.attachRequesterSignature("signature.png");
        assertThatCode(request::validateSigned).doesNotThrowAnyException();
    }
}
