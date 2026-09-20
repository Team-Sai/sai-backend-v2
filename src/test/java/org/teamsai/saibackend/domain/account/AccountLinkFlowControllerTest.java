package org.teamsai.saibackend.domain.account;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.identity.service.IdentityValidator;
import org.teamsai.saibackend.domain.link.controller.AccountLinkFlowController;
import org.teamsai.saibackend.domain.link.service.AccountLinkCoordinator;
import org.teamsai.saibackend.domain.user.service.UserService;
import org.teamsai.saibackend.global.jwt.*;
import java.util.List;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(AccountLinkFlowController.class)
@AutoConfigureMockMvc(addFilters=false)
@TestPropertySource(properties={"sai.mock-bank.base-url=http://localhost:8081",
        "sai.backend.base-url=http://localhost:8080","app.frontend.base-url=http://localhost:5173"})
class AccountLinkFlowControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean LinkedBankAccountService linkedBankAccountService;
    @MockitoBean AccountLinkCoordinator coordinator;
    @MockitoBean UserService userService;
    @MockitoBean IdentityValidator identityValidator;
    @MockitoBean JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    @Test void startRecoversBeforeIssuingNewState() {
        var user = org.teamsai.saibackend.domain.user.entity.User.builder()
                .userId(1L).name("name").birthDate(java.time.LocalDate.of(2000, 1, 1)).build();
        when(userService.getUser(1L)).thenReturn(user);
        when(jwtTokenProvider.createLinkStateToken(1L, user.getName(), user.getBirthDate()))
                .thenReturn("fresh-state");
        var controller = new AccountLinkFlowController(jwtTokenProvider, linkedBankAccountService,
                coordinator, userService, identityValidator);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "mockBankBaseUrl", "http://localhost:8081");
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "backendBaseUrl", "http://localhost:8080");

        controller.startLink(new org.teamsai.saibackend.global.security.CustomUserDetails(user));

        var order = inOrder(coordinator, jwtTokenProvider);
        order.verify(coordinator).recoverUnresolved(1L);
        order.verify(jwtTokenProvider).createLinkStateToken(1L, user.getName(), user.getBirthDate());
    }

    @Test void failedRecoveryPreventsNewLinkState() {
        var user = org.teamsai.saibackend.domain.user.entity.User.builder().userId(1L).build();
        when(userService.getUser(1L)).thenReturn(user);
        doThrow(AccountErrorCode.LINK_RECONCILIATION_REQUIRED.toException())
                .when(coordinator).recoverUnresolved(1L);
        var controller = new AccountLinkFlowController(jwtTokenProvider, linkedBankAccountService,
                coordinator, userService, identityValidator);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.startLink(
                new org.teamsai.saibackend.global.security.CustomUserDetails(user)))
                .extracting("errorCode").isEqualTo(AccountErrorCode.LINK_RECONCILIATION_REQUIRED);
        verifyNoInteractions(jwtTokenProvider);
    }

    @Test void validCallbackParsesAndRedirects() throws Exception {
        when(jwtTokenProvider.getUserIdFromLinkState("state")).thenReturn(Optional.of(1L));
        mvc.perform(get("/accounts/link/callback").param("state","state").param("userKey","key")
                        .param("accountIds","1, 2,1")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:5173/link/complete?success=true&state=state"));
        verify(coordinator).completeCallback(1L,"state","key",List.of(1L,2L,1L));
    }
    @Test void invalidStateDoesNotChangeBank() throws Exception {
        when(jwtTokenProvider.getUserIdFromLinkState("bad")).thenReturn(Optional.empty());
        mvc.perform(get("/accounts/link/callback").param("state","bad").param("userKey","key")
                .param("accountIds","1")).andExpect(header().string("Location", org.hamcrest.Matchers.containsString("?success=false&")));
        verifyNoInteractions(coordinator);
    }
    @ParameterizedTest @ValueSource(strings={"",", ,","abc","0","-1"})
    void invalidAccountIdsDoNotChangeBank(String ids) throws Exception {
        when(jwtTokenProvider.getUserIdFromLinkState("state")).thenReturn(Optional.of(1L));
        mvc.perform(get("/accounts/link/callback").param("state","state").param("userKey","key")
                .param("accountIds",ids)).andExpect(header().string("Location", org.hamcrest.Matchers.containsString("?success=false&")));
        verifyNoInteractions(coordinator);
    }
    @Test void domainFailureRedirects() throws Exception {
        when(jwtTokenProvider.getUserIdFromLinkState("state")).thenReturn(Optional.of(1L));
        doThrow(AccountErrorCode.LINK_IN_PROGRESS.toException()).when(coordinator)
                .completeCallback(1L,"state","key",List.of(1L));
        mvc.perform(get("/accounts/link/callback").param("state","state").param("userKey","key")
                .param("accountIds","1")).andExpect(header().string("Location", org.hamcrest.Matchers.containsString("?success=false&")));
    }
}
