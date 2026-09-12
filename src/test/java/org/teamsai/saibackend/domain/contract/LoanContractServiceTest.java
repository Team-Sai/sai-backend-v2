package org.teamsai.saibackend.domain.contract;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.multipart.MultipartFile;
import org.teamsai.saibackend.domain.contract.dto.request.ContractRelationType;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.dto.request.LoanContractRequest;
import org.teamsai.saibackend.domain.contract.dto.request.RepaymentMethod;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.entity.LoanContract;
import org.teamsai.saibackend.domain.contract.event.ContractCompletedEvent;
import org.teamsai.saibackend.domain.contract.event.ContractCreatedEvent;
import org.teamsai.saibackend.domain.contract.exception.LoanContractErrorCode;
import org.teamsai.saibackend.domain.contract.repository.LoanContractRepository;
import org.teamsai.saibackend.domain.contract.service.ContractAccountService;
import org.teamsai.saibackend.domain.contract.service.LoanContractFileService;
import org.teamsai.saibackend.domain.contract.service.LoanContractService;
import org.teamsai.saibackend.domain.identity.exception.IdentityErrorCode;
import org.teamsai.saibackend.domain.identity.service.IdentityService;
import org.teamsai.saibackend.domain.identity.type.IdentityPurpose;
import org.teamsai.saibackend.domain.notification.service.NotificationService;
import org.teamsai.saibackend.domain.user.dto.UserDTO;
import org.teamsai.saibackend.domain.user.dto.response.UserResponse;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.exception.UserErrorCode;
import org.teamsai.saibackend.domain.user.service.UserService;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LoanContractService 단위 테스트")
class LoanContractServiceTest {

    private static final Long CREDITOR_ID = 1L;
    private static final Long DEBTOR_ID = 2L;
    private static final Long OTHER_USER_ID = 999L;
    private static final String DEBTOR_USER_TOKEN = "SAI_ABCD1234";
    private static final Long CONTRACT_ID = 1L;
    private static final String IDENTITY_VERIFICATION_ID = "identity-verification-abc123";

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private LoanContractRepository contractRepository;

    @Mock
    private LoanContractFileService fileService;

    @Mock
    private ContractAccountService contractAccountService;

    @Mock
    private UserService userService;

    @Mock
    private IdentityService identityService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private LoanContractService loanContractService;

    @Nested
    @DisplayName("차용증 최초 생성")
    class CreateContract {

