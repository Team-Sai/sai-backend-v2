package org.teamsai.saibackend.domain.contract.service;

import jakarta.persistence.EntityManager;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.teamsai.saibackend.domain.contract.assembler.LoanContractAssembler;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.dto.request.LoanContractRequest;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.entity.LoanContract;
import org.teamsai.saibackend.domain.contract.event.ContractCompletedEvent;
import org.teamsai.saibackend.domain.contract.event.ContractCreatedEvent;
import org.teamsai.saibackend.domain.contract.exception.LoanContractErrorCode;
import org.teamsai.saibackend.domain.contract.repository.LoanContractRepository;
import org.teamsai.saibackend.domain.identity.service.IdentityService;
import org.teamsai.saibackend.domain.identity.type.IdentityPurpose;
import org.teamsai.saibackend.domain.notification.service.NotificationService;
import org.teamsai.saibackend.domain.notification.type.NotificationType;
import org.teamsai.saibackend.domain.user.dto.response.UserResponse;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.service.UserService;

import java.util.List;

@Slf4j
@Service
@AllArgsConstructor
public class LoanContractService {
    private final LoanContractRepository contractRepository;
    private final LoanContractFileService fileService;
    private final ContractAccountService contractAccountService;
    private final UserService userService;
    private final IdentityService identityService;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationService notificationService;
    private final EntityManager entityManager;

    @Transactional
    public Long createContract(LoanContractRequest request, Long userId) {

        identityService.consume(
                userId,
                request.getIdentityVerificationId(),
                IdentityPurpose.LOAN_CONTRACT
        );

        userService.getMyInfo(userId);

        LoanContract contract = LoanContract.builder()
                .creditor(entityManager.getReference(User.class, userId))
                .relationType(request.getRelationType())
                .creditorAddress(request.getCreditorAddress())
                .principalAmount(request.getPrincipalAmount())
                .interestRate(request.getInterestRate())
                .repaymentType(request.getRepaymentType())
                .startDate(request.getStartDate())
                .maturityDate(request.getMaturityDate())
                .repaymentDay(request.getRepaymentDay())
                .contractAlias(request.getContractAlias())
                .terms(request.getTerms())
                .status(ContractStatus.DRAFT)
                .build();

        LoanContract saved = contractRepository.save(contract);

        contractAccountService.createContractAccount(saved.getContractId(), userId, request.getSelectedLinkedAccountId());

        eventPublisher.publishEvent(new ContractCreatedEvent(saved.getContractId()));

        return saved.getContractId();
    }

    @Transactional
    public ContractStatus submitCreditorSignature(Long contractId, Long userId, String debtorUserToken, MultipartFile signature) {
        LoanContract contract = contractRepository.findById(contractId)
                .orElseThrow(LoanContractErrorCode.CONTRACT_NOT_FOUND::toException);

        if (!contract.getCreditor().getUserId().equals(userId)) {
            throw LoanContractErrorCode.CONTRACT_ACCESS_DENIED.toException();
        }

        UserResponse creditorInfo = userService.getMyInfo(userId);

        Long debtorId = userService.findRequestTarget(userId, debtorUserToken).getUserId();

        String savedPath = fileService.saveSignatureFile(contractId, signature);

        contract.submitCreditorSignature(savedPath, entityManager.getReference(User.class, debtorId), ContractStatus.PENDING);

        notificationService.create(
                debtorId,
                NotificationType.CONTRACT_REQUESTED,
                "새로운 차용증 수신",
                creditorInfo.getName() + "님으로부터 서명 대기 중인 차용증이 전송되었습니다.",
                contractId
        );

        return ContractStatus.PENDING;
    }

    @Transactional
    public void linkDebtor(Long contractId, Long userId) {
        LoanContract contract = contractRepository.findById(contractId)
                .orElseThrow(LoanContractErrorCode.CONTRACT_NOT_FOUND::toException);

        if (userId.equals(contract.getCreditor().getUserId())) {
            throw LoanContractErrorCode.CANNOT_CREATE_CONTRACT_TO_SELF.toException();
        }

        if (contract.getDebtor() != null && !contract.getDebtor().getUserId().equals(userId)) {
            throw LoanContractErrorCode.CONTRACT_ACCESS_DENIED.toException();
        }

        if (contract.getDebtor() == null) {
            userService.getMyInfo(userId);
            contract.linkDebtor(entityManager.getReference(User.class, userId));
        }
    }

    @Transactional
    public ContractStatus submitDebtorSignature(Long contractId, Long userId, String debtorAddress, MultipartFile signature, String identityVerificationId) {

        if (!StringUtils.hasText(debtorAddress)) {
            throw LoanContractErrorCode.DEBTOR_ADDRESS_REQUIRED.toException();
        }

        LoanContract contract = contractRepository.findById(contractId)
                .orElseThrow(LoanContractErrorCode.CONTRACT_NOT_FOUND::toException);

        if (contract.getDebtor() == null || !contract.getDebtor().getUserId().equals(userId)) {
            throw LoanContractErrorCode.CONTRACT_ACCESS_DENIED.toException();
        }

        if (contract.getStatus() == ContractStatus.COMPLETED) {
            throw LoanContractErrorCode.CONTRACT_ALREADY_COMPLETED.toException();
        }

        identityService.consume(
                userId,
                identityVerificationId,
                IdentityPurpose.LOAN_CONTRACT
        );

        String savedPath = fileService.saveSignatureFile(contractId, signature);
        contract.submitDebtorSignature(debtorAddress, savedPath, ContractStatus.COMPLETED);

        LoanContractResponse completedContract = attachPartyInfo(LoanContractResponse.from(contract));

        eventPublisher.publishEvent(new ContractCompletedEvent(completedContract));

        return ContractStatus.COMPLETED;
    }

    public LoanContractResponse findContract(Long contractId, Long userId) {
        LoanContract contract = contractRepository.findById(contractId)
                .orElseThrow(LoanContractErrorCode.CONTRACT_NOT_FOUND::toException);

        boolean isParty = contract.getCreditor().getUserId().equals(userId)
                || (contract.getDebtor() != null && contract.getDebtor().getUserId().equals(userId));
        if (!isParty) {
            throw LoanContractErrorCode.CONTRACT_ACCESS_DENIED.toException();
        }

        return attachPartyInfo(LoanContractResponse.from(contract));
    }

    public LoanContractResponse attachPartyInfo(LoanContractResponse contract) {
        var creditor = userService.getMyInfo(contract.getCreditorId());
        var debtor = contract.getDebtorId() != null ? userService.getMyInfo(contract.getDebtorId()) : null;

        return LoanContractAssembler.withPartyInfo(contract, creditor, debtor);
    }

    public LoanContractResponse getContractForInternalUse(Long contractId) {
        return contractRepository.findById(contractId)
                .map(LoanContractResponse::from)
                .orElseThrow(LoanContractErrorCode.CONTRACT_NOT_FOUND::toException);
    }

    public Long findDebtorUserId(Long contractId) {
        return contractRepository.findDebtorUserIdByContractId(contractId).orElse(null);
    }

    public List<LoanContractResponse> findContractsByUser(Long userId) {
        User user = entityManager.getReference(User.class, userId);
        return contractRepository.findByCreditorOrDebtorOrderByCreatedAtDesc(user, user)
                .stream()
                .map(LoanContractResponse::from)
                .toList();
    }

}
