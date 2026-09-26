package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.entity.LoanContractChangeRequestEntity;
import org.teamsai.saibackend.domain.contract.exception.ContractChangeErrorCode;
import org.teamsai.saibackend.domain.contract.exception.LoanContractErrorCode;
import org.teamsai.saibackend.domain.contract.repository.ContractChangeRepository;
import org.teamsai.saibackend.domain.contract.service.ContractChangeQueryService;
import org.teamsai.saibackend.domain.contract.service.LoanChangeService;
import org.teamsai.saibackend.domain.contract.service.LoanContractService;
import org.teamsai.saibackend.domain.contract.type.ChangeRequestStatus;
import org.teamsai.saibackend.global.exception.DomainException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractChangeQueryService 단위 테스트")
class ContractChangeQueryServiceTest {

    private static final Long CONTRACT_ID = 1L;
    private static final Long V2_CONTRACT_ID = 2L;
    private static final Long CHANGE_REQUEST_ID = 5L;
    private static final Long USER_ID = 10L;
    private static final Long DEBTOR_ID = 20L;

    @Mock
    private LoanContractService loanContractService;

    @Mock
    private LoanChangeService loanChangeService;

    @Mock
    private ContractChangeRepository contractChangeRepository;

    @InjectMocks
    private ContractChangeQueryService contractChangeQueryService;

    private LoanContractResponse createContract(ContractStatus status) {
        return LoanContractResponse.builder()
                .contractId(CONTRACT_ID)
                .status(status)
                .creditorId(USER_ID)
                .debtorId(DEBTOR_ID)
                .build();
    }

    private LoanContractChangeRequestEntity changeRequest(ChangeRequestStatus status) {
        LoanContractChangeRequestEntity entity = new LoanContractChangeRequestEntity(
                CONTRACT_ID, USER_ID, "이자율 조정 요청",
                null, null, null, null, null,
                status, LocalDateTime.now(), LocalDateTime.now()
        );
        ReflectionTestUtils.setField(entity, "changeRequestId", CHANGE_REQUEST_ID);
        return entity;
    }

    @Nested
    @DisplayName("계약 조회")
    class GetContract {

        @Test
        @DisplayName("완료된 계약이면 정상적으로 반환한다")
        void getContractSuccess() {
            given(loanContractService.findContract(CONTRACT_ID, USER_ID))
                    .willReturn(createContract(ContractStatus.COMPLETED));

            LoanContractResponse result = contractChangeQueryService.getContract(CONTRACT_ID, USER_ID);

            assertThat(result.getContractId()).isEqualTo(CONTRACT_ID);
        }

        @Test
        @DisplayName("완료되지 않은 계약이면 예외가 발생한다")
        void getContractFailsWhenNotCompleted() {
            given(loanContractService.findContract(CONTRACT_ID, USER_ID))
                    .willReturn(createContract(ContractStatus.PENDING));

            assertThatThrownBy(() -> contractChangeQueryService.getContract(CONTRACT_ID, USER_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ContractChangeErrorCode.CONTRACT_NOT_COMPLETED)
                    );
        }

        @Test
        @DisplayName("계약서 도메인에서 던진 예외를 그대로 전달한다")
        void getContractPropagatesExceptionFromLoanContractService() {
            given(loanContractService.findContract(CONTRACT_ID, USER_ID))
                    .willThrow(LoanContractErrorCode.CONTRACT_ACCESS_DENIED.toException());

            assertThatThrownBy(() -> contractChangeQueryService.getContract(CONTRACT_ID, USER_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_ACCESS_DENIED)
                    );
        }
    }

    @Nested
    @DisplayName("변경 요청 단건 조회")
    class GetChangeRequest {

        @Test
        @DisplayName("변경 요청이 있으면 반환한다")
        void getChangeRequestSuccess() {
            given(contractChangeRepository.findById(CHANGE_REQUEST_ID))
                    .willReturn(Optional.of(changeRequest(ChangeRequestStatus.PENDING)));

            LoanContractChangeRequestEntity result = contractChangeQueryService.getChangeRequest(CHANGE_REQUEST_ID);

            assertThat(result.getChangeRequestId()).isEqualTo(CHANGE_REQUEST_ID);
        }

        @Test
        @DisplayName("변경 요청이 없으면 예외가 발생한다")
        void getChangeRequestFailsWhenNotFound() {
            given(contractChangeRepository.findById(CHANGE_REQUEST_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> contractChangeQueryService.getChangeRequest(CHANGE_REQUEST_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ContractChangeErrorCode.CHANGE_REQUEST_NOT_FOUND)
                    );
        }
    }

    @Nested
    @DisplayName("대기 중인 변경 계약(v2) 번호 조회")
    class GetPendingChangedContractId {

        @Test
        @DisplayName("대기 중인 v2 계약이 있으면 그 번호를 반환한다")
        void returnsV2ContractId() {
            given(loanChangeService.findPendingContractByPreviousId(CONTRACT_ID))
                    .willReturn(Optional.of(LoanContractResponse.builder().contractId(V2_CONTRACT_ID).build()));

            Long result = contractChangeQueryService.getPendingChangedContractId(CONTRACT_ID);

            assertThat(result).isEqualTo(V2_CONTRACT_ID);
        }

        @Test
        @DisplayName("대기 중인 v2 계약이 없으면 예외가 발생한다")
        void failsWhenNoPendingContract() {
            given(loanChangeService.findPendingContractByPreviousId(CONTRACT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> contractChangeQueryService.getPendingChangedContractId(CONTRACT_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ContractChangeErrorCode.CHANGE_REQUEST_NOT_FOUND)
                    );
        }
    }

    @Nested
    @DisplayName("대기 중인 변경 요청 존재 여부")
    class HasPendingChangeRequest {

        @Test
        @DisplayName("PENDING 요청이 하나라도 있으면 true")
        void trueWhenPendingExists() {
            given(contractChangeRepository.findByContractId(CONTRACT_ID))
                    .willReturn(List.of(changeRequest(ChangeRequestStatus.REJECTED), changeRequest(ChangeRequestStatus.PENDING)));

            assertThat(contractChangeQueryService.hasPendingChangeRequest(CONTRACT_ID)).isTrue();
        }

        @Test
        @DisplayName("PENDING 요청이 없으면 false")
        void falseWhenNoPending() {
            given(contractChangeRepository.findByContractId(CONTRACT_ID))
                    .willReturn(List.of(changeRequest(ChangeRequestStatus.REJECTED)));

            assertThat(contractChangeQueryService.hasPendingChangeRequest(CONTRACT_ID)).isFalse();
        }
    }
}