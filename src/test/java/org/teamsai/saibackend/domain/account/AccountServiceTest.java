package org.teamsai.saibackend.domain.account;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.account.service.AccountService;
import org.teamsai.saibackend.domain.link.service.AccountLinkCoordinator;
import org.teamsai.saibackend.domain.link.dto.response.UserKeyResponse;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class AccountServiceTest {
    @Test void delegatesToSharedKeyChangeProtocol() {
        var coordinator = mock(AccountLinkCoordinator.class);
        var response = new UserKeyResponse("key");
        when(coordinator.issueOrGetUserKey(1L)).thenReturn(response);
        assertThat(new AccountService(coordinator).issueOrGetUserKey(1L)).isSameAs(response);
    }
}
