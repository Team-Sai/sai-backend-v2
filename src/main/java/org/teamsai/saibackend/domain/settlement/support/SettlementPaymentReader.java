package org.teamsai.saibackend.domain.settlement.support;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.payment.dto.PaymentObligationView;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecord;
import org.teamsai.saibackend.domain.payment.service.PaymentObligationQueryService;
import org.teamsai.saibackend.domain.payment.service.PaymentRecordService;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SettlementPaymentReader {

    public static final int IN_CLAUSE_CHUNK_SIZE = 500;

    private final SettlementParticipantRepository settlementParticipantRepository;
    private final PaymentObligationQueryService paymentObligationQueryService;
    private final PaymentRecordService paymentRecordService;

    @Transactional(readOnly = true)
    public SettlementPaymentData read(Long settlementId) {
        List<SettlementParticipant> participants =
                settlementParticipantRepository.findBySettlementIdAndStatus(
                        settlementId,
                        SettlementParticipantStatus.ACTIVE
                );

        if (participants.isEmpty()) {
            return SettlementPaymentData.empty();
        }

        List<Long> participantIds = participants.stream()
                .map(SettlementParticipant::getParticipantId)
                .toList();

        List<PaymentObligationView> obligations =
                paymentObligationQueryService.findByParticipantIds(
                        participantIds
                );

        if (obligations.isEmpty()) {
            return new SettlementPaymentData(
                    participants,
                    List.of(),
                    List.of(),
                    Map.of()
            );
        }

        List<Long> obligationIds = obligations.stream()
                .map(PaymentObligationView::paymentObligationId)
                .toList();

        List<PaymentRecord> paymentRecords =
                paymentRecordService.findConfirmedRecordsByTargetIds(
                        PaymentTargetType.SETTLEMENT,
                        obligationIds
                );

        return new SettlementPaymentData(
                participants,
                obligations,
                paymentRecords,
                sumPaidAmountByTargetId(paymentRecords)
        );
    }

    // 여러 정산의 결제 데이터를 청크당 쿼리 3번으로 한 번에 조회한다. (정산별 read() 반복 호출 시 N+1 발생)
    // ACTIVE 참여자가 없는 정산은 결과 Map에 포함되지 않는다.
    @Transactional(readOnly = true)
    public Map<Long, SettlementPaymentData> readAll(List<Long> settlementIds) {
        if (settlementIds == null || settlementIds.isEmpty()) {
            return Map.of();
        }

        // IN 절 파라미터가 과도하게 커지지 않도록 정산 ID를 나눠 조회한다.
        // 참여자·납부의무 ID 는 정산 수에 비례하므로 정산 ID 만 나눠도 이후 IN 절 크기가 함께 제한된다.
        Map<Long, SettlementPaymentData> result = new HashMap<>();
        for (int from = 0; from < settlementIds.size(); from += IN_CLAUSE_CHUNK_SIZE) {
            int to = Math.min(from + IN_CLAUSE_CHUNK_SIZE, settlementIds.size());
            result.putAll(readChunk(settlementIds.subList(from, to)));
        }
        return result;
    }

    private Map<Long, SettlementPaymentData> readChunk(List<Long> settlementIds) {
        List<SettlementParticipant> participants =
                settlementParticipantRepository.findBySettlementIdInAndStatus(
                        settlementIds,
                        SettlementParticipantStatus.ACTIVE
                );

        if (participants.isEmpty()) {
            return Map.of();
        }

        Map<Long, Long> settlementIdByParticipantId = participants.stream()
                .collect(Collectors.toMap(
                        SettlementParticipant::getParticipantId,
                        participant -> participant.getSettlement().getSettlementId()
                ));

        List<PaymentObligationView> obligations =
                paymentObligationQueryService.findByParticipantIds(
                        participants.stream()
                                .map(SettlementParticipant::getParticipantId)
                                .toList()
                );

        Map<Long, Long> settlementIdByObligationId = obligations.stream()
                .collect(Collectors.toMap(
                        PaymentObligationView::paymentObligationId,
                        obligation -> settlementIdByParticipantId.get(obligation.participantId())
                ));

        List<PaymentRecord> paymentRecords = obligations.isEmpty()
                ? List.of()
                : paymentRecordService.findConfirmedRecordsByTargetIds(
                        PaymentTargetType.SETTLEMENT,
                        obligations.stream()
                                .map(PaymentObligationView::paymentObligationId)
                                .toList()
                );

        Map<Long, List<SettlementParticipant>> participantsBySettlementId = participants.stream()
                .collect(Collectors.groupingBy(
                        participant -> settlementIdByParticipantId.get(participant.getParticipantId())
                ));

        Map<Long, List<PaymentObligationView>> obligationsBySettlementId = obligations.stream()
                .collect(Collectors.groupingBy(
                        obligation -> settlementIdByParticipantId.get(obligation.participantId())
                ));

        Map<Long, List<PaymentRecord>> paymentRecordsBySettlementId = paymentRecords.stream()
                .collect(Collectors.groupingBy(
                        record -> settlementIdByObligationId.get(record.getTargetId())
                ));

        return participantsBySettlementId.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            List<PaymentRecord> records =
                                    paymentRecordsBySettlementId.getOrDefault(entry.getKey(), List.of());

                            return new SettlementPaymentData(
                                    entry.getValue(),
                                    obligationsBySettlementId.getOrDefault(entry.getKey(), List.of()),
                                    records,
                                    sumPaidAmountByTargetId(records)
                            );
                        }
                ));
    }

    private Map<Long, BigDecimal> sumPaidAmountByTargetId(List<PaymentRecord> paymentRecords) {
        return paymentRecords.stream()
                .collect(Collectors.groupingBy(
                        PaymentRecord::getTargetId,
                        Collectors.mapping(
                                PaymentRecord::getAmount,
                                Collectors.reducing(
                                        BigDecimal.ZERO,
                                        BigDecimal::add
                                )
                        )
                ));
    }
}
