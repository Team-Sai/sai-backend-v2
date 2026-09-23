package org.teamsai.saibackend.domain.settlement.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecordEntity;
import org.teamsai.saibackend.domain.payment.service.PaymentRecordService;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.settlement.assembler.SettlementAssembler;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentHistoryResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentObligationResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;
import org.teamsai.saibackend.domain.transaction.entity.BankTransactionEntity;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionService;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SettlementPaymentHistoryService {

    private final SettlementPaymentStatusService settlementPaymentStatusService;
    private final PaymentRecordService paymentRecordService;
    private final BankTransactionService bankTransactionService;

    @Transactional(readOnly = true)
    public List<SettlementPaymentHistoryResponse> getPaymentHistory(Long settlementId, Long userId) {
        SettlementPaymentStatusResponse paymentStatus =
                settlementPaymentStatusService.getPaymentStatus(settlementId, userId);

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
