package org.teamsai.saibackend.domain.batch.settlement.abandonment;

import lombok.RequiredArgsConstructor;
import org.teamsai.saibackend.domain.settlement.support.SettlementAbandonmentRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.support.OverdueCriteria;
import org.teamsai.saibackend.domain.settlement.support.SettlementAbandonmentResult;
import org.teamsai.saibackend.domain.settlement.type.SettlementStatus;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementAbandonmentDetectionService {

    private static final int PAGE_SIZE = 100;
    private static final int ABANDONMENT_DAYS_AFTER_DUE = 3;

    private final OverdueCriteria overdueCriteria;
    private final SettlementRepository settlementRepository;
    private final SettlementAbandonmentRecorder recorder;
    private final SettlementAbandonmentNotifier notifier;

    public SettlementAbandonmentResult detectAbandoned(LocalDate baseDate) {
        SettlementAbandonmentResult result = null;
        RuntimeException detectionFailure = null;
        try {
            result = detectAndRecord(baseDate);
        } catch (RuntimeException e) {
            detectionFailure = e;
        }

        // Delivery uses its own transactions and must run even if detection failed.
        try {
            deliverPending();
        } catch (RuntimeException deliveryFailure) {
            if (detectionFailure == null) {
                throw deliveryFailure;
            }
            if (detectionFailure != deliveryFailure) {
                detectionFailure.addSuppressed(deliveryFailure);
            }
        }
        if (detectionFailure != null) {
            throw detectionFailure;
        }
        return result;
    }

    private SettlementAbandonmentResult detectAndRecord(LocalDate baseDate) {
        long totalCount = settlementRepository.countBySettlementStatus(SettlementStatus.IN_PROGRESS);
        log.info("정산 장기방치 감지 배치 시작, 대상 정산 총 {}건, baseDate={}", totalCount, baseDate);

        int detectedCount = 0;
        int pageNumber = 0;

        while (true) {
            List<Settlement> page = settlementRepository.findBySettlementStatusOrderBySettlementIdAsc( SettlementStatus.IN_PROGRESS,
                    PageRequest.of(pageNumber, PAGE_SIZE));
            if (page.isEmpty()) {
                break;
            }

            for (Settlement settlement : page) {
                LocalDate referenceDate = overdueCriteria.resolveReferenceDate(settlement);

                if (!isAbandoned(referenceDate, baseDate)) {
                    continue;
                }

                try {
                    if (!recorder.recordIfAbsent(settlement.getSettlementId(), referenceDate,
                            buildMessage(settlement, referenceDate, baseDate))) {
                        continue;
                    }
                } catch (DataIntegrityViolationException e) {
                    // The failed insert has already rolled back in its own transaction.
                    if (!recorder.exists(settlement.getSettlementId(), referenceDate)) {
                        throw e;
                    }
                    log.debug("이미 처리된 정산 포기 알림입니다. settlementId={}, referenceDate={}",
                            settlement.getSettlementId(), referenceDate);
                    continue;
                }

                detectedCount++;
            }

            if (page.size() < PAGE_SIZE) {
                break;
            }
            pageNumber++;
        }

        log.info("정산 장기방치 감지 배치 종료, 신규 방치 감지 {}건 (총 {}건 중)", detectedCount, totalCount);
        return new SettlementAbandonmentResult(detectedCount);
    }

    private boolean isAbandoned(LocalDate referenceDate, LocalDate baseDate) {
        if (referenceDate == null) {
            return false;
        }
        return !referenceDate.plusDays(ABANDONMENT_DAYS_AFTER_DUE).isAfter(baseDate);
    }


    private void deliverPending() {
        Long afterId = null;
        LocalDate afterDate = null;
        while (true) {
            var pending = recorder.pendingAfter(afterId, afterDate);
            if (pending.isEmpty()) {
                return;
            }
            for (var alert : pending) {
                try {
                    notifier.sendPending(alert.getSettlementId(), alert.getReferenceDate());
                } catch (RuntimeException e) {
                    log.error("방치 알림 발송 실패, 다음 실행에서 재시도 settlementId={}, referenceDate={}",
                            alert.getSettlementId(), alert.getReferenceDate(), e);
                }
            }
            // Keyset pagination keeps failed records pending without looping or skipping other records.
            var last = pending.get(pending.size() - 1);
            afterId = last.getSettlementId();
            afterDate = last.getReferenceDate();
        }
    }

    private String buildMessage(Settlement settlement, LocalDate referenceDate, LocalDate baseDate) {
        return String.format(
                "⚠️ *정산 장기 방치 감지* — settlementId=%d, title=%s, 기준일=%s (%d일 경과), 감지일=%s에 IN_PROGRESS",
                settlement.getSettlementId(), settlement.getTitle(), referenceDate,
                java.time.temporal.ChronoUnit.DAYS.between(referenceDate, baseDate), baseDate);
    }
}
