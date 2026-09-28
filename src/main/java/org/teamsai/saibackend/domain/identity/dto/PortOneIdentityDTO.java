package org.teamsai.saibackend.domain.identity.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PortOneIdentityDTO(
        String id,
        String status,
        VerifiedCustomer verifiedCustomer,
        Failure failure
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VerifiedCustomer(
            String name,
            LocalDate birthDate,
            String ci
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Failure(
            String reason,
            String pgCode,
            String pgMessage
    ) {
    }
}