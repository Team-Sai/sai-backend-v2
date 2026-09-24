package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsai.saibackend.domain.contract.dto.request.RepaymentMethod;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.exception.LoanContractErrorCode;
import org.teamsai.saibackend.domain.contract.entity.LoanContractChangeRequestEntity;
import org.teamsai.saibackend.domain.contract.service.ContractChangeService;
import org.teamsai.saibackend.domain.contract.service.MonthlyPaymentEstimator;
import org.teamsai.saibackend.domain.contract.type.ChangeRequestStatus;
import org.teamsai.saibackend.domain.contract.dto.response.ChangeRequestDetailResponse;
import org.teamsai.saibackend.domain.contract.exception.ChangeRequestDetailErrorCode;
import org.teamsai.saibackend.domain.contract.service.ChangeRequestDetailQueryService;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChangeRequestDetailQueryService 단위 테스트")
class ChangeRequestDetailQueryServiceTest {

    private static final Long CONTRACT_ID = 1L;
    private static final Long CHANGE_REQUEST_ID = 5L;
    private static final Long USER_ID = 10L;
    private static final Long DEBTOR_ID = 20L;

    @Mock
    private ContractChangeService contractChangeService;

    @Spy
    private MonthlyPaymentEstimator monthlyPaymentEstimator = new MonthlyPaymentEstimator();

    @InjectMocks
    private ChangeRequestDetailQueryService changeRequestDetailService;

    private LoanContractChangeRequestEntity buildChangeRequest(
            Long changeRequestId,
            Long contractId,
            Long userId,
            ChangeRequestStatus status,
            LocalDate newMaturityDate,
            BigDecimal newInterestRate,
            String newRepaymentType,
            Integer newRepaymentDate,
            String changeReason,
            String returnReason,
            LocalDateTime createdAt
    ) {
        LoanContractChangeRequestEntity entity = new LoanContractChangeRequestEntity(
                contractId,
                userId,
                changeReason,
                newMaturityDate,
                newInterestRate,
                newRepaymentType,
                newRepaymentDate,
                null,
                status,
                createdAt,
                createdAt
        );
        ReflectionTestUtils.setField(entity, "changeRequestId", changeRequestId);
        if (returnReason != null) {
            ReflectionTestUtils.setField(entity, "returnReason", returnReason);
        }
        return entity;
    }

    private LoanContractChangeRequestEntity createChangeRequestWithDifferentContractId() {
        return buildChangeRequest(
                CHANGE_REQUEST_ID, 999L, null, ChangeRequestStatus.PENDING,
                null, null, null, null, null, null, LocalDateTime.now()
        );
    }

