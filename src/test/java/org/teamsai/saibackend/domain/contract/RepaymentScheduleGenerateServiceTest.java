package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.teamsai.saibackend.domain.contract.entity.RepaymentSchedule;
import org.teamsai.saibackend.domain.contract.type.RepaymentMethod;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import org.teamsai.saibackend.domain.contract.service.RepaymentScheduleGenerateService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RepaymentScheduleGenerateServiceTest {

    private final RepaymentScheduleGenerateService generator = new RepaymentScheduleGenerateService();

    private final Long contractId = 1L;
    private final BigDecimal principal = BigDecimal.valueOf(10_000_000);
    private final BigDecimal annualInterestRate = BigDecimal.valueOf(12);
    private final List<LocalDate> dueDates = monthlyDueDates(12);

    @Test
    @DisplayName("원리금균등 - 회차 수는 개월 수와 같다")
    void equalPrincipalAndInterest_rowCount() {
        List<RepaymentSchedule> schedules =
                generator.generateEqualPrincipalAndInterest(contractId, principal, annualInterestRate, dueDates);

        assertThat(schedules).hasSize(12);
    }

    @Test
    @DisplayName("원리금균등 - 1회차 원금/이자/합계가 정확하다")
    void equalPrincipalAndInterest_firstRound() {
        List<RepaymentSchedule> schedules =
                generator.generateEqualPrincipalAndInterest(contractId, principal, annualInterestRate, dueDates);

        RepaymentSchedule first = schedules.get(0);
        assertThat(first.getInterestDue()).isEqualByComparingTo("100000");
        assertThat(first.getPrincipalDue()).isEqualByComparingTo("788487");
        assertThat(first.getTotalPaymentDue()).isEqualByComparingTo("888487");
        assertThat(first.getRemainingPrincipal()).isEqualByComparingTo("9211513");
    }

    @Test
    @DisplayName("원리금균등 - 매달 총 상환액(원금+이자)은 항상 동일하다")
    void equalPrincipalAndInterest_totalPaymentIsConstant() {
        List<RepaymentSchedule> schedules =
                generator.generateEqualPrincipalAndInterest(contractId, principal, annualInterestRate, dueDates);

        // 마지막 회차는 반올림 보정 때문에 살짝 다를 수 있어 제외
        BigDecimal firstTotal = schedules.get(0).getTotalPaymentDue();
        for (int i = 0; i < schedules.size() - 1; i++) {
            assertThat(schedules.get(i).getTotalPaymentDue()).isEqualByComparingTo(firstTotal);
        }
    }

    @Test
    @DisplayName("원리금균등 - 마지막 회차 후 잔액은 0원이다")
    void equalPrincipalAndInterest_lastRoundClearsBalance() {
        List<RepaymentSchedule> schedules =
                generator.generateEqualPrincipalAndInterest(contractId, principal, annualInterestRate, dueDates);

        RepaymentSchedule last = schedules.get(schedules.size() - 1);
        assertThat(last.getRemainingPrincipal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("원금균등 - 매달 원금은 고정, 이자는 잔액에 따라 감소한다")
    void equalPrincipal_firstAndSecondRound() {
        List<RepaymentSchedule> schedules =
                generator.generateEqualPrincipal(contractId, principal, annualInterestRate, dueDates);

        RepaymentSchedule first = schedules.get(0);
        RepaymentSchedule second = schedules.get(1);

        assertThat(first.getPrincipalDue()).isEqualByComparingTo("833333");
        assertThat(first.getInterestDue()).isEqualByComparingTo("100000");
        assertThat(second.getPrincipalDue()).isEqualByComparingTo("833333");
        assertThat(second.getInterestDue()).isEqualByComparingTo("91666");

        // 원금은 고정, 이자만 줄어서 → 2회차 총액이 1회차보다 작아야 함
        assertThat(second.getTotalPaymentDue()).isLessThan(first.getTotalPaymentDue());
    }

    @Test
    @DisplayName("원금균등 - 마지막 회차 후 잔액은 0원이다")
    void equalPrincipal_lastRoundClearsBalance() {
        List<RepaymentSchedule> schedules =
                generator.generateEqualPrincipal(contractId, principal, annualInterestRate, dueDates);

        assertThat(schedules.get(schedules.size() - 1).getRemainingPrincipal())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("만기일시 - 마지막 회차 전까지는 원금이 0이고 이자만 낸다")
    void bulletRepayment_middleRoundsInterestOnly() {
        List<RepaymentSchedule> schedules =
                generator.generateBulletRepayment(contractId, principal, annualInterestRate, dueDates);

        for (int i = 0; i < schedules.size() - 1; i++) {
            RepaymentSchedule row = schedules.get(i);
            assertThat(row.getPrincipalDue()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(row.getInterestDue()).isEqualByComparingTo("100000.00");
            assertThat(row.getRemainingPrincipal()).isEqualByComparingTo(principal);
        }
    }

    @Test
    @DisplayName("만기일시 - 마지막 회차에 원금 전액 + 이자를 낸다")
    void bulletRepayment_lastRoundPaysFullPrincipal() {
        List<RepaymentSchedule> schedules =
                generator.generateBulletRepayment(contractId, principal, annualInterestRate, dueDates);

        RepaymentSchedule last = schedules.get(schedules.size() - 1);
        assertThat(last.getPrincipalDue()).isEqualByComparingTo(principal);
        assertThat(last.getTotalPaymentDue()).isEqualByComparingTo("10100000.00");
        assertThat(last.getRemainingPrincipal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("무이자 100만원을 3회 상환하면 마지막 회차가 원금 나머지를 부담한다")
    void zeroInterest_lastRoundAbsorbsPrincipalRemainder() {
        List<RepaymentSchedule> schedules =
                generator.generateEqualPrincipalAndInterest(
                        contractId,
                        new BigDecimal("1000000"),
                        BigDecimal.ZERO,
                        monthlyDueDates(3)
                );

        assertThat(schedules)
                .extracting(RepaymentSchedule::getPrincipalDue)
                .containsExactly(
                        new BigDecimal("333333"),
                        new BigDecimal("333333"),
                        new BigDecimal("333334")
                );
        assertThat(schedules)
                .extracting(RepaymentSchedule::getTotalPaymentDue)
                .containsExactly(
                        new BigDecimal("333333"),
                        new BigDecimal("333333"),
                        new BigDecimal("333334")
                );
    }

    @Test
    @DisplayName("만기일시 0.5% 이자의 정수 나머지는 마지막 회차에 추가한다")
    void bulletRepayment_lastRoundAbsorbsInterestRemainder() {
        List<RepaymentSchedule> schedules =
                generator.generateBulletRepayment(
                        contractId,
                        new BigDecimal("100000"),
                        new BigDecimal("0.5"),
                        dueDates
                );

        assertThat(schedules.subList(0, 11))
                .extracting(RepaymentSchedule::getInterestDue)
                .containsOnly(new BigDecimal("41"));
        assertThat(schedules.get(11).getInterestDue()).isEqualByComparingTo("49");
        assertThat(schedules.stream()
                .map(RepaymentSchedule::getInterestDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("모든 상환방식의 저장 금액은 원 단위이고 원금 합계가 유지된다")
    void allMethods_generateWholeWonAmountsAndPreservePrincipal() {
        BigDecimal testPrincipal = new BigDecimal("1000000");
        BigDecimal testRate = new BigDecimal("0.5");

        List<List<RepaymentSchedule>> schedulesByMethod = List.of(
                generator.generateEqualPrincipalAndInterest(
                        contractId, testPrincipal, testRate, dueDates),
                generator.generateEqualPrincipal(
                        contractId, testPrincipal, testRate, dueDates),
                generator.generateBulletRepayment(
                        contractId, testPrincipal, testRate, dueDates)
        );

        for (List<RepaymentSchedule> schedules : schedulesByMethod) {
            assertThat(schedules.stream()
                    .map(RepaymentSchedule::getPrincipalDue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo(testPrincipal);

            assertThat(schedules).allSatisfy(schedule -> {
                assertWholeWon(schedule.getPrincipalDue());
                assertWholeWon(schedule.getInterestDue());
                assertWholeWon(schedule.getTotalPaymentDue());
                assertWholeWon(schedule.getRemainingPrincipal());
                assertThat(schedule.getTotalPaymentDue())
                        .isEqualByComparingTo(schedule.getPrincipalDue().add(schedule.getInterestDue()));
            });

            assertThat(schedules.get(schedules.size() - 1).getRemainingPrincipal())
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    @DisplayName("세 방식 모두 회차별 contractId와 sequence가 정확하다")
    void allMethods_haveCorrectContractIdAndSequence() {
        List<RepaymentSchedule> schedules =
                generator.generateEqualPrincipal(contractId, principal, annualInterestRate, dueDates);

        for (int i = 0; i < schedules.size(); i++) {
            assertThat(schedules.get(i).getContractId()).isEqualTo(contractId);
            assertThat(schedules.get(i).getSequence()).isEqualTo(i + 1);
            assertThat(schedules.get(i).getStatus()).isEqualTo(RepaymentScheduleStatus.PENDING);
        }
    }

    @Test
    @DisplayName("이자는 실제 날짜를 365로 나눠 계산한다")
    void interestFollowsActualDays() {
        List<RepaymentSchedule> rows = generator.generateWithDailyInterest(
                1L, RepaymentMethod.BULLET_REPAYMENT, new BigDecimal("12000000"), new BigDecimal("5"),
                LocalDate.of(2026, 12, 20), LocalDate.of(2027, 3, 20), 20, LocalDate.of(2026, 12, 20));

        assertThat(rows.get(0).getInterestDue()).isEqualByComparingTo("50958");
        assertThat(rows.get(2).getInterestDue()).isEqualByComparingTo("46027");
    }

    @Test
    @DisplayName("원리금균등은 납입액을 유지하고 마지막 회차에서 원금을 정리한다")
    void equalPaymentKeepsTotalWithDailyInterest() {
        List<RepaymentSchedule> rows = generator.generateWithDailyInterest(
                1L, RepaymentMethod.EQUAL_PRINCIPAL_AND_INTEREST, new BigDecimal("3000000"), new BigDecimal("5"),
                LocalDate.of(2026, 12, 20), LocalDate.of(2027, 3, 20), 20, LocalDate.of(2026, 12, 20));

        assertThat(rows.get(1).getTotalPaymentDue()).isEqualByComparingTo(rows.get(0).getTotalPaymentDue());
        assertThat(rows.get(0).getInterestDue()).isEqualByComparingTo("12739");
        assertThat(rows.get(2).getRemainingPrincipal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("경과 이자는 지난 날짜만큼 계산한다")
    void accruedInterest() {
        BigDecimal interest = generator.calculateDailyInterest(new BigDecimal("12000000"), new BigDecimal("5"),
                LocalDate.of(2026, 11, 20), LocalDate.of(2026, 12, 5));

        assertThat(interest).isEqualByComparingTo("24657");
    }

    @ParameterizedTest
    @EnumSource(RepaymentMethod.class)
    @DisplayName("모든 상환방식의 1회차는 지급 월의 다음 달 상환일이고 이후 매월 상환일이다")
    void generate_firstRoundIsRepaymentDayOfNextMonth(RepaymentMethod method) {
        List<RepaymentSchedule> schedules = generateWithDates(
                method, LocalDate.of(2026, 11, 11), LocalDate.of(2027, 11, 11), 12);

        assertThat(schedules).hasSize(12);
        assertThat(schedules.get(0).getDueDate()).isEqualTo(LocalDate.of(2026, 12, 12));
        assertThat(schedules.get(1).getDueDate()).isEqualTo(LocalDate.of(2027, 1, 12));
    }

    @Test
    @DisplayName("지급일이 상환일보다 빨라도 지급 월이 아닌 다음 달부터 시작한다")
    void generate_doesNotUseStartMonthEvenIfRepaymentDayIsLater() {
        List<RepaymentSchedule> schedules = generateWithDates(
                RepaymentMethod.EQUAL_PRINCIPAL, LocalDate.of(2026, 11, 1), LocalDate.of(2027, 2, 1), 11);

        assertThat(schedules.get(0).getDueDate()).isEqualTo(LocalDate.of(2026, 12, 11));
    }

    @Test
    @DisplayName("만기일의 날짜가 지급일보다 작아도 매월 회차를 빠뜨리지 않는다")
    void generate_keepsEveryMonthWhenMaturityDayIsSmaller() {
        List<RepaymentSchedule> schedules = generateWithDates(
                RepaymentMethod.EQUAL_PRINCIPAL_AND_INTEREST, LocalDate.of(2026, 11, 13), LocalDate.of(2027, 2, 12), 1);

        assertThat(schedules).extracting(RepaymentSchedule::getDueDate).containsExactly(
                LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 2, 12));
    }

    @Test
    @DisplayName("그 달에 상환일이 없으면 말일로 하고 다음 달에는 상환일로 돌아온다")
    void generate_clampsToLastDayOfMonth() {
        List<RepaymentSchedule> schedules = generateWithDates(
                RepaymentMethod.EQUAL_PRINCIPAL, LocalDate.of(2026, 12, 15), LocalDate.of(2027, 6, 15), 31);

        assertThat(schedules)
                .extracting(RepaymentSchedule::getDueDate)
                .startsWith(
                        LocalDate.of(2027, 1, 31),
                        LocalDate.of(2027, 2, 28),
                        LocalDate.of(2027, 3, 31),
                        LocalDate.of(2027, 4, 30)
                );
    }

    @Test
    @DisplayName("윤년 2월의 말일은 29일이다")
    void generate_clampsToLeapYearLastDay() {
        List<RepaymentSchedule> schedules = generateWithDates(
                RepaymentMethod.EQUAL_PRINCIPAL, LocalDate.of(2028, 1, 10), LocalDate.of(2028, 4, 10), 31);

        assertThat(schedules.get(0).getDueDate()).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    @DisplayName("마지막 회차가 최종 변제기일을 넘으면 최종 변제기일로 한다")
    void generate_capsLastRoundToMaturityDate() {
        List<RepaymentSchedule> schedules = generateWithDates(
                RepaymentMethod.EQUAL_PRINCIPAL, LocalDate.of(2026, 11, 11), LocalDate.of(2027, 3, 11), 12);

        assertThat(schedules)
                .extracting(RepaymentSchedule::getDueDate)
                .containsExactly(
                        LocalDate.of(2026, 12, 12),
                        LocalDate.of(2027, 1, 12),
                        LocalDate.of(2027, 2, 12),
                        LocalDate.of(2027, 3, 11)
                );
    }

    @ParameterizedTest
    @EnumSource(RepaymentMethod.class)
    @DisplayName("상환일이 최종 변제기일보다 빨라도 마지막 회차는 최종 변제기일이다")
    void generate_lastRoundIsAlwaysMaturityDate(RepaymentMethod method) {
        List<RepaymentSchedule> schedules = generateWithDates(
                method, LocalDate.of(2026, 11, 11), LocalDate.of(2027, 3, 11), 5);

        assertThat(schedules)
                .extracting(RepaymentSchedule::getDueDate)
                .containsExactly(
                        LocalDate.of(2026, 12, 5),
                        LocalDate.of(2027, 1, 5),
                        LocalDate.of(2027, 2, 5),
                        LocalDate.of(2027, 3, 11)
                );
    }

    @Test
    @DisplayName("기간에 잔여 일수가 있어도 매월 회차를 만들고 마지막 회차는 최종 변제기일이다")
    void generate_keepsRoundCountWhenPeriodHasRemainingDays() {
        List<RepaymentSchedule> schedules = generateWithDates(
                RepaymentMethod.EQUAL_PRINCIPAL, LocalDate.of(2027, 1, 10), LocalDate.of(2027, 6, 5), 25);

        assertThat(schedules)
                .extracting(RepaymentSchedule::getDueDate)
                .containsExactly(
                        LocalDate.of(2027, 2, 25),
                        LocalDate.of(2027, 3, 25),
                        LocalDate.of(2027, 4, 25),
                        LocalDate.of(2027, 5, 25),
                        LocalDate.of(2027, 6, 5)
                );
    }

    @ParameterizedTest
    @EnumSource(RepaymentMethod.class)
    @DisplayName("상환일이 달라도 회차 수와 회차별 금액은 같다")
    void generate_repaymentDayDoesNotChangeAmounts(RepaymentMethod method) {
        LocalDate start = LocalDate.of(2026, 11, 11);
        LocalDate maturity = LocalDate.of(2027, 11, 11);

        List<RepaymentSchedule> day11 = generateWithDates(method, start, maturity, 11);
        List<RepaymentSchedule> day31 = generateWithDates(method, start, maturity, 31);

        assertThat(day31).hasSameSizeAs(day11);
        for (int i = 0; i < day11.size(); i++) {
            assertThat(day31.get(i).getPrincipalDue()).isEqualByComparingTo(day11.get(i).getPrincipalDue());
            assertThat(day31.get(i).getInterestDue()).isEqualByComparingTo(day11.get(i).getInterestDue());
            assertThat(day31.get(i).getRemainingPrincipal()).isEqualByComparingTo(day11.get(i).getRemainingPrincipal());
        }
    }

    private List<RepaymentSchedule> generateWithDates(
            RepaymentMethod method, LocalDate start, LocalDate maturity, int repaymentDay
    ) {
        return generator.generate(contractId, method, principal, annualInterestRate, start, maturity, repaymentDay);
    }

    private static List<LocalDate> monthlyDueDates(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> LocalDate.of(2026, 1, 1).plusMonths(i))
                .toList();
    }

    private void assertWholeWon(BigDecimal amount) {
        assertThat(amount.remainder(BigDecimal.ONE))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
