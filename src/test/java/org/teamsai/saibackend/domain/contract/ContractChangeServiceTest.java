package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import org.teamsai.saibackend.domain.contract.dto.request.ContractChangeRequest;
import org.teamsai.saibackend.domain.contract.type.ContractStatus;
import org.teamsai.saibackend.domain.contract.type.RepaymentMethod;
import org.teamsai.saibackend.domain.contract.dto.ChangedLoanContractDTO;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.entity.LoanContractChangeRequest;
import org.teamsai.saibackend.domain.contract.exception.ContractChangeErrorCode;
import org.teamsai.saibackend.domain.contract.repository.ContractChangeRepository;
import org.teamsai.saibackend.domain.contract.service.*;
import org.teamsai.saibackend.domain.contract.type.ChangeRequestStatus;
import org.teamsai.saibackend.domain.identity.service.IdentityService;
import org.teamsai.saibackend.domain.identity.type.IdentityPurpose;
import org.teamsai.saibackend.domain.notification.service.NotificationService;
import org.teamsai.saibackend.domain.notification.type.NotificationType;
import org.teamsai.saibackend.domain.user.dto.response.UserResponse;
import org.teamsai.saibackend.domain.user.service.UserService;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractChangeService 단위 테스트")
class ContractChangeServiceTest {

    private static final Long CONTRACT_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long DEBTOR_ID = 20L;
    private static final Long NEW_CHANGE_REQUEST_ID = 77L;
    private static final String REQUEST_IDENTITY_ID = "request-identity-id";
    private static final String REQUEST_SIGNATURE_PATH = "uploads/signatures/77_signature.png";

    @Mock
    private ContractChangeRepository contractChangeRepository;

    @Mock
    private LoanContractService loanContractService;

    @Mock
    private LoanChangeService loanChangeService;

    @Mock
    private ContractAccountService contractAccountService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private UserService userService;

    @Mock
    private LoanContractFileService fileService;

    @Mock
    private RepaymentScheduleService repaymentScheduleService;

    @Mock
    private IdentityService identityService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ContractChangeService contractChangeService;

    private final MultipartFile signatureFile = mock(MultipartFile.class);

    private void givenSaveAssignsChangeRequestId() {
        given(contractChangeRepository.saveAndFlush(any(LoanContractChangeRequest.class)))
                .willAnswer(invocation -> {
                    LoanContractChangeRequest saved = invocation.getArgument(0);
                    ReflectionTestUtils.setField(saved, "changeRequestId", NEW_CHANGE_REQUEST_ID);
                    return saved;
                });
    }

