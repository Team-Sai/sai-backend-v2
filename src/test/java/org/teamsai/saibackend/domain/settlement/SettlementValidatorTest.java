package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.settlement.dto.request.SettlementParticipantCreateRequest;
import org.teamsai.saibackend.domain.settlement.dto.request.SharedSettlementCreateRequest;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.support.SettlementParticipantValidator;
import org.teamsai.saibackend.domain.settlement.support.SettlementValidator;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;
import org.teamsai.saibackend.domain.settlement.type.SplitType;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementValidator 단위 테스트")
class SettlementValidatorTest {

    private static final Long SETTLEMENT_ID = 1L;
    private static final Long OWNER_ID = 10L;
    private static final Long MEMBER_ID = 20L;
    private static final Long OTHER_USER_ID = 30L;
    private static final Long LINKED_ACCOUNT_ID = 100L;

    @Mock
    private LinkedBankAccountService linkedBankAccountService;

    @Mock
    private SettlementParticipantRepository settlementParticipantRepository;


    @Spy
    private SettlementParticipantValidator participantValidator =
            new SettlementParticipantValidator();

    @InjectMocks
    private SettlementValidator settlementValidator;


    @Test
    @DisplayName("정산 생성자는 OWNER 검증을 통과한다")
    void validateOwnerSuccess() {

        Settlement settlement =
                settlement();

        settlementValidator.validateOwner(
                settlement,
                OWNER_ID
        );
    }


    @Test
    @DisplayName("정산 생성자가 아니면 OWNER 검증에 실패한다")
    void validateOwnerFailsWhenUserIsNotOwner() {

        Settlement settlement =
                settlement();

        assertSettlementExceptionThrownBy(
                () ->
                        settlementValidator.validateOwner(
                                settlement,
                                OTHER_USER_ID
                        ),
                SettlementErrorCode.SETTLEMENT_ACCESS_DENIED
        );
    }


    @Test
    @DisplayName("본인에게 연동된 계좌이면 검증을 통과한다")
    void validateLinkedAccountOwnerSuccess() {

        given(
                linkedBankAccountService.isOwnedLinkedAccount(
                        OWNER_ID,
                        LINKED_ACCOUNT_ID
                )
        ).willReturn(true);

        settlementValidator.validateLinkedAccountOwner(
                OWNER_ID,
                LINKED_ACCOUNT_ID
        );

        verify(linkedBankAccountService)
                .isOwnedLinkedAccount(
                        OWNER_ID,
                        LINKED_ACCOUNT_ID
                );
    }


    @Test
    @DisplayName("본인에게 연동되지 않은 계좌이면 검증에 실패한다")
    void validateLinkedAccountOwnerFailsWhenAccountDoesNotBelongToUser() {

        given(
                linkedBankAccountService.isOwnedLinkedAccount(
                        OWNER_ID,
                        LINKED_ACCOUNT_ID
                )
        ).willReturn(false);

        assertSettlementExceptionThrownBy(
                () ->
                        settlementValidator
                                .validateLinkedAccountOwner(
                                        OWNER_ID,
                                        LINKED_ACCOUNT_ID
                                ),
                SettlementErrorCode.INVALID_SETTLEMENT_ACCOUNT
        );
    }


    @Test
    @DisplayName("정산 생성자는 정산 조회 권한 검증을 통과한다")
    void validateAccessibleUserSuccessForOwner() {

        Settlement settlement =
                settlement();

        settlementValidator.validateAccessibleUser(
                settlement,
                OWNER_ID
        );

        verify(
                settlementParticipantRepository,
                never()
        ).existsActiveParticipant(
                SETTLEMENT_ID,
                OWNER_ID,
                SettlementParticipantStatus.ACTIVE
        );
    }


    @Test
    @DisplayName("ACTIVE 참여자는 정산 조회 권한 검증을 통과한다")
    void validateAccessibleUserSuccessForMember() {

        Settlement settlement =
                settlement();

        given(
                settlementParticipantRepository
                        .existsActiveParticipant(
                                SETTLEMENT_ID,
                                MEMBER_ID,
                                SettlementParticipantStatus.ACTIVE
                        )
        ).willReturn(true);

        settlementValidator.validateAccessibleUser(
                settlement,
                MEMBER_ID
        );

        verify(settlementParticipantRepository)
                .existsActiveParticipant(
                        SETTLEMENT_ID,
                        MEMBER_ID,
                        SettlementParticipantStatus.ACTIVE
                );
    }


    @Test
    @DisplayName("정산 생성자도 ACTIVE 참여자도 아니면 조회할 수 없다")
    void validateAccessibleUserFailsWhenUserHasNoAccess() {

        Settlement settlement =
                settlement();

        given(
                settlementParticipantRepository
                        .existsActiveParticipant(
                                SETTLEMENT_ID,
                                OTHER_USER_ID,
                                SettlementParticipantStatus.ACTIVE
                        )
        ).willReturn(false);

        assertSettlementExceptionThrownBy(
                () ->
                        settlementValidator
                                .validateAccessibleUser(
                                        settlement,
                                        OTHER_USER_ID
                                ),
                SettlementErrorCode.SETTLEMENT_ACCESS_DENIED
        );

        verify(settlementParticipantRepository)
                .existsActiveParticipant(
                        SETTLEMENT_ID,
                        OTHER_USER_ID,
                        SettlementParticipantStatus.ACTIVE
                );
    }


    @Test
    @DisplayName("정상적인 공동정산 생성 요청은 검증을 통과한다")
    void validateCreateRequestSuccess() {

        SharedSettlementCreateRequest request =
                request(
                        List.of(
                                participant("SAI_USER_A"),
                                participant("SAI_USER_B")
                        )
                );

        settlementValidator.validateCreateRequest(
                request
        );
    }

