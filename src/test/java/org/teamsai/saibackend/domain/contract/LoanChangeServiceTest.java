package org.teamsai.saibackend.domain.contract;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.dto.request.RepaymentMethod;
import org.teamsai.saibackend.domain.contract.dto.response.ChangeLoanContractResponse;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.entity.LoanContract;
import org.teamsai.saibackend.domain.contract.exception.LoanContractErrorCode;
import org.teamsai.saibackend.domain.contract.repository.LoanContractRepository;
import org.teamsai.saibackend.domain.contract.service.LoanChangeService;
import org.teamsai.saibackend.domain.contract.service.LoanContractService;
import org.teamsai.saibackend.domain.contract.type.ContractRelationType;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("LoanChangeService 단위 테스트")
class LoanChangeServiceTest {

    private static final Long CONTRACT_ID = 1L;
    private static final Long PREVIOUS_CONTRACT_ID = 1L;
    private static final Long CREDITOR_ID = 10L;
    private static final Long DEBTOR_ID = 20L;

    @Mock
    private LoanContractRepository contractRepository;

    @Mock
    private LoanContractService loanContractService;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private LoanChangeService loanChangeService;

    private User userRef(Long userId) {
        return User.builder().userId(userId).build();
    }

    private LoanContract contract(ContractStatus status) {
        return LoanContract.builder()
                .contractId(CONTRACT_ID)
                .creditor(userRef(CREDITOR_ID))
                .debtor(userRef(DEBTOR_ID))
                .relationType(ContractRelationType.FAMILY)
                .status(status)
                .build();
    }

    @Nested
    @DisplayName("변경 계약(v2) Entity 저장")
    class InsertChangedContract {

        private ChangeLoanContractResponse changedContract() {
            return ChangeLoanContractResponse.builder()
                    .previousContractId(PREVIOUS_CONTRACT_ID)
                    .creditorId(CREDITOR_ID)
                    .debtorId(DEBTOR_ID)
                    .relationType(ContractRelationType.FAMILY)
                    .principalAmount(BigDecimal.valueOf(1_000_000))
                    .interestRate(BigDecimal.valueOf(5.0))
                    .repaymentType(RepaymentMethod.EQUAL_PRINCIPAL_AND_INTEREST)
                    .startDate(LocalDate.of(2026, 1, 1))
                    .maturityDate(LocalDate.of(2027, 1, 1))
                    .repaymentDay(15)
                    .creditorAddress("서울시 강남구")
                    .debtorAddress("서울시 서초구")
                    .contractAlias("생활비 차용")
                    .terms("특약 없음")
                    .status(ContractStatus.PENDING)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
        }

        @Test
        @DisplayName("이전 계약/채권자/채무자 참조를 걸어서 새 계약 Entity를 저장한다")
        void insertChangedContractSuccess() {
            ChangeLoanContractResponse changedContract = changedContract();
            given(entityManager.getReference(LoanContract.class, PREVIOUS_CONTRACT_ID))
                    .willReturn(contract(ContractStatus.COMPLETED));
            given(entityManager.getReference(User.class, CREDITOR_ID)).willReturn(userRef(CREDITOR_ID));
            given(entityManager.getReference(User.class, DEBTOR_ID)).willReturn(userRef(DEBTOR_ID));

            loanChangeService.insertChangedContract(changedContract);

            ArgumentCaptor<LoanContract> captor = ArgumentCaptor.forClass(LoanContract.class);
            verify(contractRepository).save(captor.capture());

            LoanContract saved = captor.getValue();
            assertThat(saved.getPreviousContract()).isNotNull();
            assertThat(saved.getCreditor().getUserId()).isEqualTo(CREDITOR_ID);
            assertThat(saved.getDebtor().getUserId()).isEqualTo(DEBTOR_ID);
            assertThat(saved.getStatus()).isEqualTo(ContractStatus.PENDING);
            assertThat(saved.getContractAlias()).isEqualTo("생활비 차용");
        }

