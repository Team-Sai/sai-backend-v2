package org.teamsai.saibackend.domain.contract.assembler;

import org.teamsai.saibackend.domain.contract.dto.response.ChangeRequestDetailResponse;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.entity.LoanContractChangeRequestEntity;
import org.teamsai.saibackend.domain.contract.type.ChangeRequestStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.Objects;

/**
 * 대출계약(LoanContractResponse) + 변경요청(LoanContractChangeRequestEntity) + 예상 월 상환액을
 * 조합해서 변경요청 상세 응답 DTO를 만드는 조립 전담 클래스.
 * 조회/검증/월 상환액 계산 같은 흐름 제어는 ChangeRequestDetailQueryService에 남아있다.
 */
public final class ChangeRequestDetailAssembler {

    private ChangeRequestDetailAssembler() {
    }

    public static ChangeRequestDetailResponse toDetail(
            LoanContractResponse contract,
            LoanContractChangeRequestEntity changeRequest,
            Long newContractId,
            BigDecimal currentMonthlyPayment,
            BigDecimal newMonthlyPayment
    ) {
        LocalDate effectiveMaturityDate = effectiveMaturityDate(contract, changeRequest);
        boolean requestedByCreditor = Objects.equals(changeRequest.getUserId(), contract.getCreditorId());

        return ChangeRequestDetailResponse.builder()
                .changeRequestId(changeRequest.getChangeRequestId())
                .newContractId(newContractId)
                .requesterName(requestedByCreditor ? contract.getCreditorName() : contract.getDebtorName())
                .rejectorName(requestedByCreditor ? contract.getDebtorName() : contract.getCreditorName())
                .requestedAt(changeRequest.getCreatedAt())
                .status(translateStatus(changeRequest.getStatus()))
                .currentMaturityDate(contract.getMaturityDate())
                .currentInterestRate(contract.getInterestRate())
                .currentRepaymentType(contract.getRepaymentType().getDescription())
                .currentTerms(contract.getTerms())
                .newMaturityDate(effectiveMaturityDate)
                .newInterestRate(effectiveInterestRate(contract, changeRequest))
                .newRepaymentType(translateRepaymentType(effectiveRepaymentType(contract, changeRequest)))
                .newTerms(effectiveTerms(contract, changeRequest))
                .changeReason(changeRequest.getChangeReason())
                .extendedMonths(calculateExtendedMonths(contract.getMaturityDate(), effectiveMaturityDate))
                .returnReason(changeRequest.getReturnReason())
                .currentMonthlyPayment(currentMonthlyPayment)
                .newMonthlyPayment(newMonthlyPayment)
                .build();
    }

    public static BigDecimal effectiveInterestRate(
            LoanContractResponse contract, LoanContractChangeRequestEntity changeRequest) {
        return changeRequest.getNewInterestRate() != null
                ? changeRequest.getNewInterestRate() : contract.getInterestRate();
    }

    public static String effectiveRepaymentType(
            LoanContractResponse contract, LoanContractChangeRequestEntity changeRequest) {
        return changeRequest.getNewRepaymentType() != null
                ? changeRequest.getNewRepaymentType() : contract.getRepaymentType().name();
    }

    public static LocalDate effectiveMaturityDate(
            LoanContractResponse contract, LoanContractChangeRequestEntity changeRequest) {
        return changeRequest.getNewMaturityDate() != null
                ? changeRequest.getNewMaturityDate() : contract.getMaturityDate();
    }

    private static String effectiveTerms(
            LoanContractResponse contract, LoanContractChangeRequestEntity changeRequest) {
        return changeRequest.getNewTerms() != null
                ? changeRequest.getNewTerms() : contract.getTerms();
    }

    private static int calculateExtendedMonths(LocalDate currentMaturityDate, LocalDate newMaturityDate) {
        Period period = Period.between(currentMaturityDate, newMaturityDate);
        return period.getMonths() + period.getYears() * 12;
    }

    private static String translateRepaymentType(String repaymentType) {
        return switch (repaymentType) {
            case "EQUAL_PRINCIPAL_AND_INTEREST" -> "원리금균등상환";
            case "EQUAL_PRINCIPAL" -> "원금균등상환";
            case "BULLET_REPAYMENT" -> "만기일시상환";
            default -> repaymentType;
        };
    }

    private static String translateStatus(ChangeRequestStatus status) {
        return switch (status) {
            case PENDING -> "승인 대기 중";
            case APPROVED -> "승인됨";
            case REJECTED -> "거절됨";
            case CANCELLED -> "취소됨";
        };
    }
}
