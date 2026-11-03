package org.teamsai.saibackend.domain.contract.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.teamsai.saibackend.global.exception.BaseErrorCode;
import org.teamsai.saibackend.global.exception.DomainException;

@Getter
@RequiredArgsConstructor
public enum ClauseChatErrorCode implements BaseErrorCode<DomainException> {

    CHAT_NOT_FOUND(HttpStatus.NOT_FOUND, "대화가 끝났거나 찾을 수 없어요. 처음부터 다시 시작해 주세요."),
    CHAT_NOT_READY_TO_WRITE(HttpStatus.CONFLICT, "아직 확인할 내용이 남아 있어요. 질문에 먼저 답해 주세요.");

    private final HttpStatus httpStatus;
    private final String message;

    @Override
    public DomainException toException() {
        return new DomainException(httpStatus, this);
    }
}