package org.teamsai.saibackend.domain.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.account.dto.SyncAccountContext;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.account.repository.LinkedBankAccountRepository;
import org.teamsai.saibackend.domain.account.repository.SyncAccountProjection;
import org.teamsai.saibackend.domain.account.service.SyncAccountReadService;
import org.teamsai.saibackend.global.exception.DomainException;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncAccountReadServiceTest {
    @Mock LinkedBankAccountRepository repository;
    @InjectMocks SyncAccountReadService service;

    @Test void copiesValuesIntoDtoWithoutExposingProjection() {
        var projection = mock(SyncAccountProjection.class);
        when(repository.findSyncAccountContext(1L)).thenReturn(Optional.of(projection));
        when(projection.getUserId()).thenReturn(10L);
        when(projection.getBankAccountId()).thenReturn(30L);
        when(projection.getUserKey()).thenReturn("test-key");
        when(projection.getLastSyncedTransactionId()).thenReturn(5L);
        assertThat(service.getContext(1L)).isEqualTo(new SyncAccountContext(10L,30L,"test-key",5L));
        verify(repository).findSyncAccountContext(1L);
        verifyNoMoreInteractions(repository);
    }

    @Test void preservesNullableKeyAndCursor() {
        var projection = mock(SyncAccountProjection.class);
        when(repository.findSyncAccountContext(1L)).thenReturn(Optional.of(projection));
        when(projection.getUserId()).thenReturn(10L);
        when(projection.getBankAccountId()).thenReturn(30L);
        when(projection.getLastSyncedTransactionId()).thenReturn(null);
        assertThat(service.getContext(1L)).isEqualTo(new SyncAccountContext(10L,30L,null,null));
    }

    @Test void preservesMissingAccountError() {
        when(repository.findSyncAccountContext(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getContext(1L))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(AccountErrorCode.LINKED_ACCOUNT_NOT_FOUND));
    }
}
