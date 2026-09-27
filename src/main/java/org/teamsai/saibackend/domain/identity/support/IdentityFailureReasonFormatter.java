package org.teamsai.saibackend.domain.identity.support;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.teamsai.saibackend.domain.identity.dto.response.PortOneIdentityResponse;

import java.util.ArrayList;
import java.util.List;

@Component
public class IdentityFailureReasonFormatter {

    private static final String PORTONE_STATUS_FAILED =
            "FAILED";

    private static final int FAILURE_REASON_MAX_LENGTH =
            255;

    public String createFailureReason(
            PortOneIdentityResponse response
    ) {
        PortOneIdentityResponse.Failure failure =
                response.failure();

        if (failure == null) {
            return PORTONE_STATUS_FAILED;
        }

        List<String> reasonParts =
                new ArrayList<>();

        addFailureReason(
                reasonParts,
                failure.reason()
        );

        addFailureReason(
                reasonParts,
                failure.pgCode()
        );

        addFailureReason(
                reasonParts,
                failure.pgMessage()
        );

        if (reasonParts.isEmpty()) {
            return PORTONE_STATUS_FAILED;
        }

        return String.join(
                " | ",
                reasonParts
        );
    }

    public String truncateFailureReason(
            String failureReason
    ) {
        if (!StringUtils.hasText(failureReason)) {
            return PORTONE_STATUS_FAILED;
        }

        String normalizedReason =
                failureReason.trim();

        if (normalizedReason.length()
                <= FAILURE_REASON_MAX_LENGTH) {

            return normalizedReason;
        }

        return normalizedReason.substring(
                0,
                FAILURE_REASON_MAX_LENGTH
        );
    }

    private void addFailureReason(
            List<String> reasonParts,
            String value
    ) {
        if (StringUtils.hasText(value)) {
            reasonParts.add(value.trim());
        }
    }
}