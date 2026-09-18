package org.teamsai.saibackend.domain.link;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.account.service.LinkedAccountWriter;
import org.teamsai.saibackend.domain.link.service.*;
import org.teamsai.saibackend.domain.user.repository.UserRepository;
import org.teamsai.saibackend.global.exception.DomainException;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class AccountLinkServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final LinkedAccountWriter writer = mock(LinkedAccountWriter.class);
    private final LinkOperationStore operations = mock(LinkOperationStore.class);
    private final AccountLinkService service = new AccountLinkService(users, writer, operations);
    @Test void usesOriginalSnapshotAndCompletesReceiptAfterWrites() {
        when(users.updateUserKeyByUserId(1L,"new","original")).thenReturn(1);
        service.completeLink(1L,"new","original",List.of(),"request");
        var order=inOrder(users,writer,operations);
        order.verify(users).updateUserKeyByUserId(1L,"new","original");
        order.verify(writer).insertAll(List.of());
        order.verify(operations).complete("request");
        verify(users,never()).findUserKeyByUserId(anyLong());
    }
    @Test void conflictDoesNotSaveAccountsOrReceipt() {
        assertThatThrownBy(()->service.completeLink(1L,"new","old",List.of(),"request"))
            .isInstanceOf(DomainException.class);
        verifyNoInteractions(writer,operations);
    }
    @Test void failedAccountWriteDoesNotCompleteReceipt() {
        when(users.updateUserKeyByUserId(1L,"new",null)).thenReturn(1);
        when(writer.insertAll(List.of())).thenThrow(new IllegalStateException("db"));
        assertThatThrownBy(()->service.completeLink(1L,"new",null,List.of(),"request"))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(operations);
    }
}