        @Test
        @DisplayName("채권자 본인 확인 후 계약서를 생성한다")
        void createContractSuccess() {
            LoanContractRequest request = createRequest();
            given(contractRepository.save(any(LoanContract.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            loanContractService.createContract(request, CREDITOR_ID);

            verify(identityService).consume(
                    CREDITOR_ID, IDENTITY_VERIFICATION_ID, IdentityPurpose.LOAN_CONTRACT
            );
            verify(userService).getMyInfo(CREDITOR_ID);
            verify(contractRepository).save(any(LoanContract.class));
            verify(eventPublisher).publishEvent(any(ContractCreatedEvent.class));
        }

        @Test
        @DisplayName("요청에 담긴 relationType을 그대로 엔티티에 담아 저장한다")
        void createContractPassesRelationTypeToRepository() {
            LoanContractRequest request = createRequest();
            request.setRelationType(ContractRelationType.ACQUAINTANCE);
            given(contractRepository.save(any(LoanContract.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            loanContractService.createContract(request, CREDITOR_ID);

            ArgumentCaptor<LoanContract> captor = ArgumentCaptor.forClass(LoanContract.class);
            verify(contractRepository).save(captor.capture());
            assertThat(captor.getValue().getRelationType()).isEqualTo(ContractRelationType.ACQUAINTANCE);
        }

        @Test
        @DisplayName("저장된 계약서의 contractId를 그대로 반환한다")
        void createContractReturnsGeneratedId() {
            LoanContractRequest request = createRequest();
            given(contractRepository.save(any(LoanContract.class)))
                    .willReturn(LoanContract.builder().contractId(100L).build());

            Long contractId = loanContractService.createContract(request, CREDITOR_ID);

            assertThat(contractId).isEqualTo(100L);
        }

        @Test
        @DisplayName("채권자를 찾을 수 없으면 예외가 발생하고 계약서를 생성하지 않는다")
        void createContractFailsWhenCreditorNotFound() {
            LoanContractRequest request = createRequest();

            willThrow(UserErrorCode.USER_NOT_FOUND.toException())
                    .given(userService)
                    .getMyInfo(CREDITOR_ID);

            assertThatThrownBy(() -> loanContractService.createContract(request, CREDITOR_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(UserErrorCode.USER_NOT_FOUND)
                    );

            verify(contractRepository, never()).save(any());
        }

        @Test
        @DisplayName("본인인증을 완료하지 않았으면 예외가 발생하고 계약서를 생성하지 않는다")
        void createContractFailsWhenIdentityNotVerified() {
            LoanContractRequest request = createRequest();

            willThrow(IdentityErrorCode.IDENTITY_VERIFICATION_CONSUME_FAILED.toException())
                    .given(identityService)
                    .consume(CREDITOR_ID, IDENTITY_VERIFICATION_ID, IdentityPurpose.LOAN_CONTRACT);

            assertThatThrownBy(() -> loanContractService.createContract(request, CREDITOR_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(IdentityErrorCode.IDENTITY_VERIFICATION_CONSUME_FAILED)
                    );

            verify(userService, never()).getMyInfo(any());
            verify(contractRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("채무자 계약 합류")
    class LinkDebtor {

        @Test
        @DisplayName("로그인한 사용자를 본인인증 없이 채무자로 계약에 연결한다")
        void linkDebtorSuccess() {
            LoanContract contract = contractWithoutDebtor();
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract));
            given(entityManager.getReference(User.class, DEBTOR_ID)).willReturn(userRef(DEBTOR_ID));

            loanContractService.linkDebtor(CONTRACT_ID, DEBTOR_ID);

            verify(identityService, never()).consume(any(), any(), any());
            verify(userService).getMyInfo(DEBTOR_ID);
            assertThat(contract.getDebtor()).isNotNull();
            assertThat(contract.getDebtor().getUserId()).isEqualTo(DEBTOR_ID);
        }

        @Test
        @DisplayName("계약서를 찾을 수 없으면 예외가 발생하고 연결하지 않는다")
        void linkDebtorFailsWhenContractNotFound() {
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> loanContractService.linkDebtor(CONTRACT_ID, DEBTOR_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_NOT_FOUND)
                    );
        }

        @Test
        @DisplayName("이미 채무자가 연결되어 있으면 예외가 발생하고 연결하지 않는다")
        void linkDebtorFailsWhenAlreadyLinked() {
            LoanContract contract = contractWithParties();
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract));

            assertThatThrownBy(() -> loanContractService.linkDebtor(CONTRACT_ID, OTHER_USER_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_ACCESS_DENIED)
                    );

            assertThat(contract.getDebtor().getUserId()).isEqualTo(DEBTOR_ID);
        }

        @Test
        @DisplayName("채권자 본인이 채무자로 합류할 수 없다")
        void linkDebtorFailsWhenCreditorTriesToJoinAsDebtor() {
            LoanContract contract = contractWithoutDebtor();
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract));

            assertThatThrownBy(() -> loanContractService.linkDebtor(CONTRACT_ID, CREDITOR_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CANNOT_CREATE_CONTRACT_TO_SELF)
                    );

            assertThat(contract.getDebtor()).isNull();
        }
    }

    @Nested
    @DisplayName("채권자 전자서명 제출")
    class SubmitCreditorSignature {

        @Test
        @DisplayName("서명 파일을 저장하고 상태를 PENDING으로 변경한다")
        void submitCreditorSignatureSuccess() {
            MultipartFile signature = mock(MultipartFile.class);
            LoanContract contract = contractWithoutDebtor();
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract));
            given(fileService.saveSignatureFile(CONTRACT_ID, signature))
                    .willReturn("uploads/signatures/1_signature.png");
            given(userService.findRequestTarget(CREDITOR_ID, DEBTOR_USER_TOKEN))
                    .willReturn(UserDTO.builder().userId(DEBTOR_ID).build());
            given(userService.getMyInfo(CREDITOR_ID))
                    .willReturn(UserResponse.builder().name("채권자").build());
            given(entityManager.getReference(User.class, DEBTOR_ID)).willReturn(userRef(DEBTOR_ID));

            ContractStatus status = loanContractService.submitCreditorSignature(
                    CONTRACT_ID, CREDITOR_ID, DEBTOR_USER_TOKEN, signature
            );

            assertThat(contract.getCreditorSignature()).isEqualTo("uploads/signatures/1_signature.png");
            assertThat(contract.getDebtor().getUserId()).isEqualTo(DEBTOR_ID);
            assertThat(contract.getStatus()).isEqualTo(ContractStatus.PENDING);
            assertThat(status).isEqualTo(ContractStatus.PENDING);
        }

        @Test
        @DisplayName("계약 당사자가 아닌 사용자가 제출하면 예외가 발생하고 서명이 저장되지 않는다")
        void submitCreditorSignatureFailsWhenUserIsNotCreditor() {
            MultipartFile signature = mock(MultipartFile.class);
            LoanContract contract = contractWithoutDebtor();
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract));

            assertThatThrownBy(() ->
                    loanContractService.submitCreditorSignature(CONTRACT_ID, OTHER_USER_ID, DEBTOR_USER_TOKEN, signature)
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_ACCESS_DENIED)
                    );

