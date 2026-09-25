package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.contract.entity.LoanContractChangeRequestEntity;
import org.teamsai.saibackend.domain.contract.exception.ContractChangeErrorCode;
import org.teamsai.saibackend.domain.contract.type.ChangeRequestStatus;
import org.teamsai.saibackend.global.exception.DomainException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LoanContractChangeRequestEntity 검증 메서드 단위 테스트")
class LoanContractChangeRequestEntityTest {

    private static final Long CONTRACT_ID = 1L;
    private static final Long USER_ID = 10L;

    private LoanContractChangeRequestEntity request(ChangeRequestStatus status) {
        return new LoanContractChangeRequestEntity(
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
        LoanContractChangeRequestEntity request = request(ChangeRequestStatus.PENDING);

        assertThatCode(() -> request.validateBelongsTo(CONTRACT_ID)).doesNotThrowAnyException();
        assertErrorCode(() -> request.validateBelongsTo(999L), ContractChangeErrorCode.CHANGE_REQUEST_NOT_FOUND);
    }

    @Test
    @DisplayName("요청자 본인이면 통과, 아니면 NOT_CONTRACT_PARTY")
    void validateRequestedBy() {
        LoanContractChangeRequestEntity request = request(ChangeRequestStatus.PENDING);

        assertThatCode(() -> request.validateRequestedBy(USER_ID)).doesNotThrowAnyException();
        assertErrorCode(() -> request.validateRequestedBy(20L), ContractChangeErrorCode.NOT_CONTRACT_PARTY);
    }

    @Test
    @DisplayName("PENDING이면 통과, 이미 처리됐으면 ALREADY_BEING_REQUEST")
    void validatePending() {
        assertThatCode(() -> request(ChangeRequestStatus.PENDING).validatePending()).doesNotThrowAnyException();
        assertErrorCode(() -> request(ChangeRequestStatus.APPROVED).validatePending(), ContractChangeErrorCode.ALREADY_BEING_REQUEST);
    }

    @Test
    @DisplayName("서명 전이면 통과, 이미 서명했으면 ALREADY_SIGNED")
    void validateNotSigned() {
        LoanContractChangeRequestEntity request = request(ChangeRequestStatus.PENDING);
        assertThatCode(request::validateNotSigned).doesNotThrowAnyException();

        request.attachRequesterSignature("signature.png");
        assertErrorCode(request::validateNotSigned, ContractChangeErrorCode.ALREADY_SIGNED);
    }
}