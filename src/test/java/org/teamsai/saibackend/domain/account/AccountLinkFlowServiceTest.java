package org.teamsai.saibackend.domain.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.identity.support.IdentityValidator;
import org.teamsai.saibackend.domain.link.dto.response.AccountLinkCallbackResult;
import org.teamsai.saibackend.domain.link.service.AccountLinkCoordinator;
import org.teamsai.saibackend.domain.link.service.AccountLinkFlowService;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.exception.UserErrorCode;
import org.teamsai.saibackend.domain.user.service.UserService;
import org.teamsai.saibackend.global.jwt.JwtTokenProvider;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountLinkFlowServiceTest {

    @Mock
    JwtTokenProvider jwtTokenProvider;

    @Mock
    LinkedBankAccountService linkedBankAccountService;

    @Mock
    AccountLinkCoordinator coordinator;

    @Mock
    UserService userService;

    @Mock
    IdentityValidator identityValidator;

    @InjectMocks
    AccountLinkFlowService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(
                service, "mockBankBaseUrl", "http://localhost:8081"
        );
        ReflectionTestUtils.setField(
                service, "backendBaseUrl", "http://localhost:8080"
        );
    }

    @Test
    void startRecoversBeforeIssuingNewState() {
        User user = User.builder()
                .userId(1L)
                .name("name")
                .birthDate(LocalDate.of(2000, 1, 1))
                .build();

        when(userService.getUser(1L)).thenReturn(user);
        when(jwtTokenProvider.createLinkStateToken(
                1L, user.getName(), user.getBirthDate()
        )).thenReturn("fresh-state");
        when(linkedBankAccountService.getLinkedAccountIds(1L))
                .thenReturn(List.of(10L, 20L));

        String redirectUrl = service.startLink(1L);

        var order = inOrder(
                userService,
                identityValidator,
                coordinator,
                jwtTokenProvider,
                linkedBankAccountService
        );

        order.verify(userService).getUser(1L);
        order.verify(identityValidator).validateUserInformation(user);
        order.verify(coordinator).recoverUnresolved(1L);
        order.verify(jwtTokenProvider).createLinkStateToken(
                1L, user.getName(), user.getBirthDate()
        );
        order.verify(linkedBankAccountService).getLinkedAccountIds(1L);

        assertThat(redirectUrl).isEqualTo(
                "http://localhost:8081/link/start"
                        + "?returnUrl=http://localhost:8080/accounts/link/callback"
                        + "&state=fresh-state"
                        + "&excludeAccountIds=10,20"
        );
    }

    @Test
    void failedRecoveryPreventsNewLinkState() {
        User user = User.builder().userId(1L).build();

        when(userService.getUser(1L)).thenReturn(user);
        doThrow(AccountErrorCode.LINK_RECONCILIATION_REQUIRED.toException())
                .when(coordinator).recoverUnresolved(1L);

        assertThatThrownBy(() -> service.startLink(1L))
                .extracting("errorCode")
                .isEqualTo(AccountErrorCode.LINK_RECONCILIATION_REQUIRED);

        verifyNoInteractions(jwtTokenProvider, linkedBankAccountService);
    }

    @Test
    void validCallbackParsesAndCompletes() {
        when(jwtTokenProvider.getUserIdFromLinkState("state"))
                .thenReturn(Optional.of(1L));

        var result = service.completeCallback("state", "key", "1, 2,1");

        assertThat(result).isEqualTo(AccountLinkCallbackResult.completed());
        verify(coordinator).completeCallback(
                1L, "state", "key", List.of(1L, 2L, 1L)
        );
    }

    @Test
    void invalidStateDoesNotCallCoordinator() {
        when(jwtTokenProvider.getUserIdFromLinkState("bad"))
                .thenReturn(Optional.empty());

        var result = service.completeCallback("bad", "key", "1");

        assertThat(result).isEqualTo(AccountLinkCallbackResult.failed(
                "유효하지 않거나 만료된 요청입니다."
        ));
        verifyNoInteractions(coordinator);
    }

    @Test
    void stateValidationExceptionReturnsFailure() {
        when(jwtTokenProvider.getUserIdFromLinkState("bad"))
                .thenThrow(UserErrorCode.INVALID_LINK_STATE.toException());

        var result = service.completeCallback("bad", "key", "1");

        assertThat(result).isEqualTo(AccountLinkCallbackResult.failed(
                "유효하지 않거나 만료된 요청입니다."
        ));
        verifyNoInteractions(coordinator);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", ", ,"})
    void missingAccountsDoNotCallCoordinator(String accountIds) {
        when(jwtTokenProvider.getUserIdFromLinkState("state"))
                .thenReturn(Optional.of(1L));

        var result = service.completeCallback("state", "key", accountIds);

        assertThat(result).isEqualTo(AccountLinkCallbackResult.failed(
                "선택된 계좌가 없습니다."
        ));
        verifyNoInteractions(coordinator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "9223372036854775808"})
    void malformedAccountIdsDoNotCallCoordinator(String accountIds) {
        when(jwtTokenProvider.getUserIdFromLinkState("state"))
                .thenReturn(Optional.of(1L));

        var result = service.completeCallback("state", "key", accountIds);

        assertThat(result).isEqualTo(AccountLinkCallbackResult.failed(
                "계좌 연동에 실패했습니다."
        ));
        verifyNoInteractions(coordinator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1,-1"})
    void nonPositiveAccountIdsDoNotCallCoordinator(String accountIds) {
        when(jwtTokenProvider.getUserIdFromLinkState("state"))
                .thenReturn(Optional.of(1L));

        var result = service.completeCallback("state", "key", accountIds);

        assertThat(result).isEqualTo(AccountLinkCallbackResult.failed(
                "계좌 연동 요청이 올바르지 않습니다."
        ));
        verifyNoInteractions(coordinator);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void invalidUserKeyDoesNotCallCoordinator(String userKey) {
        when(jwtTokenProvider.getUserIdFromLinkState("state"))
                .thenReturn(Optional.of(1L));

        var result = service.completeCallback("state", userKey, "1");

        assertThat(result).isEqualTo(AccountLinkCallbackResult.failed(
                "계좌 연동 요청이 올바르지 않습니다."
        ));
        verifyNoInteractions(coordinator);
    }

    @ParameterizedTest
    @EnumSource(
            value = AccountErrorCode.class,
            names = {
                    "LINK_IN_PROGRESS",
                    "LINK_RECOVERY_EXPIRED",
                    "LINK_RECOVERY_CONFLICT"
            }
    )
    void domainFailurePreservesMessage(AccountErrorCode errorCode) {
        when(jwtTokenProvider.getUserIdFromLinkState("state"))
                .thenReturn(Optional.of(1L));

        doThrow(errorCode.toException())
                .when(coordinator)
                .completeCallback(1L, "state", "key", List.of(1L));

        var result = service.completeCallback("state", "key", "1");

        assertThat(result).isEqualTo(
                AccountLinkCallbackResult.failed(errorCode.getMessage())
        );
    }
}