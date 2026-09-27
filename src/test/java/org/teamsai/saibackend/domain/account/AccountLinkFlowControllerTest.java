package org.teamsai.saibackend.domain.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import org.teamsai.saibackend.domain.link.controller.AccountLinkFlowController;
import org.teamsai.saibackend.domain.link.dto.response.AccountLinkCallbackResult;
import org.teamsai.saibackend.domain.link.service.AccountLinkFlowService;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.global.jwt.JwtAuthenticationEntryPoint;
import org.teamsai.saibackend.global.jwt.JwtAuthenticationFilter;
import org.teamsai.saibackend.global.jwt.JwtTokenProvider;
import org.teamsai.saibackend.global.security.CustomUserDetails;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AccountLinkFlowController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = {
        "app.frontend.base-url=http://localhost:5173"
})
class AccountLinkFlowControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccountLinkFlowController controller;

    @MockitoBean
    AccountLinkFlowService accountLinkFlowService;

    // 보안 설정에서 사용하는 Mock은 유지한다.
    @MockitoBean
    JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    @Test
    void startReturnsRedirectUrl() {
        User user = User.builder()
                .userId(1L)
                .build();

        String redirectUrl = "http://localhost:8081/link/start?state=state";

        when(accountLinkFlowService.startLink(1L))
                .thenReturn(redirectUrl);

        var response = controller.startLink(new CustomUserDetails(user));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .isEqualTo(Map.of("redirectUrl", redirectUrl));

        verify(accountLinkFlowService).startLink(1L);
    }

    @Test
    void successfulCallbackRedirects() throws Exception {
        when(accountLinkFlowService.completeCallback("state", "key", "1, 2,1"))
                .thenReturn(AccountLinkCallbackResult.completed());

        mvc.perform(get("/accounts/link/callback")
                        .param("state", "state")
                        .param("userKey", "key")
                        .param("accountIds", "1, 2,1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "http://localhost:5173/link/complete?success=true&state=state"
                ));

        verify(accountLinkFlowService)
                .completeCallback("state", "key", "1, 2,1");
    }

    @Test
    void failedCallbackRedirectsWithErrorMessage() throws Exception {
        String message = "연동키 복구 기한이 만료되어 상태 확인이 필요합니다.";

        when(accountLinkFlowService.completeCallback("state", "key", "1"))
                .thenReturn(AccountLinkCallbackResult.failed(message));

        String expectedUrl = UriComponentsBuilder
                .fromUriString("http://localhost:5173/link/complete")
                .queryParam("success", false)
                .queryParam("state", "state")
                .queryParam("errorMessage", message)
                .build()
                .encode()
                .toUriString();

        mvc.perform(get("/accounts/link/callback")
                        .param("state", "state")
                        .param("userKey", "key")
                        .param("accountIds", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(expectedUrl));

        verify(accountLinkFlowService)
                .completeCallback("state", "key", "1");
    }

    @Test
    void missingAccountIdsArePassedAsNull() throws Exception {
        when(accountLinkFlowService.completeCallback("state", "key", null))
                .thenReturn(AccountLinkCallbackResult.failed(
                        "선택된 계좌가 없습니다."
                ));

        mvc.perform(get("/accounts/link/callback")
                        .param("state", "state")
                        .param("userKey", "key"))
                .andExpect(status().is3xxRedirection());

        verify(accountLinkFlowService)
                .completeCallback("state", "key", null);
    }
}