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
                    if (!recorder.recordIfAbsent(settlement.getSettlementId(), referenceDate)) {
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

                notifier.notifyAbandoned(settlement, referenceDate, baseDate);
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

}
