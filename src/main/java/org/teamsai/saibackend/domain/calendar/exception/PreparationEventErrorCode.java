package org.teamsai.saibackend.domain.calendar.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.teamsai.saibackend.global.exception.BaseErrorCode;
import org.teamsai.saibackend.global.exception.DomainException;

@Getter
@RequiredArgsConstructor
public enum PreparationEventErrorCode
        implements BaseErrorCode<DomainException> {

    EVENT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "변경할 확인 일정을 찾을 수 없습니다."
    ),

    EVENT_NOT_RESCHEDULABLE(
            HttpStatus.CONFLICT,
            "아직 발송되지 않은 미래 확인 일정만 변경할 수 있습니다."
    ),
    UNAUTHENTICATED(
            HttpStatus.UNAUTHORIZED,
            "로그인이 필요합니다."
    ),

    INVALID_REQUEST(
            HttpStatus.BAD_REQUEST,
            "준비 일정 입력값이 올바르지 않습니다."
    ),

    INVALID_TIME(
            HttpStatus.BAD_REQUEST,
            "준비 일정은 미래의 같은 날에 최대 60분으로 설정하세요."
    ),

    CANDIDATE_NOT_FOUND(
            HttpStatus.CONFLICT,
            "현재 준비 일정을 등록할 수 있는 미상환 회차가 아닙니다."
    ),

    AFTER_DUE_DATE(
            HttpStatus.BAD_REQUEST,
            "준비 일정은 계약상 납기일까지 설정하세요."
    ),

    ALREADY_REGISTERED(
            HttpStatus.CONFLICT,
            "해당 회차에 준비 일정이 이미 등록되어 있습니다."
    ),

    TIME_CONFLICT(
            HttpStatus.CONFLICT,
            "이미 등록된 상환 준비 일정과 시간이 겹칩니다."
    ),
    PROPOSAL_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "승인할 제안을 찾을 수 없습니다."
    ),

    PROPOSAL_EXPIRED(
            HttpStatus.GONE,
            "제안이 만료되었습니다. 새 제안을 생성하세요."
    ),

    PROPOSAL_CHANGED(
            HttpStatus.CONFLICT,
            "상환 상태 또는 준비 일정이 변경되었습니다. 새 제안을 생성하세요."
    );

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public DomainException toException() {
        return new DomainException(httpStatus, this);
    }
}