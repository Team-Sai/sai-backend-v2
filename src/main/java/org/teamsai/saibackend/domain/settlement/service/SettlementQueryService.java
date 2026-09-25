package org.teamsai.saibackend.domain.settlement.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecordEntity;
import org.teamsai.saibackend.domain.payment.repository.PaymentObligationRepository;
import org.teamsai.saibackend.domain.payment.repository.PaymentRecordRepository;
import org.teamsai.saibackend.domain.payment.service.PaymentRecordService;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.payment.type.RecordStatus;
import org.teamsai.saibackend.domain.settlement.assembler.SettlementAssembler;
import org.teamsai.saibackend.domain.settlement.dto.response.*;
import org.teamsai.saibackend.domain.settlement.entity.RecurringSettlement;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.support.SettlementValidator;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;
import org.teamsai.saibackend.domain.transaction.entity.BankTransactionEntity;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SettlementQueryService {

    private final SettlementRepository settlementRepository;
    private final SettlementParticipantRepository settlementParticipantRepository;
    private final PaymentObligationRepository paymentObligationRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final SettlementValidator settlementValidator;
    private final PaymentRecordService paymentRecordService;
    private final BankTransactionService bankTransactionService;

    @Transactional(readOnly = true)
    public List<SettlementListResponse> getSettlementList(Long userId) {
        return settlementRepository.findAllAccessibleByUserId(
                        userId,
                        SettlementParticipantStatus.ACTIVE
                )
                .stream()
                .map(settlement -> {
                    RecurringSettlement recurringSettlement =
                            settlement.getRecurringSettlement();

                    String role =
                            settlement.getOwner().getUserId().equals(userId)
                                    ? "OWNER"
                                    : "MEMBER";

                    return new SettlementListResponse(
                            settlement.getSettlementId(),
                            settlement.getTitle(),
                            role,
                            settlement.getSettlementCategory(),
                            settlement.getSettlementType().name(),
                            settlement.getSplitType() != null
                                    ? settlement.getSplitType().name()
                                    : null,
                            settlement.getSettlementStatus().name(),
                            settlement.getTotalAmount(),
                            settlement.getDueDate(),
                            recurringSettlement != null
                                    ? recurringSettlement.getStartDate()
                                    : null,
                            recurringSettlement != null
                                    ? recurringSettlement.getEndDate()
                                    : null,
                            settlement.getCycleDate(),
                            settlement.getCreatedAt()
                    );
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public SettlementDetailResponse getSettlementDetail(Long settlementId, Long userId){
        Settlement settlement =
                settlementRepository.findDetailById(settlementId)
                        .orElseThrow(
                                SettlementErrorCode
                                        .SETTLEMENT_NOT_FOUND
                                        ::toException
                        );

        String role;

        if (settlement.getOwner().getUserId().equals(userId)) {
            role = "OWNER";
        } else {
            boolean isParticipant =
                    settlementParticipantRepository.existsActiveParticipant(
                            settlementId,
                            userId,
                            SettlementParticipantStatus.ACTIVE
                    );

            if (!isParticipant) {
                throw SettlementErrorCode
                        .SETTLEMENT_ACCESS_DENIED
                        .toException();
            }

            role = "MEMBER";
        }

        RecurringSettlement recurringSettlement =
                settlement.getRecurringSettlement();

        return new SettlementDetailResponse(
                settlement.getSettlementId(),
                settlement.getTitle(),
                settlement.getOwner().getName(),
                settlement.getSettlementCategory(),
                settlement.getSettlementType().name(),
                settlement.getSettlementStatus().name(),
                settlement.getSplitType() != null
                        ? settlement.getSplitType().name()
                        : null,
                settlement.getDueDate(),
                recurringSettlement != null
                        ? recurringSettlement.getStartDate()
                        : null,
                recurringSettlement != null
                        ? recurringSettlement.getEndDate()
                        : null,
                settlement.getCreatedAt(),
                role
        );
    }
    @Transactional(readOnly = true)
    public SettlementPaymentStatusResponse getPaymentStatus(
            Long settlementId,
            Long userId
    ) {
        Settlement settlement =
                settlementRepository.findById(settlementId)
                        .orElseThrow(
                                SettlementErrorCode
                                        .SETTLEMENT_NOT_FOUND
                                        ::toException
                        );
        settlementValidator.validateAccessibleUser(settlement,userId);
        List<SettlementPaymentObligationResponse> obligations =
                buildPaymentObligationResponses(
                        settlement.getSettlementId()
                );

        return SettlementAssembler.toPaymentStatusResponse(settlement, obligations);
    }

    private List<SettlementPaymentObligationResponse> buildPaymentObligationResponses(
            Long settlementId
    ) {
        List<SettlementParticipant> participants =
                settlementParticipantRepository.findBySettlementIdAndStatus(
                        settlementId,
                        SettlementParticipantStatus.ACTIVE
                );

        if (participants.isEmpty()) {
            return List.of();
        }

        List<Long> participantIds = participants.stream()
                .map(SettlementParticipant::getParticipantId)
                .toList();

        List<PaymentObligationEntity> obligations =
                paymentObligationRepository.findByParticipantIdsAndObligationStatuses(
                        participantIds,
                        List.of(
                                ObligationStatus.ACTIVE,
                                ObligationStatus.WRITTEN_OFF
                        )
                );

        if (obligations.isEmpty()) {
            return List.of();
        }

        List<Long> obligationIds = obligations.stream()
                .map(PaymentObligationEntity::getPaymentObligationId)
                .toList();

        List<PaymentRecordEntity> paymentRecords =
                paymentRecordRepository.findConfirmedByTargetIds(
                        PaymentTargetType.SETTLEMENT,
                        obligationIds,
                        RecordStatus.CONFIRMED
                );

        Map<Long, SettlementParticipant> participantMap = participants.stream()
                .collect(Collectors.toMap(
                        SettlementParticipant::getParticipantId,
                        participant -> participant
                ));

        Map<Long, List<PaymentRecordEntity>> paymentRecordMap = paymentRecords.stream()
                .collect(Collectors.groupingBy(
                        PaymentRecordEntity::getTargetId
                ));

        return obligations.stream()
                .map(obligation -> SettlementAssembler.toObligationResponse(
                        obligation,
                        participantMap.get(obligation.getParticipantId()),
                        paymentRecordMap.getOrDefault(obligation.getPaymentObligationId(), List.of())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SettlementPaymentHistoryResponse> getPaymentHistory(Long settlementId, Long userId) {
        SettlementPaymentStatusResponse paymentStatus =
                getPaymentStatus(settlementId, userId);

        Map<Long, String> payerNameByObligationId = paymentStatus.getObligations().stream()
                .collect(Collectors.toMap(
                        SettlementPaymentObligationResponse::getPaymentObligationId,
                        SettlementPaymentObligationResponse::getParticipantName
                ));

        List<PaymentRecordEntity> records = paymentRecordService.findConfirmedRecordsByTargetIds(
                PaymentTargetType.SETTLEMENT,
                List.copyOf(payerNameByObligationId.keySet())
        );

        return records.stream()
                .map(record -> toResponse(record, payerNameByObligationId))
                .toList();
    }

    private SettlementPaymentHistoryResponse toResponse(
            PaymentRecordEntity record,
            Map<Long, String> payerNameByObligationId
    ) {
        BankTransactionEntity transaction = bankTransactionService
                .findById(record.getBankTransactionId())
                .orElse(null);

        return SettlementAssembler.toPaymentHistoryResponse(
                record, transaction, payerNameByObligationId.get(record.getTargetId())
        );
    }
}