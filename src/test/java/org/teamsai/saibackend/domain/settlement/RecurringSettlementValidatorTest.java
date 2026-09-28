package org.teamsai.saibackend.domain.settlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.settlement.dto.request.RecurringSettlementCreateRequest;
import org.teamsai.saibackend.domain.settlement.dto.request.SettlementParticipantCreateRequest;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.support.RecurringSettlementValidator;
import org.teamsai.saibackend.domain.settlement.support.SettlementParticipantValidator;
import org.teamsai.saibackend.domain.settlement.type.SplitType;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;

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
                        .splitType(SplitType.EQUAL)
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

    @Test
    @DisplayName("CUSTOM 정기정산 금액 합이 총액과 일치하면 검증을 통과한다")
    void validatesCustomAmounts() {

        RecurringSettlementCreateRequest request =
                RecurringSettlementCreateRequest.builder()
                        .splitType(SplitType.CUSTOM)
                        .totalAmount(new BigDecimal("100000"))
                        .ownerAmount(new BigDecimal("20000"))
                        .participants(
                                List.of(
                                        participant(
                                                "SAI_USER_A",
                                                new BigDecimal("30000")
                                        ),
                                        participant(
                                                "SAI_USER_B",
                                                new BigDecimal("50000")
                                        )
                                )
                        )
                        .build();

        assertThatCode(
                () -> validator.validateCreateRequest(request)
        ).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CUSTOM 정기정산 금액 합이 총액과 다르면 예외가 발생한다")
    void rejectsCustomAmountMismatch() {

        RecurringSettlementCreateRequest request =
                RecurringSettlementCreateRequest.builder()
                        .splitType(SplitType.CUSTOM)
                        .totalAmount(new BigDecimal("100000"))
                        .ownerAmount(new BigDecimal("20000"))
                        .participants(
                                List.of(
                                        participant(
                                                "SAI_USER_A",
                                                new BigDecimal("30000")
                                        ),
                                        participant(
                                                "SAI_USER_B",
                                                new BigDecimal("40000")
                                        )
                                )
                        )
                        .build();

        assertThatThrownBy(
                () -> validator.validateCreateRequest(request)
        )
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(
                        SettlementErrorCode.INVALID_SETTLEMENT_AMOUNT
                );
    }
    private SettlementParticipantCreateRequest participant(
            String userToken,
            BigDecimal amount
    ) {

        return SettlementParticipantCreateRequest.builder()
                .userToken(userToken)
                .amount(amount)
                .build();
    }
}