    private LoanContractChangeRequest entity(
            Long changeRequestId,
            Long contractId,
            Long userId,
            ChangeRequestStatus status,
            String requesterSignature
    ) {
        LoanContractChangeRequest entity = new LoanContractChangeRequest(
                contractId,
                userId,
                "이자율 조정 요청",
                null,
                null,
                null,
                null,
                null,
                status,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        ReflectionTestUtils.setField(entity, "changeRequestId", changeRequestId);
        if (requesterSignature != null) {
            entity.attachRequesterSignature(requesterSignature);
        }
        return entity;
    }

    private LoanContractResponse createContract(ContractStatus status) {
        return LoanContractResponse.builder()
                .contractId(CONTRACT_ID)
                .status(status)
                .creditorId(USER_ID)
                .debtorId(DEBTOR_ID)
                .principalAmount(BigDecimal.valueOf(1_000_000))
                .repaymentDay(15)
                .creditorAddress("서울시 강남구")
                .debtorAddress("서울시 서초구")
                .contractAlias("차용증")
                .terms("계약 조건")
                .build();
    }

    private ContractChangeRequest changeRequest() {
        return ContractChangeRequest.builder()
                .changeReason("이자율 조정 요청")
                .newMaturityDate(LocalDate.of(2027, 1, 1))
                .newInterestRate(BigDecimal.valueOf(5.0))
                .newRepaymentType(RepaymentMethod.EQUAL_PRINCIPAL_AND_INTEREST.name())
                .newRepaymentDate(15)
                .build();
    }

    @Nested
    @DisplayName("변경 요청 저장")
    class RequestChange {

        @Test
        @DisplayName("완료된 계약이고 중복 요청이 없으면 변경 요청과 계약서 테이블에 함께 저장한다")
        void requestChangeSuccess() {
            given(loanContractService.findContract(CONTRACT_ID, USER_ID))
                    .willReturn(createContract(ContractStatus.COMPLETED));
            given(contractChangeRepository.findByContractId(CONTRACT_ID))
                    .willReturn(List.of());
            givenSaveAssignsChangeRequestId();
            given(fileService.saveSignatureFile(NEW_CHANGE_REQUEST_ID, signatureFile))
                    .willReturn(REQUEST_SIGNATURE_PATH);
            given(userService.getMyInfo(USER_ID))
                    .willReturn(UserResponse.builder().name("채권자").build());

            contractChangeService.requestChange(CONTRACT_ID, changeRequest(), signatureFile, REQUEST_IDENTITY_ID, USER_ID);

            ArgumentCaptor<LoanContractChangeRequest> savedCaptor =
                    ArgumentCaptor.forClass(LoanContractChangeRequest.class);
            verify(contractChangeRepository).save(savedCaptor.capture());
            assertThat(savedCaptor.getValue().getRequesterSignature()).isEqualTo(REQUEST_SIGNATURE_PATH);
            verify(identityService).consume(USER_ID, REQUEST_IDENTITY_ID, IdentityPurpose.LOAN_CONTRACT);
            verify(notificationService).create(
                    eq(DEBTOR_ID),
                    eq(NotificationType.CONTRACT_CHANGE),
                    any(), any(), eq(CONTRACT_ID), eq(NEW_CHANGE_REQUEST_ID)
            );

            ArgumentCaptor<ChangedLoanContractDTO> captor =
                    ArgumentCaptor.forClass(ChangedLoanContractDTO.class);
            verify(loanChangeService).insertChangedContract(captor.capture());

            ChangedLoanContractDTO changedContract = captor.getValue();
            assertThat(changedContract.getPreviousContractId()).isEqualTo(CONTRACT_ID);
            assertThat(changedContract.getStatus()).isEqualTo(ContractStatus.PENDING);
        }

        @Test
        @DisplayName("완료되지 않은 계약이면 예외가 발생한다")
        void requestChangeFailsWhenNotCompleted() {
            given(loanContractService.findContract(CONTRACT_ID, USER_ID))
                    .willReturn(createContract(ContractStatus.DRAFT));

            assertThatThrownBy(() -> contractChangeService.requestChange(CONTRACT_ID, changeRequest(), signatureFile, REQUEST_IDENTITY_ID, USER_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ContractChangeErrorCode.CONTRACT_NOT_COMPLETED)
                    );
        }

        @Test
        @DisplayName("이미 PENDING 요청이 있으면 예외가 발생한다")
        void requestChangeFailsWhenDuplicatePending() {
            LoanContractChangeRequest pendingRequest =
                    entity(1L, CONTRACT_ID, USER_ID, ChangeRequestStatus.PENDING, null);

            given(loanContractService.findContract(CONTRACT_ID, USER_ID))
                    .willReturn(createContract(ContractStatus.COMPLETED));
            given(contractChangeRepository.findByContractId(CONTRACT_ID))
                    .willReturn(List.of(pendingRequest));

            assertThatThrownBy(() -> contractChangeService.requestChange(CONTRACT_ID, changeRequest(), signatureFile, REQUEST_IDENTITY_ID, USER_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ContractChangeErrorCode.DUPLICATE_PENDING_REQUEST)
                    );
        }

        @Test
        @DisplayName("계약 당사자가 아니면 예외가 발생한다")
        void requestChangeFailsWhenNotContractParty() {
            LoanContractResponse contract = LoanContractResponse.builder()
                    .contractId(CONTRACT_ID)
                    .status(ContractStatus.COMPLETED)
                    .creditorId(888L)   // ← userId(10L)와 다른 채권자
                    .debtorId(999L)     // ← userId(10L)와 다른 채무자
                    .build();

            given(loanContractService.findContract(CONTRACT_ID, USER_ID))
                    .willReturn(contract);

            assertThatThrownBy(() -> contractChangeService.requestChange(CONTRACT_ID, changeRequest(), signatureFile, REQUEST_IDENTITY_ID, USER_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ContractChangeErrorCode.NOT_CONTRACT_PARTY)
                    );
        }

        @Test
        @DisplayName("채무자가 요청해도 정상적으로 저장된다")
        void requestChangeSucceedsWhenRequesterIsDebtor() {
            LoanContractResponse contract = LoanContractResponse.builder()
                    .contractId(CONTRACT_ID)
                    .status(ContractStatus.COMPLETED)
                    .creditorId(888L)
                    .debtorId(USER_ID)
                    .build();

            given(loanContractService.findContract(CONTRACT_ID, USER_ID))
                    .willReturn(contract);
            given(contractChangeRepository.findByContractId(CONTRACT_ID))
                    .willReturn(List.of());
            givenSaveAssignsChangeRequestId();
            given(fileService.saveSignatureFile(NEW_CHANGE_REQUEST_ID, signatureFile))
                    .willReturn(REQUEST_SIGNATURE_PATH);
            given(userService.getMyInfo(USER_ID))
                    .willReturn(UserResponse.builder().name("채무자").build());

            contractChangeService.requestChange(CONTRACT_ID, changeRequest(), signatureFile, REQUEST_IDENTITY_ID, USER_ID);

            verify(contractChangeRepository).saveAndFlush(any(LoanContractChangeRequest.class));
            verify(loanChangeService).insertChangedContract(any());
            verify(notificationService).create(
                    eq(888L),
                    eq(NotificationType.CONTRACT_CHANGE),
                    any(), any(), eq(CONTRACT_ID), eq(NEW_CHANGE_REQUEST_ID)
            );
        }

        @Test
        @DisplayName("서명 저장에 실패하면 예외를 그대로 던져 트랜잭션을 롤백하고 알림을 보내지 않는다")
        void requestChangeFailsWhenSignatureCannotBeSaved() {
            given(loanContractService.findContract(CONTRACT_ID, USER_ID))
                    .willReturn(createContract(ContractStatus.COMPLETED));
            given(contractChangeRepository.findByContractId(CONTRACT_ID))
                    .willReturn(List.of());
            givenSaveAssignsChangeRequestId();
            given(fileService.saveSignatureFile(NEW_CHANGE_REQUEST_ID, signatureFile))
                    .willThrow(new IllegalStateException("저장 실패"));

            assertThatThrownBy(() -> contractChangeService.requestChange(
                    CONTRACT_ID, changeRequest(), signatureFile, REQUEST_IDENTITY_ID, USER_ID))
                    .isInstanceOf(IllegalStateException.class);

            verify(contractChangeRepository, never()).save(any());
            verifyNoInteractions(notificationService);
        }

        @Test
        @DisplayName("요청을 만들 수 없는 상태면 본인인증을 소모하지 않고 서명도 저장하지 않는다")
        void requestChangeDoesNotConsumeIdentityWhenRejected() {
            given(loanContractService.findContract(CONTRACT_ID, USER_ID))
                    .willReturn(createContract(ContractStatus.DRAFT));

            assertThatThrownBy(() -> contractChangeService.requestChange(
                    CONTRACT_ID, changeRequest(), signatureFile, REQUEST_IDENTITY_ID, USER_ID))
                    .isInstanceOf(DomainException.class);

            verifyNoInteractions(identityService, fileService);
        }
    }

    @Nested
    @DisplayName("계약 변경 승인")
    class ApproveChange {

        private static final Long V1_CONTRACT_ID = 1L;
        private static final Long V2_CONTRACT_ID = 2L;
        private static final Long CHANGE_REQUEST_ID = 5L;
        private static final String IDENTITY_VERIFICATION_ID = "identity-verification-id";
        private static final String SAVED_PATH = "uploads/signatures/change_2_signature.png";
        private static final String REQUESTER_SIGNATURE = "uploads/signatures/change_5_signature.png";

        private LoanContractResponse pendingV2Contract() {
            return LoanContractResponse.builder()
                    .contractId(V2_CONTRACT_ID)
                    .previousContractId(V1_CONTRACT_ID)
                    .status(ContractStatus.PENDING)
                    .creditorId(USER_ID)
                    .debtorId(DEBTOR_ID)
                    .build();
        }

        private LoanContractChangeRequest pendingChangeRequest() {
            // 요청자 = 채권자(USER_ID)
            return entity(CHANGE_REQUEST_ID, V1_CONTRACT_ID, USER_ID, ChangeRequestStatus.PENDING, null);
        }

        @Test
        @DisplayName("승인 처리 시 변경 요청을 APPROVED로 바꾸고 v1 계약을 SUPERSEDED로 전환한다")
        void approveChangeSupersedesV1Contract() {
            MultipartFile signature = mock(MultipartFile.class);
            LoanContractChangeRequest changeRequest = pendingChangeRequest();
            changeRequest.attachRequesterSignature(REQUESTER_SIGNATURE);

            given(loanContractService.getContractForInternalUse(V2_CONTRACT_ID))
                    .willReturn(pendingV2Contract());
            given(contractChangeRepository.findByContractId(V1_CONTRACT_ID))
                    .willReturn(List.of(changeRequest));
            // 후보를 찾은 뒤 잠금 걸고 다시 조회하는 단계 -> 같은 상자를 그대로 돌려줌 (정상 흐름)
            given(contractChangeRepository.findByIdForUpdate(CHANGE_REQUEST_ID))
                    .willReturn(Optional.of(changeRequest));
            given(fileService.saveSignatureFile(V2_CONTRACT_ID, signature))
                    .willReturn(SAVED_PATH);
            given(loanChangeService.buildCompletedSnapshot(any(), eq(false), eq(SAVED_PATH)))
                    .willReturn(pendingV2Contract());
            given(userService.getMyInfo(DEBTOR_ID))
                    .willReturn(UserResponse.builder().name("채무자").build());

            // 요청자(USER_ID)가 채권자이므로, 승인자는 채무자(DEBTOR_ID)
            contractChangeService.approveChange(V2_CONTRACT_ID, DEBTOR_ID, signature, IDENTITY_VERIFICATION_ID);

            verify(loanChangeService).updateDebtorSignatureOnly(V2_CONTRACT_ID, SAVED_PATH);
            verify(loanChangeService).updateCreditorSignatureOnly(V2_CONTRACT_ID, REQUESTER_SIGNATURE);
            verify(loanChangeService).buildCompletedSnapshot(
                    argThat(contract -> REQUESTER_SIGNATURE.equals(contract.getCreditorSignature())
                            && SAVED_PATH.equals(contract.getDebtorSignature())),
                    eq(false),
                    eq(SAVED_PATH));
            verify(contractChangeRepository).save(changeRequest);
            assertThat(changeRequest.getStatus()).isEqualTo(ChangeRequestStatus.APPROVED);
            verify(loanChangeService).supersedeContract(V1_CONTRACT_ID);
            verify(contractAccountService).copyActiveAccount(V1_CONTRACT_ID, V2_CONTRACT_ID);
            verify(repaymentScheduleService).generateChangedSchedule(V1_CONTRACT_ID, V2_CONTRACT_ID);
        }

        @Test
        @DisplayName("채무자가 요청하고 채권자가 승인해도 양쪽 서명을 변경 계약서에 기록한다")
        void approveChangeRecordsBothSignaturesWhenCreditorApproves() {
            MultipartFile signature = mock(MultipartFile.class);
            LoanContractChangeRequest changeRequest =
                    entity(CHANGE_REQUEST_ID, V1_CONTRACT_ID, DEBTOR_ID, ChangeRequestStatus.PENDING, null);
            changeRequest.attachRequesterSignature(REQUESTER_SIGNATURE);

            given(loanContractService.getContractForInternalUse(V2_CONTRACT_ID))
                    .willReturn(pendingV2Contract());
            given(contractChangeRepository.findByContractId(V1_CONTRACT_ID))
                    .willReturn(List.of(changeRequest));
            given(contractChangeRepository.findByIdForUpdate(CHANGE_REQUEST_ID))
                    .willReturn(Optional.of(changeRequest));
            given(fileService.saveSignatureFile(V2_CONTRACT_ID, signature))
                    .willReturn(SAVED_PATH);
            given(loanChangeService.buildCompletedSnapshot(any(), eq(true), eq(SAVED_PATH)))
                    .willReturn(pendingV2Contract());
            given(userService.getMyInfo(USER_ID))
                    .willReturn(UserResponse.builder().name("채권자").build());

            contractChangeService.approveChange(V2_CONTRACT_ID, USER_ID, signature, IDENTITY_VERIFICATION_ID);

            verify(loanChangeService).updateCreditorSignatureOnly(V2_CONTRACT_ID, SAVED_PATH);
            verify(loanChangeService).updateDebtorSignatureOnly(V2_CONTRACT_ID, REQUESTER_SIGNATURE);
        }

        @Test
        @DisplayName("요청자 서명이 없는 변경 요청은 승인할 수 없다")
        void approveChangeFailsWhenRequesterHasNotSigned() {
            MultipartFile signature = mock(MultipartFile.class);
            LoanContractChangeRequest changeRequest = pendingChangeRequest();

            given(loanContractService.getContractForInternalUse(V2_CONTRACT_ID))
                    .willReturn(pendingV2Contract());
            given(contractChangeRepository.findByContractId(V1_CONTRACT_ID))
                    .willReturn(List.of(changeRequest));
            given(contractChangeRepository.findByIdForUpdate(CHANGE_REQUEST_ID))
                    .willReturn(Optional.of(changeRequest));

            assertThatThrownBy(() ->
                    contractChangeService.approveChange(V2_CONTRACT_ID, DEBTOR_ID, signature, IDENTITY_VERIFICATION_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ContractChangeErrorCode.REQUESTER_SIGNATURE_REQUIRED)
                    );

            verify(identityService, never()).consume(any(), any(), any());
            verify(loanChangeService, never()).updateCreditorSignatureOnly(any(), any());
            verify(loanChangeService, never()).updateDebtorSignatureOnly(any(), any());
            verify(loanChangeService, never()).supersedeContract(any());
        }

        @Test
        @DisplayName("잠금 후 재확인 시 이미 다른 요청에 의해 처리되어 있으면 승인하지 않는다")
        void approveChangeFailsWhenAlreadyProcessedAtRecheck() {
            MultipartFile signature = mock(MultipartFile.class);

            // 1단계(후보 찾기) 시점에는 아직 PENDING으로 보였음
            LoanContractChangeRequest snapshotWhenFound = pendingChangeRequest();

            // 2단계(잠금 걸고 재조회) 시점에는, 그 사이 다른 요청이 먼저 승인 처리를 끝내버린 상태
            LoanContractChangeRequest latestState =
                    entity(CHANGE_REQUEST_ID, V1_CONTRACT_ID, USER_ID, ChangeRequestStatus.APPROVED, null);

            given(loanContractService.getContractForInternalUse(V2_CONTRACT_ID))
                    .willReturn(pendingV2Contract());
            given(contractChangeRepository.findByContractId(V1_CONTRACT_ID))
                    .willReturn(List.of(snapshotWhenFound));
            given(contractChangeRepository.findByIdForUpdate(CHANGE_REQUEST_ID))
                    .willReturn(Optional.of(latestState));

            assertThatThrownBy(() ->
                    contractChangeService.approveChange(V2_CONTRACT_ID, DEBTOR_ID, signature, IDENTITY_VERIFICATION_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ContractChangeErrorCode.ALREADY_BEING_REQUEST)
                    );

            verify(loanChangeService, never()).supersedeContract(any());
            verify(contractAccountService, never()).copyActiveAccount(any(), any());
            verify(repaymentScheduleService, never()).generateChangedSchedule(any(), any());
            verify(contractChangeRepository, never()).save(any());
        }

        @Test
        @DisplayName("요청자 본인이 자기 요청을 승인하려 하면 예외가 발생한다")
        void approveChangeFailsWhenRequesterApprovesOwnRequest() {
            MultipartFile signature = mock(MultipartFile.class);
            LoanContractChangeRequest changeRequest = pendingChangeRequest(); // 요청자 = USER_ID(채권자)

            given(loanContractService.getContractForInternalUse(V2_CONTRACT_ID))
                    .willReturn(pendingV2Contract());
            given(contractChangeRepository.findByContractId(V1_CONTRACT_ID))
                    .willReturn(List.of(changeRequest));
            given(contractChangeRepository.findByIdForUpdate(CHANGE_REQUEST_ID))
                    .willReturn(Optional.of(changeRequest));

            // 요청자(USER_ID)가 직접 승인 시도
            assertThatThrownBy(() ->
                    contractChangeService.approveChange(V2_CONTRACT_ID, USER_ID, signature, IDENTITY_VERIFICATION_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ContractChangeErrorCode.NOT_CONTRACT_PARTY)
                    );

            verify(identityService, never()).consume(any(), any(), any());
            verify(contractChangeRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("변경 요청 반려")
    class RejectChange {

        private static final Long CHANGE_REQUEST_ID = 5L;
        private static final Long V2_CONTRACT_ID = 2L;
        private static final String RETURN_REASON = "이율이 너무 높습니다";

        private LoanContractChangeRequest pendingChangeRequest(Long requesterId) {
            return entity(CHANGE_REQUEST_ID, CONTRACT_ID, requesterId, ChangeRequestStatus.PENDING, null);
        }

        private LoanContractResponse pendingV2() {
            return LoanContractResponse.builder()
                    .contractId(V2_CONTRACT_ID)
                    .previousContractId(CONTRACT_ID)
                    .status(ContractStatus.PENDING)
                    .build();
        }

        @Test
        @DisplayName("채무자가 요청한 건을 채권자가 반려할 수 있다")
        void rejectChangeSuccessWhenRequesterIsDebtor() {
            given(loanContractService.findContract(CONTRACT_ID, USER_ID))
                    .willReturn(createContract(ContractStatus.COMPLETED));
            given(contractChangeRepository.findByIdForUpdate(CHANGE_REQUEST_ID))
                    .willReturn(Optional.of(pendingChangeRequest(DEBTOR_ID)));
            given(loanChangeService.findPendingContractByPreviousId(CONTRACT_ID))
                    .willReturn(Optional.of(pendingV2()));
            given(userService.getMyInfo(USER_ID))
                    .willReturn(UserResponse.builder().name("채권자").build());

            contractChangeService.rejectChange(CONTRACT_ID, CHANGE_REQUEST_ID, RETURN_REASON, USER_ID);

            verify(contractChangeRepository).save(any(LoanContractChangeRequest.class));
        }

        @Test
        @DisplayName("채권자가 요청한 건을 채무자가 반려하면 사유를 저장하고 v2를 REJECTED로 바꾼다")
        void rejectChangeSuccess() {
            LoanContractChangeRequest changeRequest = pendingChangeRequest(USER_ID);

            given(loanContractService.findContract(CONTRACT_ID, DEBTOR_ID))
                    .willReturn(createContract(ContractStatus.COMPLETED));
            given(contractChangeRepository.findByIdForUpdate(CHANGE_REQUEST_ID))
                    .willReturn(Optional.of(changeRequest));
            given(loanChangeService.findPendingContractByPreviousId(CONTRACT_ID))
                    .willReturn(Optional.of(pendingV2()));
            given(userService.getMyInfo(DEBTOR_ID))
                    .willReturn(UserResponse.builder().name("채무자").build());

            contractChangeService.rejectChange(CONTRACT_ID, CHANGE_REQUEST_ID, RETURN_REASON, DEBTOR_ID);

            verify(contractChangeRepository).save(changeRequest);
            assertThat(changeRequest.getStatus()).isEqualTo(ChangeRequestStatus.REJECTED);
            assertThat(changeRequest.getReturnReason()).isEqualTo(RETURN_REASON);
            verify(loanChangeService).rejectChangedContract(V2_CONTRACT_ID);
        }

        @Test
        @DisplayName("채권자가 반려를 시도하면 예외가 발생하고 아무것도 저장하지 않는다")
        void rejectChangeFailsWhenRequesterIsCreditor() {
            given(loanContractService.findContract(CONTRACT_ID, USER_ID))
                    .willReturn(createContract(ContractStatus.COMPLETED));
            given(contractChangeRepository.findByIdForUpdate(CHANGE_REQUEST_ID))
                    .willReturn(Optional.of(pendingChangeRequest(USER_ID)));

            assertThatThrownBy(() ->
                    contractChangeService.rejectChange(CONTRACT_ID, CHANGE_REQUEST_ID, RETURN_REASON, USER_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ContractChangeErrorCode.NOT_CONTRACT_PARTY)
                    );

            verify(contractChangeRepository, never()).save(any());
            verify(loanChangeService, never()).rejectChangedContract(any());
        }

        @Test
        @DisplayName("changeRequestId가 다른 계약 소속이면 예외가 발생한다")
        void rejectChangeFailsWhenContractMismatch() {
            LoanContractChangeRequest otherContractRequest =
                    entity(CHANGE_REQUEST_ID, 999L, USER_ID, ChangeRequestStatus.PENDING, null);

            given(loanContractService.findContract(CONTRACT_ID, DEBTOR_ID))
                    .willReturn(createContract(ContractStatus.COMPLETED));
            given(contractChangeRepository.findByIdForUpdate(CHANGE_REQUEST_ID))
                    .willReturn(Optional.of(otherContractRequest));

            assertThatThrownBy(() ->
                    contractChangeService.rejectChange(CONTRACT_ID, CHANGE_REQUEST_ID, RETURN_REASON, DEBTOR_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ContractChangeErrorCode.CHANGE_REQUEST_NOT_FOUND)
                    );
        }

        @Test
        @DisplayName("이미 처리된(APPROVED) 요청을 반려하려 하면 예외가 발생한다")
        void rejectChangeFailsWhenAlreadyProcessed() {
            LoanContractChangeRequest approvedRequest =
                    entity(CHANGE_REQUEST_ID, CONTRACT_ID, USER_ID, ChangeRequestStatus.APPROVED, null);

            given(loanContractService.findContract(CONTRACT_ID, DEBTOR_ID))
                    .willReturn(createContract(ContractStatus.COMPLETED));
            given(contractChangeRepository.findByIdForUpdate(CHANGE_REQUEST_ID))
                    .willReturn(Optional.of(approvedRequest));

            assertThatThrownBy(() ->
                    contractChangeService.rejectChange(CONTRACT_ID, CHANGE_REQUEST_ID, RETURN_REASON, DEBTOR_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ContractChangeErrorCode.ALREADY_BEING_REQUEST)
                    );

            verify(contractChangeRepository, never()).save(any());
        }

        @Test
        @DisplayName("존재하지 않는 변경 요청이면 예외가 발생한다")
        void rejectChangeFailsWhenChangeRequestNotFound() {
            given(loanContractService.findContract(CONTRACT_ID, DEBTOR_ID))
                    .willReturn(createContract(ContractStatus.COMPLETED));
            given(contractChangeRepository.findByIdForUpdate(CHANGE_REQUEST_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    contractChangeService.rejectChange(CONTRACT_ID, CHANGE_REQUEST_ID, RETURN_REASON, DEBTOR_ID))
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(ContractChangeErrorCode.CHANGE_REQUEST_NOT_FOUND)
                    );
        }
    }
}