    @Test
    @DisplayName("변경요청이 다른 계약서에 속하면 예외가 발생한다.")
    void getDetailFailsWhenChangeRequestBelongsToOtherContract(){
        given(contractChangeService.getChangeRequest(CHANGE_REQUEST_ID))
                .willReturn(createChangeRequestWithDifferentContractId());

        assertThatThrownBy(() -> changeRequestDetailService.getDetail(CONTRACT_ID, CHANGE_REQUEST_ID, USER_ID))
                .isInstanceOfSatisfying(
                        DomainException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ChangeRequestDetailErrorCode.CHANGE_REQUEST_NOT_FOUND)

                );
    }

    @Test
    @DisplayName("계약서와 변경요청을 조합해 상세 정보(월 상환액 포함)를 반환한다")
    void getDetailSuccess() {
        given(contractChangeService.getContract(CONTRACT_ID, USER_ID))
                .willReturn(createContract());
        given(contractChangeService.getChangeRequest(CHANGE_REQUEST_ID))
                .willReturn(createChangeRequest());

        ChangeRequestDetailResponse result = changeRequestDetailService.getDetail(CONTRACT_ID, CHANGE_REQUEST_ID, USER_ID);

        assertThat(result.getRequesterName()).isEqualTo("김민수");
        assertThat(result.getStatus()).isEqualTo("승인 대기 중");
        assertThat(result.getExtendedMonths()).isEqualTo(12);
        assertThat(result.getCurrentMonthlyPayment()).isEqualByComparingTo(BigDecimal.valueOf(375000));
        assertThat(result.getNewMonthlyPayment()).isEqualByComparingTo(BigDecimal.valueOf(4532773.92));
    }

    private LoanContractResponse createContract() {
        return LoanContractResponse.builder()
                .contractId(CONTRACT_ID)
                .creditorName("김민수")
                .debtorId(DEBTOR_ID)
                .debtorName("이영희")
                .principalAmount(BigDecimal.valueOf(100_000_000))
                .interestRate(BigDecimal.valueOf(4.5))
                .repaymentType(RepaymentMethod.BULLET_REPAYMENT)
                .startDate(LocalDate.of(2025, 1, 1))
                .maturityDate(LocalDate.of(2025, 12, 31))
                .creditorId(USER_ID)
                .build();
    }

    private LoanContractChangeRequestEntity createChangeRequest() {
        return buildChangeRequest(
                CHANGE_REQUEST_ID, CONTRACT_ID, USER_ID, ChangeRequestStatus.PENDING,
                LocalDate.of(2026, 12, 31), BigDecimal.valueOf(4.2), "EQUAL_PRINCIPAL_AND_INTEREST", 15,
                "자금 사정으로 인한 연장 요청", null, LocalDateTime.now()
        );
    }

    @Test
    @DisplayName("newMaturityDate가 없으면 현재 계약의 만기일로 채워진다")
    void getDetail_fallsBackToCurrentMaturityDate_whenNewMaturityDateIsNull() {
        LoanContractResponse contract = createContract();

        LoanContractChangeRequestEntity changeRequest = buildChangeRequest(
                CHANGE_REQUEST_ID, CONTRACT_ID, USER_ID, ChangeRequestStatus.PENDING,
                null, BigDecimal.valueOf(4.2), "EQUAL_PRINCIPAL_AND_INTEREST", 15,
                "자금 사정으로 인한 연장 요청", null, LocalDateTime.now()
        );

        given(contractChangeService.getContract(CONTRACT_ID, USER_ID)).willReturn(contract);
        given(contractChangeService.getChangeRequest(CHANGE_REQUEST_ID)).willReturn(changeRequest);

        ChangeRequestDetailResponse result = changeRequestDetailService.getDetail(CONTRACT_ID, CHANGE_REQUEST_ID, USER_ID);

        assertThat(result.getNewMaturityDate()).isEqualTo(contract.getMaturityDate());
    }

    @Test
    @DisplayName("계약 당사자가 아니면 예외가 발생한다")
    void getDetailFailsWhenNotContractParty() {
        given(contractChangeService.getContract(CONTRACT_ID, USER_ID))
                .willThrow(LoanContractErrorCode.CONTRACT_ACCESS_DENIED.toException());

        assertThatThrownBy(() -> changeRequestDetailService.getDetail(CONTRACT_ID, CHANGE_REQUEST_ID, USER_ID))
                .isInstanceOf(DomainException.class);

    }

    @Test
    @DisplayName("채무자가 요청자면 requesterName이 채무자 이름으로 결정된다")
    void getDetail_requesterIsDebtor() {
        LoanContractResponse contract = createContract();
        LoanContractChangeRequestEntity changeRequest = buildChangeRequest(
                CHANGE_REQUEST_ID, CONTRACT_ID, contract.getDebtorId(), ChangeRequestStatus.PENDING,
                null, null, null, null, null, null, LocalDateTime.now()
        );

        when(contractChangeService.getContract(CONTRACT_ID, USER_ID)).thenReturn(contract);
        when(contractChangeService.getChangeRequest(CHANGE_REQUEST_ID)).thenReturn(changeRequest);

        ChangeRequestDetailResponse result = changeRequestDetailService.getDetail(CONTRACT_ID, CHANGE_REQUEST_ID, USER_ID);

        assertThat(result.getRequesterName()).isEqualTo(contract.getDebtorName());
    }

    @Test
    @DisplayName("반려 사유(returnReason)가 응답에 그대로 채워진다")
    void getDetail_includesReturnReason() {
        LoanContractResponse contract = createContract();
        LoanContractChangeRequestEntity changeRequest = buildChangeRequest(
                CHANGE_REQUEST_ID, CONTRACT_ID, contract.getCreditorId(), ChangeRequestStatus.REJECTED,
                null, null, null, null, null, "이율이 너무 높습니다", LocalDateTime.now()
        );

        when(contractChangeService.getContract(CONTRACT_ID, USER_ID)).thenReturn(contract);
        when(contractChangeService.getChangeRequest(CHANGE_REQUEST_ID)).thenReturn(changeRequest);

        ChangeRequestDetailResponse result = changeRequestDetailService.getDetail(CONTRACT_ID, CHANGE_REQUEST_ID, USER_ID);

        assertThat(result.getReturnReason()).isEqualTo("이율이 너무 높습니다");
    }

}