            verify(fileService, never()).saveSignatureFile(any(), any());
            assertThat(contract.getCreditorSignature()).isNull();
        }

        @Test
        @DisplayName("계약서를 찾을 수 없으면 예외가 발생하고 서명이 저장되지 않는다")
        void submitCreditorSignatureFailsWhenContractNotFound() {
            MultipartFile signature = mock(MultipartFile.class);
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    loanContractService.submitCreditorSignature(CONTRACT_ID, CREDITOR_ID, DEBTOR_USER_TOKEN, signature)
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_NOT_FOUND)
                    );

            verify(fileService, never()).saveSignatureFile(any(), any());
        }
    }

    @Nested
    @DisplayName("채무자 전자서명 제출")
    class SubmitDebtorSignature {

        @Test
        @DisplayName("본인인증을 소비한 뒤 서명 파일과 주소를 저장하고 상태를 COMPLETED로 변경한다")
        void submitDebtorSignatureSuccess() {
            MultipartFile signature = mock(MultipartFile.class);
            String debtorAddress = "서울특별시 마포구 월드컵로 1";
            LoanContract contract = contractWithParties();
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract));
            given(fileService.saveSignatureFile(CONTRACT_ID, signature))
                    .willReturn("uploads/signatures/1_signature.png");
            given(userService.getMyInfo(CREDITOR_ID))
                    .willReturn(UserResponse.builder().name("김채권").birthDate(LocalDate.of(1995, 5, 5)).build());
            given(userService.getMyInfo(DEBTOR_ID))
                    .willReturn(UserResponse.builder().name("이채무").birthDate(LocalDate.of(1996, 6, 6)).build());

            ContractStatus status = loanContractService.submitDebtorSignature(
                    CONTRACT_ID, DEBTOR_ID, debtorAddress, signature, IDENTITY_VERIFICATION_ID
            );

            verify(identityService).consume(
                    DEBTOR_ID, IDENTITY_VERIFICATION_ID, IdentityPurpose.LOAN_CONTRACT
            );
            assertThat(contract.getDebtorAddress()).isEqualTo(debtorAddress);
            assertThat(contract.getDebtorSignature()).isEqualTo("uploads/signatures/1_signature.png");
            assertThat(contract.getStatus()).isEqualTo(ContractStatus.COMPLETED);
            // PDF 아카이빙은 ContractArchiveEventListener가 ContractCompletedEvent를 구독해 비동기로 처리한다.
            verify(eventPublisher).publishEvent(any(ContractCompletedEvent.class));
            assertThat(status).isEqualTo(ContractStatus.COMPLETED);
        }

        @Test
        @DisplayName("본인인증을 완료하지 않았으면 예외가 발생하고 서명이 저장되지 않는다")
        void submitDebtorSignatureFailsWhenIdentityNotVerified() {
            MultipartFile signature = mock(MultipartFile.class);
            LoanContract contract = contractWithParties();
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract));

            willThrow(IdentityErrorCode.IDENTITY_VERIFICATION_CONSUME_FAILED.toException())
                    .given(identityService)
                    .consume(DEBTOR_ID, IDENTITY_VERIFICATION_ID, IdentityPurpose.LOAN_CONTRACT);

            assertThatThrownBy(() ->
                    loanContractService.submitDebtorSignature(
                            CONTRACT_ID, DEBTOR_ID, "서울특별시 마포구 월드컵로 1", signature, IDENTITY_VERIFICATION_ID
                    )
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(IdentityErrorCode.IDENTITY_VERIFICATION_CONSUME_FAILED)
                    );

            verify(fileService, never()).saveSignatureFile(any(), any());
            assertThat(contract.getDebtorSignature()).isNull();
        }

        @Test
        @DisplayName("채무자 주소가 빈 문자열이면 예외가 발생하고 저장되지 않는다")
        void submitDebtorSignatureFailsWhenAddressIsBlank() {
            MultipartFile signature = mock(MultipartFile.class);

            assertThatThrownBy(() ->
                    loanContractService.submitDebtorSignature(
                            CONTRACT_ID, DEBTOR_ID, "", signature, IDENTITY_VERIFICATION_ID
                    )
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.DEBTOR_ADDRESS_REQUIRED)
                    );

            verify(fileService, never()).saveSignatureFile(any(), any());
        }

        @Test
        @DisplayName("채무자 주소가 null이면 예외가 발생하고 저장되지 않는다")
        void submitDebtorSignatureFailsWhenAddressIsNull() {
            MultipartFile signature = mock(MultipartFile.class);

            assertThatThrownBy(() ->
                    loanContractService.submitDebtorSignature(
                            CONTRACT_ID, DEBTOR_ID, null, signature, IDENTITY_VERIFICATION_ID
                    )
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.DEBTOR_ADDRESS_REQUIRED)
                    );

            verify(fileService, never()).saveSignatureFile(any(), any());
        }

        @Test
        @DisplayName("계약 당사자가 아닌 사용자가 제출하면 예외가 발생하고 서명이 저장되지 않는다")
        void submitDebtorSignatureFailsWhenUserIsNotDebtor() {
            MultipartFile signature = mock(MultipartFile.class);
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contractWithParties()));

            assertThatThrownBy(() ->
                    loanContractService.submitDebtorSignature(
                            CONTRACT_ID, OTHER_USER_ID, "아무 주소", signature, IDENTITY_VERIFICATION_ID
                    )
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_ACCESS_DENIED)
                    );

            verify(fileService, never()).saveSignatureFile(any(), any());
        }

        @Test
        @DisplayName("계약서를 찾을 수 없으면 예외가 발생하고 서명이 저장되지 않는다")
        void submitDebtorSignatureFailsWhenContractNotFound() {
            MultipartFile signature = mock(MultipartFile.class);
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    loanContractService.submitDebtorSignature(
                            CONTRACT_ID, DEBTOR_ID, "아무 주소", signature, IDENTITY_VERIFICATION_ID
                    )
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_NOT_FOUND)
                    );

            verify(fileService, never()).saveSignatureFile(any(), any());
        }
    }

    @Nested
    @DisplayName("차용증 상세 조회")
    class FindContract {

        @Test
        @DisplayName("채권자가 조회하면 응답 DTO를 반환한다")
        void findContractSuccessAsCreditor() {
            LoanContract contract = contractWithParties();
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract));
            given(userService.getMyInfo(CREDITOR_ID)).willReturn(
                    UserResponse.builder().name("김채권").birthDate(LocalDate.of(1995, 5, 5)).build()
            );
            given(userService.getMyInfo(DEBTOR_ID)).willReturn(
                    UserResponse.builder().name("이채무").birthDate(LocalDate.of(1996, 6, 6)).build()
            );

            LoanContractResponse result = loanContractService.findContract(CONTRACT_ID, CREDITOR_ID);

            assertThat(result.getContractId()).isEqualTo(CONTRACT_ID);
            assertThat(result.getCreditorName()).isEqualTo("김채권");
            assertThat(result.getDebtorName()).isEqualTo("이채무");
            assertThat(result.getStatus()).isEqualTo(ContractStatus.DRAFT);
        }

        @Test
        @DisplayName("채무자가 조회하면 응답 DTO를 반환한다")
        void findContractSuccessAsDebtor() {
            LoanContract contract = contractWithParties();
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contract));
            given(userService.getMyInfo(CREDITOR_ID)).willReturn(
                    UserResponse.builder().name("김채권").birthDate(LocalDate.of(1995, 5, 5)).build()
            );
            given(userService.getMyInfo(DEBTOR_ID)).willReturn(
                    UserResponse.builder().name("이채무").birthDate(LocalDate.of(1996, 6, 6)).build()
            );

            LoanContractResponse result = loanContractService.findContract(CONTRACT_ID, DEBTOR_ID);

            assertThat(result.getContractId()).isEqualTo(CONTRACT_ID);
            assertThat(result.getCreditorName()).isEqualTo("김채권");
            assertThat(result.getDebtorName()).isEqualTo("이채무");
        }

        @Test
        @DisplayName("계약서를 찾을 수 없으면 예외가 발생한다")
        void findContractFailsWhenNotFound() {
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> loanContractService.findContract(CONTRACT_ID, CREDITOR_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_NOT_FOUND)
                    );
        }

        @Test
        @DisplayName("계약 당사자가 아닌 사용자가 조회하면 예외가 발생한다")
        void findContractFailsWhenUserIsNotParty() {
            given(contractRepository.findById(CONTRACT_ID)).willReturn(Optional.of(contractWithParties()));

            assertThatThrownBy(() -> loanContractService.findContract(CONTRACT_ID, OTHER_USER_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_ACCESS_DENIED)
                    );
        }
    }

    private LoanContractRequest createRequest() {
        return LoanContractRequest.builder()
                .identityVerificationId(IDENTITY_VERIFICATION_ID)
                .relationType(ContractRelationType.FAMILY)
                .principalAmount(BigDecimal.valueOf(1_000_000))
                .interestRate(BigDecimal.valueOf(5.0))
                .repaymentType(RepaymentMethod.EQUAL_PRINCIPAL_AND_INTEREST)
                .startDate(LocalDate.of(2026, 1, 1))
                .maturityDate(LocalDate.of(2027, 1, 1))
                .repaymentDay(15)
                .creditorAddress("서울특별시 강남구 테헤란로 123")
                .contractAlias("생활비 차용")
                .terms(null)
                .build();
    }

    private User userRef(Long userId) {
        return User.builder().userId(userId).build();
    }

    private LoanContract contractWithoutDebtor() {
        return LoanContract.builder()
                .contractId(CONTRACT_ID)
                .creditor(userRef(CREDITOR_ID))
                .relationType(ContractRelationType.FAMILY)
                .creditorAddress("서울특별시 강남구 테헤란로 123")
                .principalAmount(BigDecimal.valueOf(1_000_000))
                .interestRate(BigDecimal.valueOf(5.0))
                .repaymentType(RepaymentMethod.EQUAL_PRINCIPAL_AND_INTEREST)
                .startDate(LocalDate.of(2026, 1, 1))
                .maturityDate(LocalDate.of(2027, 1, 1))
                .repaymentDay(15)
                .contractAlias("생활비 차용")
                .status(ContractStatus.DRAFT)
                .build();
    }

    private LoanContract contractWithParties() {
        return LoanContract.builder()
                .contractId(CONTRACT_ID)
                .creditor(userRef(CREDITOR_ID))
                .debtor(userRef(DEBTOR_ID))
                .relationType(ContractRelationType.FAMILY)
                .creditorAddress("서울특별시 강남구 테헤란로 123")
                .principalAmount(BigDecimal.valueOf(1_000_000))
                .interestRate(BigDecimal.valueOf(5.0))
                .repaymentType(RepaymentMethod.EQUAL_PRINCIPAL_AND_INTEREST)
                .startDate(LocalDate.of(2026, 1, 1))
                .maturityDate(LocalDate.of(2027, 1, 1))
                .repaymentDay(15)
                .contractAlias("생활비 차용")
                .status(ContractStatus.DRAFT)
                .build();
    }
}
