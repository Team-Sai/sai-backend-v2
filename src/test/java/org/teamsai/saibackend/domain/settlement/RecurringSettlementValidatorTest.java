package org.teamsai.saibackend.domain.settlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.settlement.dto.request.RecurringSettlementCreateRequest;
import org.teamsai.saibackend.domain.settlement.dto.request.SettlementParticipantCreateRequest;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.support.RecurringSettlementValidator;
import org.teamsai.saibackend.domain.settlement.support.SettlementParticipantValidator;
import org.teamsai.saibackend.global.exception.DomainException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RecurringSettlementValidator 단위 테스트")
class RecurringSettlementValidatorTest {

    private final RecurringSettlementValidator validator =
            new RecurringSettlementValidator(
                    new SettlementParticipantValidator()
            );

    @Test
    @DisplayName("동일한 참여자를 중복 선택하면 예외가 발생한다")
    void rejectsDuplicateParticipants() {
        RecurringSettlementCreateRequest request =
                RecurringSettlementCreateRequest.builder()
                        .participants(List.of(
                                participant("SAI_USER_A"),
                                participant("SAI_USER_A")
                        ))
                        .build();

        assertThatThrownBy(() -> validator.validateCreateRequest(request))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(SettlementErrorCode.DUPLICATE_SETTLEMENT_PARTICIPANT);
    }

    private SettlementParticipantCreateRequest participant(String userToken) {
        return SettlementParticipantCreateRequest.builder()
                .userToken(userToken)
                .build();
    }
}