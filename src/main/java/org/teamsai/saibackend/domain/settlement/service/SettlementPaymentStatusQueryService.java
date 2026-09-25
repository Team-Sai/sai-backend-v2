package org.teamsai.saibackend.domain.settlement.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecordEntity;
import org.teamsai.saibackend.domain.payment.repository.PaymentObligationRepository;
import org.teamsai.saibackend.domain.payment.repository.PaymentRecordRepository;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.payment.type.RecordStatus;
import org.teamsai.saibackend.domain.settlement.assembler.SettlementAssembler;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementObligationStatusResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentObligationResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.support.SettlementValidator;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementPaymentStatusQueryService {

    private final SettlementRepository settlementRepository;
    private final SettlementParticipantRepository settlementParticipantRepository;
    private final PaymentObligationRepository paymentObligationRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final SettlementValidator settlementValidator;

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

    @Transactional(readOnly = true)
    public boolean areAllObligationsResolved(Long settlementId) {
        List<SettlementObligationStatusResponse> obligations =
                buildObligationStatusResponses(settlementId);
        return !obligations.isEmpty()
                && obligations.stream().allMatch(o ->
                isResolved(o.obligationStatus())
                        || o.paidAmount().compareTo(o.expectedAmount()) >= 0
        );
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

    private List<SettlementObligationStatusResponse> buildObligationStatusResponses(
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
                paymentObligationRepository.findByParticipantIdIn(
                        participantIds
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

        Map<Long, BigDecimal> paidAmountMap = paymentRecords.stream()
                .collect(Collectors.groupingBy(
                        PaymentRecordEntity::getTargetId,
                        Collectors.mapping(
                                PaymentRecordEntity::getAmount,
                                Collectors.reducing(
                                        BigDecimal.ZERO,
                                        BigDecimal::add
                                )
                        )
                ));

        return obligations.stream()
                .map(obligation -> SettlementAssembler.toObligationStatusResponse(
                        obligation,
                        paidAmountMap.getOrDefault(obligation.getPaymentObligationId(), BigDecimal.ZERO)
                ))
                .toList();
    }

    private boolean isResolved(ObligationStatus status) {
        return status == ObligationStatus.WRITTEN_OFF
                || status == ObligationStatus.EXCLUDED
                || status == ObligationStatus.CANCELLED;
    }
}
