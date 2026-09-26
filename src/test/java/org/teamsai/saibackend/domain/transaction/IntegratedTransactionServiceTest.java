package org.teamsai.saibackend.domain.transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.transaction.repository.BankTransactionQueryRepository;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionQueryService;
import org.teamsai.saibackend.domain.transaction.dto.request.BankTransactionSearchCondition;
import org.teamsai.saibackend.global.exception.DomainException;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.teamsai.saibackend.domain.account.dto.type.ConnectionStatus.*;
import static org.teamsai.saibackend.domain.transaction.type.BankTransactionType.*;
import static org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus.*;

@ExtendWith(MockitoExtension.class)
class IntegratedTransactionServiceTest {
    @Mock BankTransactionQueryRepository transactionQueries;
    @Mock LinkedBankAccountService accounts;
    @InjectMocks BankTransactionQueryService service;

    @Test void passesNormalizedFiltersAndInclusiveDateBounds() {
        var date = LocalDate.of(2026, 9, 21);
        when(transactionQueries.searchIntegrated(1L, AVAILABLE, null, PENDING, DEPOSIT, "메모",
                date.atStartOfDay(), date.plusDays(1).atStartOfDay(), PageRequest.of(0, 20)))
                .thenReturn(Page.empty());
        var result = service.getIntegratedTransactions(1L, null,
                new BankTransactionSearchCondition(PENDING, DEPOSIT, " 메모 ", date, date, 0, 20));
        assertThat(result.content()).isEmpty();
        assertThat(result.totalCount()).isZero();
        verifyNoInteractions(accounts);
    }

    @Test void rejectsOtherUsersAccountBeforeQuery() {
        when(accounts.getLinkedAccount(2L)).thenReturn(
                LinkedBankAccount.builder().userId(9L).build());
        assertThatThrownBy(() -> service.getIntegratedTransactions(1L, 2L, condition()))
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.getHttpStatus().value()).isEqualTo(403));
        verifyNoInteractions(transactionQueries);
    }

    @Test void rejectsMissingAccountBeforeQuery() {
        when(accounts.getLinkedAccount(2L)).thenThrow(AccountErrorCode.LINKED_ACCOUNT_NOT_FOUND.toException());
        assertThatThrownBy(() -> service.getIntegratedTransactions(1L, 2L, condition()))
                .isInstanceOfSatisfying(DomainException.class,
                        ex -> assertThat(ex.getHttpStatus().value()).isEqualTo(404));
        verifyNoInteractions(transactionQueries);
    }

    @Test void unavailableOwnedAccountStillUsesAvailableQueryScope() {
        when(accounts.getLinkedAccount(2L)).thenReturn(
                LinkedBankAccount.builder().userId(1L).connectionStatus(UNAVAILABLE).build());
        when(transactionQueries.searchIntegrated(eq(1L), eq(AVAILABLE), eq(2L), isNull(), isNull(),
                isNull(), isNull(), isNull(), any())).thenReturn(Page.empty());
        assertThat(service.getIntegratedTransactions(1L, 2L, condition()).content()).isEmpty();
    }

    private BankTransactionSearchCondition condition() {
        return new BankTransactionSearchCondition(null, null, null, null, null, 0, 20);
    }
}
