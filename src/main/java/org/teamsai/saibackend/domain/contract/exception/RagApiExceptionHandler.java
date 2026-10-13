
package org.teamsai.saibackend.domain.contract.exception;

import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import org.teamsai.saibackend.domain.contract.controller.ContractClauseAgentController;
import org.teamsai.saibackend.domain.contract.controller.ContractLegalReviewController;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {
        ContractClauseAgentController.class,
        ContractLegalReviewController.class
})
public class RagApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleRagException(
            ResponseStatusException e
    ) {
        String message = e.getReason() != null
                ? e.getReason()
                : "AI 서비스 처리 중 오류가 발생했습니다.";

        return ResponseEntity
                .status(e.getStatusCode())
                .body(Map.of(
                        "status", e.getStatusCode().value(),
                        "message", message
                ));
    }
}
