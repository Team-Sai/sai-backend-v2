package org.teamsai.saibackend.domain.batch.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.repository.PaymentObligationRepository;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WriteOffTransactionExecutor {

    private static final int CHUNK_SIZE = 500;

    private final PaymentObligationRepository paymentObligationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int writeOffOneBatch(List<Long> obligationIds) {
        int total = 0;

        for (List<Long> chunk : partition(obligationIds, CHUNK_SIZE)) {
            List<PaymentObligationEntity> obligations =
                    paymentObligationRepository.findWriteOffTargetsForUpdate(
                            chunk,
                            ObligationStatus.ACTIVE,
                            List.of(
                                    PaymentStatus.UNPAID,
                                    PaymentStatus.PARTIALLY_PAID
                            )
                    );

            for (PaymentObligationEntity obligation : obligations) {
                obligation.writeOff();
            }

            total += obligations.size();
        }

        return total;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int writeOffSchedulesInNewTransaction(List<Long> scheduleIds) {
        int total = 0;
        for (List<Long> chunk : partition(scheduleIds, CHUNK_SIZE)) {
            total += paymentObligationRepository.writeOffBulk(chunk);
        }
        log.info("상환 스케줄 상각 처리, {}건", total);
        return total;
    }

    private List<List<Long>> partition(List<Long> list, int size) {
        List<List<Long>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }
}
