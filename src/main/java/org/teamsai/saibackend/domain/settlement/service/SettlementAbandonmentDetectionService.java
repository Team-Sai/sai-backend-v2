package org.teamsai.saibackend.domain.settlement.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.batch.common.notification.SlackNotifier;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementAbandonmentAlert;
import org.teamsai.saibackend.domain.settlement.repository.SettlementAbandonmentAlertRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.support.OverdueCriteria;
import org.teamsai.saibackend.domain.settlement.support.SettlementAbandonmentResult;
import org.teamsai.saibackend.domain.settlement.type.SettlementStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementAbandonmentDetectionService {

    private static final int PAGE_SIZE = 100;
    private static final int ABANDONMENT_DAYS_AFTER_DUE = 3;

    private final OverdueCriteria overdueCriteria;
    private final SettlementRepository settlementRepository;
    private final SettlementAbandonmentAlertRepository abandonmentAlertRepository;
    private final SlackNotifier slackNotifier;

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

                boolean alreadyNotified =
                        abandonmentAlertRepository.existsBySettlementIdAndReferenceDate(
                                settlement.getSettlementId(),
                                referenceDate
                        );

                if (alreadyNotified) {
                    continue;
                }

                try {
                    SettlementAbandonmentAlert alert =
                            SettlementAbandonmentAlert.create(
                                    settlement.getSettlementId(),
                                    referenceDate,
                                    LocalDateTime.now()
                            );

                    abandonmentAlertRepository.saveAndFlush(alert);
                } catch (DataIntegrityViolationException e) {
                    log.debug(
                            "이미 처리된 정산 포기 알림입니다. settlementId={}, referenceDate={}",
                            settlement.getSettlementId(),
                            referenceDate
                    );
                    continue;
                }

                notifyAbandoned(settlement, referenceDate, baseDate);
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

    private void notifyAbandoned(Settlement settlement, LocalDate referenceDate, LocalDate baseDate) {
        long daysOverdue = java.time.temporal.ChronoUnit.DAYS.between(referenceDate, baseDate);
        slackNotifier.send(String.format(
                "⚠️ *정산 장기 방치 감지* — settlementId=%d, title=%s, 기준일=%s (%d일 경과), 여전히 IN_PROGRESS",
                settlement.getSettlementId(),
                settlement.getTitle(),
                referenceDate,
                daysOverdue
        ));
    }
}