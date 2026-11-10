package org.teamsai.saibackend.domain.contract.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.teamsai.saibackend.global.exception.BaseErrorCode;
import org.teamsai.saibackend.global.exception.DomainException;

@Getter
@RequiredArgsConstructor
public enum RepaymentScheduleErrorCode implements BaseErrorCode<DomainException> {

    INVALID_CONTRACT_PERIOD(HttpStatus.BAD_REQUEST, "대출 기간은 최소 1개월 이상이어야 합니다."),

    SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND,"상환 스케줄을 찾을 수 없습니다"),

    SCHEDULE_NOT_PENDING(HttpStatus.CONFLICT, "이미 상환 완료되었거나 처리 불가능한 스케줄입니다."),

    SCHEDULE_GENERATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "상환 스케줄 생성 중 오류가 발생했습니다."),

    EARLY_REPAYMENT_FORBIDDEN(HttpStatus.FORBIDDEN, "채권자만 중도상환을 처리할 수 있습니다."),

    EARLY_REPAYMENT_NOT_ALLOWED(HttpStatus.CONFLICT, "중도상환을 처리할 수 없는 계약입니다."),

    EARLY_REPAYMENT_ACCOUNT_MISSING(HttpStatus.CONFLICT, "이 계약에 연결된 수취 계좌가 없어 입금을 확인할 수 없어요."),

    EARLY_REPAYMENT_CONTRACT_NOT_ACTIVE(HttpStatus.CONFLICT, "진행 중인 계약에서만 입금을 확인하고 처리할 수 있어요."),

    EARLY_REPAYMENT_DEPOSIT_INVALID(HttpStatus.CONFLICT, "중도상환에 사용할 수 없는 입금입니다."),

    EARLY_REPAYMENT_AMOUNT_INVALID(HttpStatus.BAD_REQUEST, "입금액이 부족하거나 남은 원금보다 많습니다."),

    EARLY_REPAYMENT_DEPOSIT_OUT_OF_ORDER(HttpStatus.CONFLICT, "이전 중도상환보다 앞선 입금은 처리할 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public DomainException toException() {
        return new DomainException(httpStatus, this);
    }
}
