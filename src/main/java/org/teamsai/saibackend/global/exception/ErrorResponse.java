package org.teamsai.saibackend.global.exception;

import java.time.LocalDateTime;

public record ErrorResponse(
        int status,
        String message,
        String code,
        LocalDateTime timestamp
) {

    public static ErrorResponse of(int status, String message) {
        return new ErrorResponse(
                status,
                message,
                null,
                LocalDateTime.now()
        );
    }

    public static ErrorResponse of(int status, String message, String code) {
        return new ErrorResponse(status, message, code, LocalDateTime.now());
    }
}
