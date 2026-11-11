package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.contract.dto.request.EarlyRepaymentRequest;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.entity.ContractAccount;
import org.teamsai.saibackend.domain.contract.entity.EarlyRepayment;
import org.teamsai.saibackend.domain.contract.entity.RepaymentSchedule;
import org.teamsai.saibackend.domain.contract.repository.ContractAccountRepository;
import org.teamsai.saibackend.domain.contract.repository.DepositReturnRepository;
import org.teamsai.saibackend.domain.contract.repository.EarlyRepaymentRepository;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleRepository;
import org.teamsai.saibackend.domain.contract.service.EarlyRepaymentService;
import org.teamsai.saibackend.domain.contract.service.LoanContractService;
import org.teamsai.saibackend.domain.contract.service.RepaymentScheduleGenerateService;
import org.teamsai.saibackend.domain.contract.type.*;
import org.teamsai.saibackend.domain.payment.service.PaymentRecordService;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.transaction.entity.BankTransaction;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionService;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EarlyRepaymentServiceTest {

    @Mock private RepaymentScheduleRepository repaymentScheduleRepository;
    @Mock private EarlyRepaymentRepository earlyRepaymentRepository;
    @Mock private DepositReturnRepository depositReturnRepository;
    @Mock private LoanContractService loanContractService;
    @Spy private RepaymentScheduleGenerateService repaymentScheduleGenerateService = new RepaymentScheduleGenerateService();
    @Mock private ContractAccountRepository contractAccountRepository;
    @Mock private BankTransactionService bankTransactionService;
    @Mock private PaymentRecordService paymentRecordService;

    @InjectMocks private EarlyRepaymentService service;

    private final BankTransaction deposit = mock(BankTransaction.class);
    private List<RepaymentSchedule> rows;

    @BeforeEach
    void setUp() {
        rows = List.of(row(1, "200000"), row(2, "100000"), row(3, "0"));

        when(loanContractService.findContract(1L, 10L)).thenReturn(LoanContractResponse.builder()
                .creditorId(10L).status(ContractStatus.COMPLETED)
                .repaymentType(RepaymentMethod.EQUAL_PRINCIPAL).interestRate(BigDecimal.ZERO)
                .startDate(LocalDate.of(2026, 11, 20)).debtorName("홍길동")
                .contractDisplayId("LC-1").build());
        ContractAccount account = mock(ContractAccount.class, RETURNS_DEEP_STUBS);
        when(account.getLinkedAccount().getLinkedAccountId()).thenReturn(7L);
        when(contractAccountRepository.findLatestByContractIdAndStatus(1L, ContractAccountStatus.ACTIVE))
                .thenReturn(Optional.of(account));

        when(bankTransactionService.findByIdAndLinkedAccountIdForUpdate(99L, 7L)).thenReturn(deposit);
        when(deposit.getTransactionType()).thenReturn(BankTransactionType.DEPOSIT);
        lenient().when(deposit.getProcessingStatus()).thenReturn(BankTransactionProcessingStatus.UNMATCHED);
        lenient().when(deposit.getTransactionAt()).thenReturn(LocalDateTime.of(2026, 12, 1, 10, 0));
        lenient().when(repaymentScheduleRepository.findByContractIdForUpdate(1L)).thenReturn(rows);
        lenient().when(deposit.getCounterpartyName()).thenReturn("홍길동");
    }

    @Test
    @DisplayName("일부 중도상환은 회차 수를 유지하고 금액만 줄인다")
    void partialKeepsRows() {
        when(deposit.getAmount()).thenReturn(new BigDecimal("150000"));
        when(earlyRepaymentRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.process(1L, 10L, new EarlyRepaymentRequest(99L));

        assertThat(rows).extracting(RepaymentSchedule::getSequence).containsExactly(1, 2, 3);
        assertThat(rows).allMatch(row -> row.getTotalPaymentDue().compareTo(new BigDecimal("50000")) == 0);
        verify(deposit).changeProcessingStatus(BankTransactionProcessingStatus.APPLIED);
    }

    @Test
    @DisplayName("남은 원금보다 많은 입금은 거절한다")
    void rejectsTooMuch() {
        when(deposit.getAmount()).thenReturn(new BigDecimal("400000"));

        assertThatThrownBy(() -> service.process(1L, 10L, new EarlyRepaymentRequest(99L)))
                .isInstanceOf(DomainException.class);
        verify(deposit, never()).changeProcessingStatus(any());
    }

    @Test
    @DisplayName("차용자가 아닌 사람의 입금은 처리하지 않는다")
    void rejectsOtherDepositor() {
        when(deposit.getCounterpartyName()).thenReturn("다른사람");

        assertThatThrownBy(() -> service.process(1L, 10L, new EarlyRepaymentRequest(99L)))
                .isInstanceOf(DomainException.class);
        verify(deposit, never()).changeProcessingStatus(any());
    }

    @Test
    @DisplayName("이전 중도상환보다 앞선 입금은 거절한다")
    void rejectsDepositOlderThanLastEarlyRepayment() {
        BankTransaction later = mock(BankTransaction.class);
        EarlyRepayment last = mock(EarlyRepayment.class);
        when(last.getBankTransactionId()).thenReturn(50L);
        when(later.getTransactionAt()).thenReturn(LocalDateTime.of(2026, 12, 10, 10, 0));
        when(earlyRepaymentRepository.findFirstByContractIdAndPrincipalAmountGreaterThanOrderByProcessedAtDesc(1L, BigDecimal.ZERO))
                .thenReturn(Optional.of(last));
        when(bankTransactionService.findById(50L)).thenReturn(Optional.of(later));

        assertThatThrownBy(() -> service.process(1L, 10L, new EarlyRepaymentRequest(99L)))
                .isInstanceOf(DomainException.class);
        verify(deposit, never()).changeProcessingStatus(any());
    }

    @Test
    @DisplayName("다른 차용증 번호가 적힌 입금은 처리하지 않는다")
    void rejectsOtherContractNumber() {
        when(deposit.getMemo()).thenReturn("LC-99 상환");

        assertThatThrownBy(() -> service.process(1L, 10L, new EarlyRepaymentRequest(99L)))
                .isInstanceOf(DomainException.class);
        verify(deposit, never()).changeProcessingStatus(any());
    }

    @Test
    @DisplayName("반환 표시하면 다시 매칭되지 않게 처리 끝으로 두고 기록한다")
    void marksDepositReturned() {
        service.markReturned(1L, 10L, 99L);

        verify(deposit).changeProcessingStatus(BankTransactionProcessingStatus.APPLIED);
        verify(depositReturnRepository).save(any());
    }

    @Test
    @DisplayName("밀린 회차보다 적게 들어오면 밀린 회차에 부분 납부로 기록한다")
    void fillsArrearsPartially() {
        rows.get(0).overDue();
        when(deposit.getAmount()).thenReturn(new BigDecimal("50000"));
        when(earlyRepaymentRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.process(1L, 10L, new EarlyRepaymentRequest(99L));

        assertThat(rows.get(0).getStatus()).isEqualTo(RepaymentScheduleStatus.OVERDUE);
        verify(paymentRecordService).createConfirmedRecord(
                eq(99L), eq(PaymentTargetType.LOAN), any(), eq(new BigDecimal("50000")), any());
        verify(deposit).changeProcessingStatus(BankTransactionProcessingStatus.APPLIED);
    }

    @Test
    @DisplayName("차액 반환을 고르면 완납 처리하고 넘친 금액은 반환으로 기록한다")
    void paysOffAndRecordsExcess() {
        when(deposit.getAmount()).thenReturn(new BigDecimal("300100"));
        when(earlyRepaymentRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.process(1L, 10L, new EarlyRepaymentRequest(99L, true));

        assertThat(rows.get(0).getStatus()).isEqualTo(RepaymentScheduleStatus.PAID);
        verify(depositReturnRepository).save(any());
    }

    @Test
    @DisplayName("아직 분류 전(대기) 입금도 중도상환으로 처리할 수 있다")
    void processesPendingDeposit() {
        when(deposit.getProcessingStatus()).thenReturn(BankTransactionProcessingStatus.PENDING);
        when(deposit.getAmount()).thenReturn(new BigDecimal("150000"));
        when(earlyRepaymentRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.process(1L, 10L, new EarlyRepaymentRequest(99L));

        verify(deposit).changeProcessingStatus(BankTransactionProcessingStatus.APPLIED);
    }

    @Test
    @DisplayName("회차 미리 내기는 가까운 회차부터 채우고 금액은 다시 계산하지 않는다")
    void prepaysNearestRows() {
        when(deposit.getAmount()).thenReturn(new BigDecimal("150000"));
        when(earlyRepaymentRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.process(1L, 10L, new EarlyRepaymentRequest(99L, false, EarlyRepaymentMethod.PREPAY));

        assertThat(rows.get(0).getStatus()).isEqualTo(RepaymentScheduleStatus.PAID);
        assertThat(rows.get(1).getStatus()).isEqualTo(RepaymentScheduleStatus.PENDING);
        assertThat(rows.get(1).getTotalPaymentDue()).isEqualByComparingTo("100000");
        verify(paymentRecordService).createConfirmedRecord(
                eq(99L), eq(PaymentTargetType.LOAN), any(), eq(new BigDecimal("50000")), any());
        verify(deposit).changeProcessingStatus(BankTransactionProcessingStatus.APPLIED);
    }

    @Test
    @DisplayName("회차 미리 내기로 모든 회차를 채우고 남으면 반환으로 기록한다")
    void prepayRecordsExcess() {
        when(deposit.getAmount()).thenReturn(new BigDecimal("300100"));
        when(earlyRepaymentRepository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.process(1L, 10L, new EarlyRepaymentRequest(99L, true, EarlyRepaymentMethod.PREPAY));

        assertThat(rows).allMatch(row -> row.getStatus() == RepaymentScheduleStatus.PAID);
        verify(depositReturnRepository).save(any());
    }

    private RepaymentSchedule row(int sequence, String remaining) {
        return new RepaymentSchedule(1L, sequence, LocalDate.of(2026, 12, 20).plusMonths(sequence - 1L),
                new BigDecimal("100000"), BigDecimal.ZERO, new BigDecimal("100000"),
                new BigDecimal(remaining), RepaymentScheduleStatus.PENDING, LocalDateTime.now());
    }
}