    @Test
    @DisplayName("CUSTOM 정산 금액 합이 총액과 일치하면 검증을 통과한다")
    void validateCustomCreateRequestSuccess() {

        SharedSettlementCreateRequest request =
                SharedSettlementCreateRequest.builder()
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

        settlementValidator.validateCreateRequest(request);
    }

    @Test
    @DisplayName("CUSTOM 정산 금액 합이 총액과 다르면 검증에 실패한다")
    void validateCustomCreateRequestFailsWhenAmountSumDoesNotMatch() {

        SharedSettlementCreateRequest request =
                SharedSettlementCreateRequest.builder()
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

        assertSettlementExceptionThrownBy(
                () ->
                        settlementValidator.validateCreateRequest(
                                request
                        ),
                SettlementErrorCode.SETTLEMENT_AMOUNT_MISMATCH
        );
    }
    @Test
    @DisplayName("CUSTOM 정산 참여자 금액이 0원이면 검증에 실패한다")
    void validateCustomCreateRequestFailsWhenParticipantAmountIsZero() {

        SharedSettlementCreateRequest request =
                SharedSettlementCreateRequest.builder()
                        .splitType(SplitType.CUSTOM)
                        .totalAmount(new BigDecimal("100000"))
                        .ownerAmount(new BigDecimal("100000"))
                        .participants(
                                List.of(
                                        participant(
                                                "SAI_USER_A",
                                                BigDecimal.ZERO
                                        )
                                )
                        )
                        .build();

        assertSettlementExceptionThrownBy(
                () ->
                        settlementValidator.validateCreateRequest(
                                request
                        ),
                SettlementErrorCode.INVALID_PARTICIPANT_AMOUNT
        );
    }

    @Test
    @DisplayName("CUSTOM 정산 생성자 금액이 음수이면 검증에 실패한다")
    void validateCustomCreateRequestFailsWhenOwnerAmountIsNegative() {

        SharedSettlementCreateRequest request =
                SharedSettlementCreateRequest.builder()
                        .splitType(SplitType.CUSTOM)
                        .totalAmount(new BigDecimal("100000"))
                        .ownerAmount(new BigDecimal("-1"))
                        .participants(
                                List.of(
                                        participant(
                                                "SAI_USER_A",
                                                new BigDecimal("100001")
                                        )
                                )
                        )
                        .build();

        assertSettlementExceptionThrownBy(
                () -> settlementValidator.validateCreateRequest(request),
                SettlementErrorCode.INVALID_OWNER_AMOUNT
        );
    }


    @Test
    @DisplayName("공동정산 생성 요청이 null이면 검증에 실패한다")
    void validateCreateRequestFailsWhenRequestIsNull() {

        assertSettlementExceptionThrownBy(
                () ->
                        settlementValidator
                                .validateCreateRequest(null),
                SettlementErrorCode.INVALID_SETTLEMENT_REQUEST
        );
    }


    @Test
    @DisplayName("참여자가 없으면 공동정산 생성 요청 검증에 실패한다")
    void validateCreateRequestFailsWhenParticipantsAreEmpty() {

        SharedSettlementCreateRequest request =
                request(List.of());

        assertSettlementExceptionThrownBy(
                () ->
                        settlementValidator
                                .validateCreateRequest(request),
                SettlementErrorCode.SETTLEMENT_PARTICIPANT_REQUIRED
        );
    }


    @Test
    @DisplayName("참여자 목록에 null이 포함되면 검증에 실패한다")
    void validateCreateRequestFailsWhenParticipantIsNull() {
        SharedSettlementCreateRequest request = request(
                Arrays.asList(
                        participant("SAI_USER_A"),
                        null
                )
        );

        assertSettlementExceptionThrownBy(
                () -> settlementValidator.validateCreateRequest(request),
                SettlementErrorCode.INVALID_SETTLEMENT_PARTICIPANT
        );
    }


    @Test
    @DisplayName("참여자 userToken이 비어 있으면 검증에 실패한다")
    void validateCreateRequestFailsWhenUserTokenIsBlank() {
        SharedSettlementCreateRequest request = request(
                List.of(participant(" "))
        );

        assertSettlementExceptionThrownBy(
                () -> settlementValidator.validateCreateRequest(request),
                SettlementErrorCode.INVALID_SETTLEMENT_PARTICIPANT
        );
    }


    @Test
    @DisplayName("동일한 userToken의 참여자가 중복되면 검증에 실패한다")
    void validateCreateRequestFailsWhenParticipantIsDuplicated() {

        SharedSettlementCreateRequest request =
                request(
                        List.of(
                                participant("SAI_USER_A"),
                                participant("SAI_USER_A")
                        )
                );

        assertThatThrownBy(() -> settlementValidator.validateCreateRequest(request))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(SettlementErrorCode.DUPLICATE_SETTLEMENT_PARTICIPANT);
    }


    private Settlement settlement() {

        return Settlement.builder()
                .settlementId(SETTLEMENT_ID)
                .owner(
                        User.builder().userId(OWNER_ID).build()
                )
                .build();
    }


    private SharedSettlementCreateRequest request(
            List<SettlementParticipantCreateRequest> participants
    ) {

        return SharedSettlementCreateRequest.builder()
                .splitType(SplitType.EQUAL)
                .participants(participants)
                .build();
    }


    private SettlementParticipantCreateRequest participant(
            String userToken
    ) {

        return SettlementParticipantCreateRequest.builder()
                .userToken(userToken)
                .build();
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


    private void assertSettlementExceptionThrownBy(
            Runnable operation,
            SettlementErrorCode errorCode
    ) {

        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        DomainException.class,
                        exception ->
                                assertThat(
                                        exception.getErrorCode()
                                ).isEqualTo(errorCode)
                );
    }
}