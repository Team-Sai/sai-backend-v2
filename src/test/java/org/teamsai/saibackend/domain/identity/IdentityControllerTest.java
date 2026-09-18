package org.teamsai.saibackend.domain.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.teamsai.saibackend.domain.identity.controller.IdentityController;
import org.teamsai.saibackend.domain.identity.dto.request.IdentityPrepareRequest;
import org.teamsai.saibackend.domain.identity.dto.response.IdentityCompleteResponse;
import org.teamsai.saibackend.domain.identity.dto.response.IdentityPrepareResponse;
import org.teamsai.saibackend.domain.identity.exception.IdentityErrorCode;
import org.teamsai.saibackend.domain.identity.service.IdentityService;
import org.teamsai.saibackend.domain.identity.type.IdentityPurpose;
import org.teamsai.saibackend.domain.identity.type.IdentityStatus;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.repository.UserRepository;
import org.teamsai.saibackend.global.config.SecurityConfig;
import org.teamsai.saibackend.global.jwt.JwtAuthenticationEntryPoint;
import org.teamsai.saibackend.global.jwt.JwtAuthenticationFilter;
import org.teamsai.saibackend.global.jwt.JwtTokenProvider;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real security filter and exception handler; token cryptography and service mocked. */
@WebMvcTest(IdentityController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class,
        IdentityControllerTest.TestSecurity.class})
class IdentityControllerTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestSecurity {}

    @Autowired WebApplicationContext context;
    private MockMvc mvc;
    @MockitoBean IdentityService service;
    @MockitoBean JwtTokenProvider tokens;
    @MockitoBean UserRepository users;
    private static final String ID = "identity-verification-controller-test";

    @BeforeEach
    void setUp() {
        // Register the security chain once, with JwtAuthenticationFilter at its configured position.
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/identity-verifications", "/api/identity-verifications/test/complete"})
    void unauthenticatedRequestIsRejected(String path) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"LOAN_CONTRACT\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        when(tokens.getUserIdIfValid("invalid")).thenReturn(Optional.empty());
        mvc.perform(post("/api/identity-verifications").header("Authorization", "Bearer invalid")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"LOAN_CONTRACT\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void prepareUsesAuthenticatedUserRatherThanBodyUserId() throws Exception {
        authenticate();
        when(service.prepare(42L, new IdentityPrepareRequest(IdentityPurpose.LOAN_CONTRACT)))
                .thenReturn(new IdentityPrepareResponse(ID, "store", "channel"));
        mvc.perform(post("/api/identity-verifications").header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"LOAN_CONTRACT\",\"userId\":999}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.identityVerificationId").value(ID));
        verify(service).prepare(42L, new IdentityPrepareRequest(IdentityPurpose.LOAN_CONTRACT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"purpose\":null}", "{\"purpose\":\"UNKNOWN\"}", "not-json"})
    void invalidPrepareBodyIsRejected(String body) throws Exception {
        authenticate();
        mvc.perform(post("/api/identity-verifications").header("Authorization", "Bearer test-token")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void completeReturnsVerifiedResponse() throws Exception {
        authenticate();
        LocalDateTime now = LocalDateTime.of(2026, 9, 18, 12, 0);
        when(service.complete(42L, ID)).thenReturn(new IdentityCompleteResponse(ID, IdentityStatus.VERIFIED, now, now.plusMinutes(10)));
        mvc.perform(post("/api/identity-verifications/{id}/complete", ID).header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.identityVerificationId").value(ID));
        verify(service).complete(42L, ID);
    }

    @ParameterizedTest
    @EnumSource(value = IdentityErrorCode.class, names = {
            "IDENTITY_VERIFICATION_FORBIDDEN", "IDENTITY_VERIFICATION_NOT_FOUND",
            "IDENTITY_INFORMATION_MISMATCH", "IDENTITY_VERIFICATION_NOT_COMPLETED", "PORTONE_API_CALL_FAILED"
    })
    void domainErrorsKeepTheirHttpStatus(IdentityErrorCode error) throws Exception {
        authenticate();
        when(service.complete(42L, ID)).thenThrow(error.toException());
        mvc.perform(post("/api/identity-verifications/{id}/complete", ID).header("Authorization", "Bearer test-token"))
                .andExpect(status().is(error.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(error.getMessage()));
    }

    private void authenticate() {
        when(tokens.getUserIdIfValid("test-token")).thenReturn(Optional.of(42L));
        when(users.findById(42L)).thenReturn(Optional.of(User.builder().userId(42L).build()));
    }
}
