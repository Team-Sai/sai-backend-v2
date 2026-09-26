package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.teamsai.saibackend.domain.contract.assembler.ContractChangeAssembler;
import org.teamsai.saibackend.domain.contract.dto.request.ContractChangeRequest;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.dto.response.ChangeLoanContractResponse;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractChangeResponse;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.entity.LoanContractChangeRequestEntity;
import org.teamsai.saibackend.domain.contract.event.ContractCompletedEvent;
import org.teamsai.saibackend.domain.contract.exception.ContractChangeErrorCode;
import org.teamsai.saibackend.domain.contract.exception.LoanContractErrorCode;
import org.teamsai.saibackend.domain.contract.repository.ContractChangeRepository;
import org.teamsai.saibackend.domain.contract.type.ChangeRequestStatus;
import org.teamsai.saibackend.domain.identity.service.IdentityService;
import org.teamsai.saibackend.domain.identity.type.IdentityPurpose;
import org.teamsai.saibackend.domain.notification.service.NotificationService;
import org.teamsai.saibackend.domain.notification.type.NotificationType;
import org.teamsai.saibackend.domain.user.service.UserService;

import java.time.LocalDateTime;
import java.time.Period;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContractChangeService {

    private final LoanContractService loanContractService;
    private final LoanChangeService loanChangeService;
    private final RepaymentScheduleService repaymentScheduleService;
    private final NotificationService notificationService;
    private final UserService userService;
    private final LoanContractFileService fileService;
    private final IdentityService identityService;
    private final ApplicationEventPublisher eventPublisher;
    private final ContractChangeRepository contractChangeRepository;

    private LoanContractChangeRequestEntity getChangeRequestForUpdate(Long changeRequestId) {
        return contractChangeRepository.findByIdForUpdate(changeRequestId)
                .orElseThrow(ContractChangeErrorCode.CHANGE_REQUEST_NOT_FOUND::toException);
    }

    private LoanContractResponse getPendingChangedContract(Long contractId) {
        return loanChangeService.findPendingContractByPreviousId(contractId)
                .orElseThrow(ContractChangeErrorCode.CHANGE_REQUEST_NOT_FOUND::toException);
    }

    private Long resolveApproverId(LoanContractResponse contract, LoanContractChangeRequestEntity changeRequest) {
        boolean requesterIsCreditor = Objects.equals(contract.getCreditorId(), changeRequest.getUserId());
        return requesterIsCreditor ? contract.getDebtorId() : contract.getCreditorId();
    }

    private void notifyChange(Long recipientId, Long actorId, String title, String messageSuffix,
                              Long contractId, Long changeRequestId) {
        if (recipientId == null) {
            log.warn("계약 변경 알림 발송 생략 - recipientId가 null입니다. title={}, contractId={}", title, contractId);
            return;
        }
        try {
            String actorName = userService.getMyInfo(actorId).getName();
            if (changeRequestId != null) {
                notificationService.create(recipientId, NotificationType.CONTRACT_CHANGE,
                        title, actorName + messageSuffix, contractId, changeRequestId);
            } else {
                notificationService.create(recipientId, NotificationType.CONTRACT_CHANGE,
                        title, actorName + messageSuffix, contractId);
            }
        } catch (Exception e) {
            log.error("계약 변경 알림 발송 실패: title={}, contractId={}, recipientId={}, error={}",
            title, contractId, recipientId, e.getMessage(), e);
        }
    }

    @Transactional
    public LoanContractChangeResponse requestChange(
            Long contractId,
            ContractChangeRequest request,
            Long userId
            ) {

        LoanContractResponse contract = loanContractService.findContract(contractId, userId);

        boolean isCreditor = contract.isCreditor(userId);
        boolean isDebtor = contract.isDebtor(userId);

        if (!isCreditor && !isDebtor) {
            throw ContractChangeErrorCode.NOT_CONTRACT_PARTY.toException();
        }

        if (contract.getStatus() != ContractStatus.COMPLETED) {
            throw ContractChangeErrorCode.CONTRACT_NOT_COMPLETED.toException();
        }

        if (contract.getStartDate() != null
                && request.getNewMaturityDate() != null
                && Period.between(contract.getStartDate(), request.getNewMaturityDate()).toTotalMonths() <= 0) {
            throw ContractChangeErrorCode.INVALID_MATURITY_DATE.toException();
        }

        List<LoanContractChangeRequestEntity> existingRequests = contractChangeRepository.findByContractId(contractId);

        boolean hasPendingRequest = existingRequests.stream()
                .anyMatch(changeRequest -> ChangeRequestStatus.PENDING.equals(changeRequest.getStatus()));

        if (hasPendingRequest) {
            throw ContractChangeErrorCode.DUPLICATE_PENDING_REQUEST.toException();
        }

        boolean alreadySuperseded = existingRequests.stream()
                .anyMatch(changeRequest -> ChangeRequestStatus.APPROVED.equals(changeRequest.getStatus()));

        if (alreadySuperseded) {
            throw ContractChangeErrorCode.CONTRACT_ALREADY_SUPERSEDED.toException();
        }

        LocalDateTime now = LocalDateTime.now();

        LoanContractChangeRequestEntity changeEntity = new LoanContractChangeRequestEntity(
                contractId,
                userId,
                request.getChangeReason(),
                request.getNewMaturityDate(),
                request.getNewInterestRate(),
                request.getNewRepaymentType(),
                request.getNewRepaymentDate(),
                request.getNewTerms(),
                ChangeRequestStatus.PENDING,
                now,
                now
        );

        LoanContractChangeRequestEntity savedEntity;
        try {
            savedEntity = contractChangeRepository.saveAndFlush(changeEntity);
        } catch (DataIntegrityViolationException e) {
            throw ContractChangeErrorCode.DUPLICATE_PENDING_REQUEST.toException();
        }

        ChangeLoanContractResponse newContractDTO = ContractChangeAssembler.toChangedContract(contract, request, contractId, now);

        loanChangeService.insertChangedContract(newContractDTO);

        log.info("계약 변경 요청 생성 및 차용증 재저장 완료: contractId={}, userId={}",
                contractId, userId);

        return LoanContractChangeResponse.from(savedEntity);
    }

    @Transactional
    public LoanContractChangeResponse rejectChange(Long contractId, Long changeRequestId, String returnReason, Long userId) {

        LoanContractResponse contract = loanContractService.findContract(contractId, userId);
        LoanContractChangeRequestEntity changeRequest = getChangeRequestForUpdate(changeRequestId);

        if (!Objects.equals(resolveApproverId(contract, changeRequest), userId)) {
            throw ContractChangeErrorCode.NOT_CONTRACT_PARTY.toException();
        }

        changeRequest.validateBelongsTo(contractId);
        changeRequest.validatePending();

        changeRequest.reject(returnReason);
        contractChangeRepository.save(changeRequest);

        notifyChange(changeRequest.getUserId(), userId, "계약 변경 요청 반려",
                "님이 변경 요청을 반려했습니다.", contractId, changeRequestId);

        LoanContractResponse v2 = getPendingChangedContract(contractId);

        loanChangeService.rejectChangedContract(v2.getContractId());

        log.info("계약 변경 요청 반려 처리 완료: contractId={}, changeRequestId={}", contractId, changeRequestId);

        return LoanContractChangeResponse.from(changeRequest);

    }

    @Transactional
    public ContractStatus approveChange(Long contractId, Long userId, MultipartFile signature, String identityVerificationId) {

        LoanContractResponse contract = loanContractService.getContractForInternalUse(contractId);

        boolean isCreditor = contract.isCreditor(userId);
        boolean isDebtor = contract.isDebtor(userId);

        if (!isCreditor && !isDebtor) {
            throw LoanContractErrorCode.CONTRACT_ACCESS_DENIED.toException();
        }

        if (contract.getStatus() == ContractStatus.COMPLETED) {
            throw LoanContractErrorCode.CONTRACT_ALREADY_COMPLETED.toException();
        }

        if (contract.getPreviousContractId() == null) {
            throw LoanContractErrorCode.NOT_A_CHANGE_CONTRACT.toException();
        }

        Long v1ContractId = contract.getPreviousContractId();

        LoanContractChangeRequestEntity changeRequest = contractChangeRepository.findByContractId(v1ContractId).stream()
                .filter(r -> r.getStatus() == ChangeRequestStatus.PENDING)
                .findFirst()
                .orElseThrow(ContractChangeErrorCode.CHANGE_REQUEST_NOT_FOUND::toException);

        changeRequest = getChangeRequestForUpdate(changeRequest.getChangeRequestId());

        changeRequest.validatePending();

        if (!Objects.equals(resolveApproverId(contract, changeRequest), userId)) {
            throw ContractChangeErrorCode.NOT_CONTRACT_PARTY.toException();
        }

        identityService.consume(userId, identityVerificationId, IdentityPurpose.LOAN_CONTRACT);

        String savedPath = fileService.saveSignatureFile(contractId, signature);

        if (isCreditor) {
            loanChangeService.updateCreditorSignatureOnly(contractId, savedPath);
        } else {
            loanChangeService.updateDebtorSignatureOnly(contractId, savedPath);
        }

        changeRequest.approve();
        contractChangeRepository.save(changeRequest);

        loanChangeService.supersedeContract(v1ContractId);

        repaymentScheduleService.generateChangedSchedule(v1ContractId, contractId);

        log.info("계약 변경 승인 처리 완료: v1ContractId={}, v2ContractId={}, changeRequestId={}",
                v1ContractId, contractId, changeRequest.getChangeRequestId());

        LoanContractResponse completedContract = loanChangeService.buildCompletedSnapshot(contract, isCreditor, savedPath);
        eventPublisher.publishEvent(new ContractCompletedEvent(completedContract));

        notifyChange(changeRequest.getUserId(), userId, "계약 변경 승인 완료",
                "님이 신청하신 계약 변경 요청을 승인했습니다.", contractId, null);

        return ContractStatus.COMPLETED;
    }

    @Transactional
    public void cancelChangeRequest(Long contractId, Long changeRequestId, Long userId) {
        LoanContractChangeRequestEntity changeRequest = getChangeRequestForUpdate(changeRequestId);

        changeRequest.validateBelongsTo(contractId);
        changeRequest.validateRequestedBy(userId);
        changeRequest.validatePending();
        changeRequest.validateNotSigned();

        changeRequest.cancel();
        contractChangeRepository.save(changeRequest);

        LoanContractResponse v2 = getPendingChangedContract(contractId);
        loanChangeService.rejectChangedContract(v2.getContractId());

        log.info("계약 변경 요청 취소 처리 완료: contractId={}, changeRequestId={}, userId={}",
                contractId, changeRequestId, userId);
    }

    @Transactional
    public LoanContractChangeResponse submitRequesterSignature(
            Long contractId,
            Long changeRequestId,
            Long userId,
            MultipartFile signature,
            String identityVerificationId

    ) {


        identityService.consume(userId, identityVerificationId, IdentityPurpose.LOAN_CONTRACT);

        LoanContractChangeRequestEntity changeRequest = getChangeRequestForUpdate(changeRequestId);

        changeRequest.validateBelongsTo(contractId);
        changeRequest.validateRequestedBy(userId);
        changeRequest.validatePending();
        changeRequest.validateNotSigned();

        String savedPath = fileService.saveSignatureFile(changeRequestId, signature);

        changeRequest.attachRequesterSignature(savedPath);
        contractChangeRepository.save(changeRequest);

        LoanContractResponse contract = loanContractService.findContract(contractId, userId);
        Long recipientId = contract.isCreditor(userId) ? contract.getDebtorId() : contract.getCreditorId();

        notifyChange(recipientId, userId, "계약 변경 요청",
                "님으로부터 계약 내용 변경 요청이 도착했습니다.", contractId, changeRequestId);

        return LoanContractChangeResponse.from(changeRequest);
    }
}
