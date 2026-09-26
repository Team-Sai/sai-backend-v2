package org.teamsai.saibackend.domain.account;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.account.service.*;
import org.teamsai.saibackend.domain.account.repository.LinkedBankAccountRepository;
import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;
import org.teamsai.saibackend.domain.account.dto.request.LinkAccountRequest;
import org.teamsai.saibackend.domain.account.dto.response.AccountDetailResponse;
import org.teamsai.saibackend.domain.account.dto.type.ConnectionStatus;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.user.service.UserService;
import org.teamsai.saibackend.global.client.MockBankClient;
import org.springframework.web.client.RestClientException;
import java.math.BigDecimal;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class LinkedBankAccountServiceTest {
 final LinkedBankAccountRepository repository=mock(LinkedBankAccountRepository.class);
 final UserService users=mock(UserService.class);
 final MockBankClient bank=mock(MockBankClient.class);
 final LinkedAccountWriter writer=mock(LinkedAccountWriter.class);
 final LinkedBankAccountService service=new LinkedBankAccountService(repository,users,bank,mock(EntityManager.class),writer);
 AccountDetailResponse detail(Long id){return new AccountDetailResponse(id,"088","masked","name","holder",BigDecimal.TEN,"ACTIVE",null,null);}
 @Test void unchangedCursorIsAcceptedWhenAccountExists(){
  when(repository.advanceCursorAndBalance(1L, 11L, BigDecimal.TEN)).thenReturn(0);
  when(repository.existsById(1L)).thenReturn(true);
  service.advanceTransactionCursor(1L, 11L, BigDecimal.TEN);
  verify(repository).existsById(1L);
 }
 @Test void unchangedCursorIsRejectedWhenAccountWasDeleted(){
  when(repository.advanceCursorAndBalance(1L, 11L, BigDecimal.TEN)).thenReturn(0);
  when(repository.existsById(1L)).thenReturn(false);
  assertThatThrownBy(() -> service.advanceTransactionCursor(1L, 11L, BigDecimal.TEN))
   .extracting("errorCode").isEqualTo(AccountErrorCode.LINKED_ACCOUNT_NOT_FOUND);
 }
 LinkAccountRequest request(Long... ids){return new LinkAccountRequest(java.util.Arrays.stream(ids).map(id->new LinkAccountRequest.SelectedAccount(id,"alias")).toList());}
 @Test void deduplicatesSelectionAndExcludesExistingBeforeBankCalls(){
  when(users.getUserKeyByUserId(1L)).thenReturn("key");
  when(repository.findAllByUserId(1L)).thenReturn(List.of(LinkedBankAccount.builder().accountId(2L).build()));
  when(bank.getAccountDetail(1L,"key")).thenReturn(detail(1L));
  service.linkSelectedAccounts(1L,request(1L,1L,2L));
  verify(bank,times(1)).getAccountDetail(1L,"key");
  verify(bank,never()).getAccountDetail(eq(2L),any());
  verify(writer).insertAll(argThat(list->list.size()==1 && list.get(0).getAccountAlias().equals("alias")));
 }
 @Test void preparesCallbackAccountsWithBankNameAndDistinctIds(){
  when(bank.getAccountDetail(1L,"key")).thenReturn(detail(1L));
  var result=service.prepareAccountsByIds(1L,"key",List.of(1L,1L));
  assertThat(result).hasSize(1); assertThat(result.get(0).getAccountAlias()).isEqualTo("name");
  verify(bank,times(1)).getAccountDetail(1L,"key"); verifyNoInteractions(writer);
 }
 @Test void nullBankResponseIsDomainErrorInsteadOfNullPointer(){
  assertThatThrownBy(()->service.prepareAccountsByIds(1L,"key",List.of(1L)))
   .extracting("errorCode").isEqualTo(AccountErrorCode.INVALID_BANK_RESPONSE);
  verifyNoInteractions(writer);
 }
 @Test void wrongAccountInResponseIsRejected(){
  when(bank.getAccountDetail(1L,"key")).thenReturn(detail(99L));
  assertThatThrownBy(()->service.prepareAccountsByIds(1L,"key",List.of(1L)))
   .extracting("errorCode").isEqualTo(AccountErrorCode.INVALID_BANK_RESPONSE);
 }
 @Test void bankFailureDoesNotPersistEarlierCandidates(){
  when(users.getUserKeyByUserId(1L)).thenReturn("key");
  when(bank.getAccountDetail(1L,"key")).thenReturn(detail(1L));
  when(bank.getAccountDetail(2L,"key")).thenThrow(new RestClientException("offline"));
  assertThatThrownBy(()->service.linkSelectedAccounts(1L,request(1L,2L)))
   .extracting("errorCode").isEqualTo(AccountErrorCode.BANK_SERVER_UNAVAILABLE);
  verifyNoInteractions(writer);
 }
 @Test void unavailableAccountIsNotOwnedForUse(){
  when(repository.findAllByUserId(1L)).thenReturn(List.of(LinkedBankAccount.builder()
    .linkedAccountId(5L).connectionStatus(ConnectionStatus.UNAVAILABLE).build()));
  assertThat(service.isOwnedLinkedAccount(1L,5L)).isFalse();
  assertThat(service.isOwnedLinkedAccount(null,5L)).isFalse();
 }
 @Test void nullUserAndEmptyAccountsReturnEmptyResults(){
  assertThat(service.getLinkedAccounts(null)).isEmpty();
  assertThat(service.getLinkedAccounts(1L)).isEmpty();
  assertThat(service.getLinkedAccountIds(null)).isEmpty();
 }
}