        @Test
        @DisplayName("이전 계약/채무자 정보가 없으면 해당 참조 없이 저장한다")
        void insertChangedContractWithoutPreviousContractAndDebtor() {
            ChangeLoanContractResponse changedContract = ChangeLoanContractResponse.builder()
                    .previousContractId(null)
                    .creditorId(CREDITOR_ID)
                    .debtorId(null)
                    .status(ContractStatus.DRAFT)
                    .build();
            given(entityManager.getReference(User.class, CREDITOR_ID)).willReturn(userRef(CREDITOR_ID));

            loanChangeService.insertChangedContract(changedContract);

            ArgumentCaptor<LoanContract> captor = ArgumentCaptor.forClass(LoanContract.class);
            verify(contractRepository).save(captor.capture());

            LoanContract saved = captor.getValue();
            assertThat(saved.getPreviousContract()).isNull();
            assertThat(saved.getDebtor()).isNull();
        }
    }

    @Nested
    @DisplayName("이전 계약 기준 대기중인 변경 계약 조회")
    class FindPendingContractByPreviousId {

        @Test
        @DisplayName("대기중인 v2 계약이 있으면 반환한다")
        void findsPendingContract() {
            LoanContract previous = contract(ContractStatus.COMPLETED);
            given(entityManager.getReference(LoanContract.class, PREVIOUS_CONTRACT_ID)).willReturn(previous);
            given(contractRepository.findByPreviousContractAndStatus(previous, ContractStatus.PENDING))
                    .willReturn(Optional.of(contract(ContractStatus.PENDING)));

            Optional<LoanContractResponse> result =
                    loanChangeService.findPendingContractByPreviousId(PREVIOUS_CONTRACT_ID);

            assertThat(result).isPresent();
            assertThat(result.get().getStatus()).isEqualTo(ContractStatus.PENDING);
        }

        @Test
        @DisplayName("대기중인 v2 계약이 없으면 빈 값을 반환한다")
        void returnsEmptyWhenNoPendingContract() {
            LoanContract previous = contract(ContractStatus.COMPLETED);
            given(entityManager.getReference(LoanContract.class, PREVIOUS_CONTRACT_ID)).willReturn(previous);
            given(contractRepository.findByPreviousContractAndStatus(previous, ContractStatus.PENDING))
                    .willReturn(Optional.empty());

            Optional<LoanContractResponse> result =
                    loanChangeService.findPendingContractByPreviousId(PREVIOUS_CONTRACT_ID);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("변경 계약 반려/대체 상태 전환")
    class StatusTransition {

        @Test
        @DisplayName("반려 처리 시 상태를 CHANGE_REJECTED로 바꾼다")
        void rejectChangedContractSuccess() {
            LoanContract contract = contract(ContractStatus.PENDING);
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract));

            loanChangeService.rejectChangedContract(CONTRACT_ID);

            assertThat(contract.getStatus()).isEqualTo(ContractStatus.CHANGE_REJECTED);
        }

        @Test
        @DisplayName("반려 대상 계약을 찾을 수 없으면 예외가 발생한다")
        void rejectChangedContractFailsWhenNotFound() {
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> loanChangeService.rejectChangedContract(CONTRACT_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_NOT_FOUND)
                    );
        }

