package org.teamsai.saibackend.domain.settlement.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecordEntity;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.settlement.assembler.SettlementAssembler;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementDetailResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementListResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentHistoryResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentObligationResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;
import org.teamsai.saibackend.domain.settlement.entity.RecurringSettlement;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.support.SettlementPaymentData;
import org.teamsai.saibackend.domain.settlement.support.SettlementPaymentReader;
import org.teamsai.saibackend.domain.settlement.support.SettlementValidator;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;
import org.teamsai.saibackend.domain.transaction.entity.BankTransactionEntity;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionService;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SettlementQueryService {

    private final SettlementRepository settlementRepository;
    private final SettlementParticipantRepository settlementParticipantRepository;
    private final SettlementValidator settlementValidator;
    private final SettlementPaymentReader settlementPaymentReader;
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
    public SettlementDetailResponse getSettlementDetail(
            Long settlementId,
            Long userId
    ) {
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
                findAccessibleSettlement(
                        settlementId,
                        userId
                );

        SettlementPaymentData paymentData =
                settlementPaymentReader.read(settlementId);

        List<SettlementPaymentObligationResponse> obligations =
                buildPaymentObligationResponses(paymentData);

        return SettlementAssembler.toPaymentStatusResponse(
                settlement,
                obligations
        );
    }

    @Transactional(readOnly = true)
    public List<SettlementPaymentHistoryResponse> getPaymentHistory(
            Long settlementId,
            Long userId
    ) {
        // getPaymentStatus()를 다시 호출하지 않고
        // 권한 검증만 한 번 수행
        findAccessibleSettlement(
                settlementId,
                userId
        );

        // 참여자 → obligation → paymentRecord 조회도 한 번만 수행
        SettlementPaymentData paymentData =
                settlementPaymentReader.read(settlementId);

        List<PaymentObligationEntity> obligations =
                getPaymentTargetObligations(
                        paymentData.obligations()
                );

        if (obligations.isEmpty()) {
            return List.of();
        }

        Map<Long, SettlementParticipant> participantMap =
                paymentData.participants().stream()
                        .collect(Collectors.toMap(
                                SettlementParticipant::getParticipantId,
                                participant -> participant
                        ));

        Map<Long, String> payerNameByObligationId =
                obligations.stream()
                        .collect(Collectors.toMap(
                                PaymentObligationEntity::getPaymentObligationId,
                                obligation -> {
                                    SettlementParticipant participant =
                                            participantMap.get(
                                                    obligation.getParticipantId()
                                            );

                                    return participant != null
                                            ? participant.getUser().getName()
                                            : "";
                                }
                        ));

        Set<Long> obligationIds =
                payerNameByObligationId.keySet();

        return paymentData.paymentRecords().stream()
                .filter(record ->
                        obligationIds.contains(
                                record.getTargetId()
                        )
                )
                .map(record ->
                        toPaymentHistoryResponse(
                                record,
                                payerNameByObligationId
                        )
                )
                .toList();
    }

    private Settlement findAccessibleSettlement(
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

        settlementValidator.validateAccessibleUser(
                settlement,
                userId
        );

        return settlement;
    }

    private List<SettlementPaymentObligationResponse> buildPaymentObligationResponses(
            SettlementPaymentData paymentData
    ) {
        List<PaymentObligationEntity> obligations =
                getPaymentTargetObligations(
                        paymentData.obligations()
                );

        if (obligations.isEmpty()) {
            return List.of();
        }

        Map<Long, SettlementParticipant> participantMap =
                paymentData.participants().stream()
                        .collect(Collectors.toMap(
                                SettlementParticipant::getParticipantId,
                                participant -> participant
                        ));

        Map<Long, List<PaymentRecordEntity>> paymentRecordMap =
                paymentData.paymentRecords().stream()
                        .collect(Collectors.groupingBy(
                                PaymentRecordEntity::getTargetId
                        ));

        return obligations.stream()
                .map(obligation ->
                        SettlementAssembler.toObligationResponse(
                                obligation,
                                participantMap.get(
                                        obligation.getParticipantId()
                                ),
                                paymentRecordMap.getOrDefault(
                                        obligation.getPaymentObligationId(),
                                        List.of()
                                )
                        )
                )
                .toList();
    }

    private List<PaymentObligationEntity> getPaymentTargetObligations(
            List<PaymentObligationEntity> obligations
    ) {
        return obligations.stream()
                .filter(obligation ->
                        obligation.getObligationStatus()
                                == ObligationStatus.ACTIVE
                                || obligation.getObligationStatus()
                                == ObligationStatus.WRITTEN_OFF
                )
                .toList();
    }

    private SettlementPaymentHistoryResponse toPaymentHistoryResponse(
            PaymentRecordEntity record,
            Map<Long, String> payerNameByObligationId
    ) {
        BankTransactionEntity transaction =
                bankTransactionService
                        .findById(
                                record.getBankTransactionId()
                        )
                        .orElse(null);

        return SettlementAssembler.toPaymentHistoryResponse(
                record,
                transaction,
                payerNameByObligationId.get(
                        record.getTargetId()
                )
        );
    }
}