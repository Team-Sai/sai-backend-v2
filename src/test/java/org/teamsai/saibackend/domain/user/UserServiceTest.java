package org.teamsai.saibackend.domain.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.user.dto.response.UserResponse;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.exception.UserErrorCode;
import org.teamsai.saibackend.domain.user.repository.UserRepository;
import org.teamsai.saibackend.domain.user.service.UserService;
import org.teamsai.saibackend.global.exception.DomainException;

import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Supplier;
import org.teamsai.saibackend.domain.link.service.UserLinkLock;
import org.teamsai.saibackend.domain.link.service.LinkOperationStore;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService 단위 테스트")
class UserServiceTest {

    private static final Long USER_ID = 1L;
    private static final String USER_TOKEN = "SAI-ABCDEFGH";
    private static final String USER_KEY = "mock-bank-user-key";

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserLinkLock userLinkLock;

    @Mock
    private LinkOperationStore linkOperationStore;

    @InjectMocks
    private UserService userService;

    @Nested
    @DisplayName("내 정보 조회")
    class GetMyInfo {

        @Test
        @DisplayName("사용자 ID로 회원을 조회해 응답 DTO로 반환한다")
        void getMyInfoSuccess() {
            given(userRepository.findById(USER_ID))
                    .willReturn(
                            Optional.of(createUser())
                    );

            UserResponse response =
                    userService.getMyInfo(USER_ID);

            assertThat(response.getUserToken())
                    .isEqualTo(USER_TOKEN);

            assertThat(response.getEmail())
                    .isEqualTo("user@example.com");

            assertThat(response.getName())
                    .isEqualTo("김사이");

            assertThat(response.getBirthDate())
                    .isEqualTo(
                            LocalDate.of(
                                    2002,
                                    10,
                                    22
                            )
                    );

            verify(userRepository)
                    .findById(USER_ID);
        }

        @Test
        @DisplayName("사용자 ID에 해당하는 회원이 없으면 예외가 발생한다")
        void getMyInfoFailsWhenUserDoesNotExist() {
            given(userRepository.findById(USER_ID))
                    .willReturn(Optional.empty());

            assertUserNotFound(
                    () -> userService.getMyInfo(USER_ID)
            );

            verify(userRepository)
                    .findById(USER_ID);
        }
    }

    @Nested
    @DisplayName("회원 탈퇴")
    class Withdraw {

        void executeLockedAction() {
            doAnswer(invocation -> {
                Supplier<?> action = invocation.getArgument(1);
                return action.get();
            }).when(userLinkLock).execute(eq(USER_ID), any());
        }

        @Test
        void unresolvedOperationPreventsWithdrawal() {
            executeLockedAction();
            given(linkOperationStore.hasUnresolved(USER_ID)).willReturn(true);

            assertThatThrownBy(() -> userService.withdraw(USER_ID))
                    .extracting("errorCode")
                    .isEqualTo(AccountErrorCode.LINK_RECONCILIATION_REQUIRED);

            verifyNoInteractions(userRepository);
        }

        @Test
        void lockFailurePreventsWithdrawal() {
            doThrow(AccountErrorCode.LINK_IN_PROGRESS.toException())
                    .when(userLinkLock).execute(eq(USER_ID), any());

            assertThatThrownBy(() -> userService.withdraw(USER_ID))
                    .extracting("errorCode")
                    .isEqualTo(AccountErrorCode.LINK_IN_PROGRESS);

            verifyNoInteractions(userRepository, linkOperationStore);
        }

        @Test
        @DisplayName("사용자 ID로 회원을 조회한 후 삭제한다")
        void withdrawSuccess() {
            executeLockedAction();
            User user = createUser();

            given(userRepository.findById(USER_ID))
                    .willReturn(Optional.of(user));

            userService.withdraw(USER_ID);

            verify(userRepository)
                    .findById(USER_ID);

            verify(userRepository)
                    .delete(user);
        }

        @Test
        @DisplayName("회원이 존재하지 않으면 회원 없음 예외가 발생한다")
        void withdrawFailsWhenUserDoesNotExist() {
            executeLockedAction();
            given(userRepository.findById(USER_ID))
                    .willReturn(Optional.empty());

            assertUserNotFound(
                    () -> userService.withdraw(USER_ID)
            );

            verify(userRepository)
                    .findById(USER_ID);

            verify(userRepository, never())
                    .delete(
                            org.mockito.ArgumentMatchers
                                    .any(User.class)
                    );
        }
    }

    @Nested
    @DisplayName("요청 대상 회원 조회")
    class FindRequestTarget {

        @Test
        @DisplayName("회원토큰으로 요청 대상 회원을 조회한다")
        void findRequestTargetSuccess() {
            Long requesterUserId = 1L;
            String userToken =
                    "USER-ABCD1234";

            User targetUser =
                    User.builder()
                            .userId(2L)
                            .userToken(userToken)
                            .name("김사이")
                            .build();

            given(
                    userRepository
                            .findByUserToken(userToken)
            ).willReturn(
                    Optional.of(targetUser)
            );

            User result =
                    userService.findRequestTarget(
                            requesterUserId,
                            userToken
                    );

            assertThat(result.getUserId())
                    .isEqualTo(2L);

            assertThat(result.getUserToken())
                    .isEqualTo(userToken);

            assertThat(result.getName())
                    .isEqualTo("김사이");

            verify(userRepository)
                    .findByUserToken(userToken);
        }

        @Test
        @DisplayName("회원토큰과 일치하는 회원이 없으면 예외가 발생한다")
        void findRequestTargetNotFound() {
            Long requesterUserId = 1L;
            String userToken =
                    "UNKNOWN-TOKEN";

            given(
                    userRepository
                            .findByUserToken(userToken)
            ).willReturn(
                    Optional.empty()
            );

            assertUserNotFound(
                    () ->
                            userService.findRequestTarget(
                                    requesterUserId,
                                    userToken
                            )
            );

            verify(userRepository)
                    .findByUserToken(userToken);
        }

        @Test
        @DisplayName("본인의 회원토큰을 요청 대상으로 선택하면 예외가 발생한다")
        void findRequestTargetSelf() {
            Long requesterUserId = 1L;
            String userToken =
                    "USER-MY-TOKEN";

            User requester =
                    User.builder()
                            .userId(requesterUserId)
                            .userToken(userToken)
                            .name("김사이")
                            .build();

            given(
                    userRepository
                            .findByUserToken(userToken)
            ).willReturn(
                    Optional.of(requester)
            );

            assertThatThrownBy(
                    () ->
                            userService.findRequestTarget(
                                    requesterUserId,
                                    userToken
                            )
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception ->
                                    assertThat(
                                            exception.getErrorCode()
                                    ).isEqualTo(
                                            UserErrorCode
                                                    .CANNOT_SELECT_SELF
                                    )
                    );

            verify(userRepository)
                    .findByUserToken(userToken);
        }
    }

    private void assertUserNotFound(
            Runnable operation
    ) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        DomainException.class,
                        exception ->
                                assertThat(
                                        exception.getErrorCode()
                                ).isEqualTo(
                                        UserErrorCode.USER_NOT_FOUND
                                )
                );
    }

    private User createUser() {
        return User.builder()
                .userId(USER_ID)
                .userToken(USER_TOKEN)
                .userKey(USER_KEY)
                .email("user@example.com")
                .password("encoded-password")
                .name("김사이")
                .birthDate(
                        LocalDate.of(
                                2002,
                                10,
                                22
                        )
                )
                .build();
    }
}