        @Test
        @DisplayName("승인 처리 시 v1 계약 상태를 SUPERSEDED로 바꾼다")
        void supersedeContractSuccess() {
            LoanContract contract = contract(ContractStatus.COMPLETED);
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract));

            loanChangeService.supersedeContract(CONTRACT_ID);

            assertThat(contract.getStatus()).isEqualTo(ContractStatus.SUPERSEDED);
        }

        @Test
        @DisplayName("대체 대상 계약을 찾을 수 없으면 예외가 발생한다")
        void supersedeContractFailsWhenNotFound() {
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> loanChangeService.supersedeContract(CONTRACT_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_NOT_FOUND)
                    );
        }
    }

    @Nested
    @DisplayName("변경 승인 서명 처리")
    class SignatureOnly {

        @Test
        @DisplayName("채권자 서명이 없으면 저장하고 상태를 COMPLETED로 바꾼다")
        void updateCreditorSignatureOnlySuccess() {
            LoanContract contract = contract(ContractStatus.PENDING);
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract));

            loanChangeService.updateCreditorSignatureOnly(CONTRACT_ID, "signature.png");

            assertThat(contract.getCreditorSignature()).isEqualTo("signature.png");
            assertThat(contract.getStatus()).isEqualTo(ContractStatus.COMPLETED);
        }

        @Test
        @DisplayName("이미 채권자 서명이 있으면 예외가 발생하고 덮어쓰지 않는다")
        void updateCreditorSignatureOnlyFailsWhenAlreadySigned() {
            LoanContract contract = LoanContract.builder()
                    .contractId(CONTRACT_ID)
                    .creditor(userRef(CREDITOR_ID))
                    .creditorSignature("existing.png")
                    .status(ContractStatus.PENDING)
                    .build();
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract));

            assertThatThrownBy(() -> loanChangeService.updateCreditorSignatureOnly(CONTRACT_ID, "new.png"))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_ALREADY_COMPLETED)
                    );

            assertThat(contract.getCreditorSignature()).isEqualTo("existing.png");
        }

        @Test
        @DisplayName("채무자 서명이 없으면 저장하고 상태를 COMPLETED로 바꾼다")
        void updateDebtorSignatureOnlySuccess() {
            LoanContract contract = contract(ContractStatus.PENDING);
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract));

            loanChangeService.updateDebtorSignatureOnly(CONTRACT_ID, "signature.png");

            assertThat(contract.getDebtorSignature()).isEqualTo("signature.png");
            assertThat(contract.getStatus()).isEqualTo(ContractStatus.COMPLETED);
        }

        @Test
        @DisplayName("이미 채무자 서명이 있으면 예외가 발생하고 덮어쓰지 않는다")
        void updateDebtorSignatureOnlyFailsWhenAlreadySigned() {
            LoanContract contract = LoanContract.builder()
                    .contractId(CONTRACT_ID)
                    .debtor(userRef(DEBTOR_ID))
                    .debtorSignature("existing.png")
                    .status(ContractStatus.PENDING)
                    .build();
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract));

            assertThatThrownBy(() -> loanChangeService.updateDebtorSignatureOnly(CONTRACT_ID, "new.png"))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_ALREADY_COMPLETED)
                    );

            assertThat(contract.getDebtorSignature()).isEqualTo("existing.png");
        }
    }

    @Nested
    @DisplayName("변경 승인 완료 스냅샷 조립")
    class BuildCompletedSnapshot {

        private LoanContractResponse baseContract() {
            return LoanContractResponse.builder()
                    .contractId(CONTRACT_ID)
                    .creditorId(CREDITOR_ID)
                    .debtorId(DEBTOR_ID)
                    .creditorSignature(null)
                    .debtorSignature("debtor-existing.png")
                    .status(ContractStatus.PENDING)
                    .build();
        }

        @Test
        @DisplayName("채권자 승인이면 채권자 서명만 채우고 상태를 COMPLETED로 바꾼 뒤 당사자 정보를 붙인다")
        void buildCompletedSnapshotAsCreditor() {
            LoanContractResponse enriched = LoanContractResponse.builder().contractId(CONTRACT_ID).build();
            given(loanContractService.attachPartyInfo(any())).willReturn(enriched);

            LoanContractResponse result =
                    loanChangeService.buildCompletedSnapshot(baseContract(), true, "creditor-new.png");

            ArgumentCaptor<LoanContractResponse> captor = ArgumentCaptor.forClass(LoanContractResponse.class);
            verify(loanContractService).attachPartyInfo(captor.capture());

            LoanContractResponse updated = captor.getValue();
            assertThat(updated.getCreditorSignature()).isEqualTo("creditor-new.png");
            assertThat(updated.getDebtorSignature()).isEqualTo("debtor-existing.png");
            assertThat(updated.getStatus()).isEqualTo(ContractStatus.COMPLETED);
            assertThat(result).isSameAs(enriched);
        }

        @Test
        @DisplayName("채무자 승인이면 채무자 서명만 채운다")
        void buildCompletedSnapshotAsDebtor() {
            given(loanContractService.attachPartyInfo(any()))
                    .willReturn(LoanContractResponse.builder().build());

            loanChangeService.buildCompletedSnapshot(baseContract(), false, "debtor-new.png");

            ArgumentCaptor<LoanContractResponse> captor = ArgumentCaptor.forClass(LoanContractResponse.class);
            verify(loanContractService).attachPartyInfo(captor.capture());

            assertThat(captor.getValue().getDebtorSignature()).isEqualTo("debtor-new.png");
            assertThat(captor.getValue().getCreditorSignature()).isNull();
        }
    }
}
