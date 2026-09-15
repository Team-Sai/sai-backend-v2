package org.teamsai.saibackend.domain.link;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.link.service.AccountLinkService;
import org.teamsai.saibackend.domain.user.exception.UserErrorCode;
import org.teamsai.saibackend.domain.user.repository.UserRepository;
import org.teamsai.saibackend.global.exception.DomainException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountLinkService 단위 테스트")
class AccountLinkServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private LinkedBankAccountService linkedBankAccountService;

    private AccountLinkService accountLinkService;

    private static final Long USER_ID = 1L;
    private static final String NEW_KEY = "mb_newkey";
    private static final String OLD_KEY = "mb_oldkey";

    @BeforeEach
    void setUp() {
        accountLinkService = new AccountLinkService(
                userRepository,
                linkedBankAccountService
        );
    }

    @Nested
    @DisplayName("completeLink(userId, userKey, accountIds)")
    class CompleteLink {

        @Test
        @DisplayName("기존 키가 없으면(최초 연동) 정상적으로 갱신되고 계좌가 연동된다")
        void linksSuccessfullyWhenNoPreviousKey() {
            given(
                    userRepository.findUserKeyByUserId(USER_ID)
            ).willReturn(null);

            given(
                    userRepository.updateUserKeyByUserId(
                            USER_ID,
                            NEW_KEY,
                            null
                    )
            ).willReturn(1);

            accountLinkService.completeLink(
                    USER_ID,
                    NEW_KEY,
                    List.of(1L)
            );

            verify(userRepository)
                    .findUserKeyByUserId(USER_ID);

            verify(userRepository)
                    .updateUserKeyByUserId(
                            USER_ID,
                            NEW_KEY,
                            null
                    );

            verify(linkedBankAccountService)
                    .linkAccountsByIds(
                            USER_ID,
                            NEW_KEY,
                            List.of(1L)
                    );
        }

        @Test
        @DisplayName("기존 키와 새 키가 같아도 정상적으로 갱신되고 계좌가 연동된다")
        void linksSuccessfullyWhenKeyUnchanged() {
            given(
                    userRepository.findUserKeyByUserId(USER_ID)
            ).willReturn(NEW_KEY);

            given(
                    userRepository.updateUserKeyByUserId(
                            USER_ID,
                            NEW_KEY,
                            NEW_KEY
                    )
            ).willReturn(1);

            accountLinkService.completeLink(
                    USER_ID,
                    NEW_KEY,
                    List.of(1L)
            );

            verify(userRepository)
                    .findUserKeyByUserId(USER_ID);

            verify(userRepository)
                    .updateUserKeyByUserId(
                            USER_ID,
                            NEW_KEY,
                            NEW_KEY
                    );

            verify(linkedBankAccountService)
                    .linkAccountsByIds(
                            USER_ID,
                            NEW_KEY,
                            List.of(1L)
                    );
        }

        @Test
        @DisplayName("동시 요청 경합으로 userKey 갱신이 0건이면 LINK_KEY_UPDATE_CONFLICT 예외를 던진다")
        void throwsConflictWhenUpdateAffectsZeroRowsDueToRace() {
            given(
                    userRepository.findUserKeyByUserId(USER_ID)
            ).willReturn(OLD_KEY);

            given(
                    userRepository.updateUserKeyByUserId(
                            USER_ID,
                            NEW_KEY,
                            OLD_KEY
                    )
            ).willReturn(0);

            assertThatThrownBy(
                    () -> accountLinkService.completeLink(
                            USER_ID,
                            NEW_KEY,
                            List.of(1L)
                    )
            )
                    .isInstanceOf(DomainException.class)
                    .extracting("errorCode")
                    .isEqualTo(
                            UserErrorCode.LINK_KEY_UPDATE_CONFLICT
                    );

            verify(userRepository)
                    .findUserKeyByUserId(USER_ID);

            verify(userRepository)
                    .updateUserKeyByUserId(
                            USER_ID,
                            NEW_KEY,
                            OLD_KEY
                    );

            verify(
                    linkedBankAccountService,
                    never()
            ).linkAccountsByIds(
                    anyLong(),
                    anyString(),
                    anyList()
            );
        }

        @Test
        @DisplayName("계좌 연동이 실패하면 예외가 그대로 전파된다")
        void propagatesExceptionWhenLinkAccountsFails() {
            given(
                    userRepository.findUserKeyByUserId(USER_ID)
            ).willReturn(OLD_KEY);

            given(
                    userRepository.updateUserKeyByUserId(
                            USER_ID,
                            NEW_KEY,
                            OLD_KEY
                    )
            ).willReturn(1);

            willThrow(
                    new RuntimeException("mock-bank 조회 실패")
            )
                    .given(linkedBankAccountService)
                    .linkAccountsByIds(
                            USER_ID,
                            NEW_KEY,
                            List.of(1L)
                    );

            assertThatThrownBy(
                    () -> accountLinkService.completeLink(
                            USER_ID,
                            NEW_KEY,
                            List.of(1L)
                    )
            )
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("mock-bank 조회 실패");

            verify(userRepository)
                    .findUserKeyByUserId(USER_ID);

            verify(userRepository)
                    .updateUserKeyByUserId(
                            USER_ID,
                            NEW_KEY,
                            OLD_KEY
                    );

            verify(linkedBankAccountService)
                    .linkAccountsByIds(
                            USER_ID,
                            NEW_KEY,
                            List.of(1L)
                    );
        }
    }
}