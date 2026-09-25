package org.teamsai.saibackend.domain.settlement.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.support.OverdueCriteria;
import org.teamsai.saibackend.domain.settlement.support.OverdueUpdateResult;
import org.teamsai.saibackend.domain.settlement.type.SettlementStatus;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OverdueSettlementService {

    private static final int PAGE_SIZE = 100;

    private final OverdueCriteria overdueCriteria;
    private final SettlementRepository settlementRepository;
    private final OverdueSettlementUpdateService overdueSettlementUpdateService;

    public OverdueUpdateResult updateOverdueStatus(LocalDate baseDate) {
        long totalCount = settlementRepository.countBySettlementStatus(
                SettlementStatus.IN_PROGRESS
        );
        log.info("연체 상태 갱신 배치 시작, 대상 정산 총 {}건, baseDate={}", totalCount, baseDate);

        int pageNumber = 0;
        int processedCount = 0;
        int failedCount = 0;

        while (true) {
            List<Settlement> page =
                    settlementRepository.findBySettlementStatusOrderBySettlementIdAsc(
                            SettlementStatus.IN_PROGRESS,
                            PageRequest.of(pageNumber, PAGE_SIZE)
                    );

            if (page.isEmpty()) {
                break;
            }

            for (Settlement settlement : page) {
                LocalDate referenceDate = overdueCriteria.resolveReferenceDate(settlement);

                if (!overdueCriteria.isOverdue(settlement, baseDate, referenceDate)) {
                    continue;
                }

                try {
                    overdueSettlementUpdateService.updateOverdueForSettlement(
                            settlement,
                            referenceDate
                    );
                    processedCount++;
                } catch (Exception e) {
                    failedCount++;
                    log.error("연체 상태 갱신 실패, 다음 배치에서 재시도 예정 settlementId={}",
                            settlement.getSettlementId(), e);
                }
            }

            if (page.size() < PAGE_SIZE) {
                break;
            }

            pageNumber++;
        }

        log.info("연체 상태 갱신 배치 종료, 성공 {}건 / 실패 {}건 (총 {}건 중)",
                processedCount, failedCount, totalCount);

        return new OverdueUpdateResult(processedCount, failedCount);
    }
}