package org.teamsai.saibackend.domain.settlement.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.batch.common.notification.SlackNotifier;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.exception.PaymentErrorCode;
import org.teamsai.saibackend.domain.payment.repository.PaymentObligationRepository;
import org.teamsai.saibackend.domain.payment.service.SettlementPaymentService;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.settlement.dto.SettlementAccountDTO;
import org.teamsai.saibackend.domain.settlement.entity.RecurringSettlement;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.mapper.SettlementAccountMapper;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.type.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecurringSettlementCycleGenerator {

    private final SettlementRepository settlementRepository;
    private final SettlementParticipantRepository settlementParticipantRepository;
    private final PaymentObligationRepository paymentObligationRepository;
    private final SettlementPaymentService settlementPaymentService;
    private final SettlementAmountCalculator settlementAmountCalculator;
    private final SettlementAccountMapper settlementAccountMapper;
    private final SlackNotifier slackNotifier;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CycleGenerationOutcome generateOneCycle(RecurringSettlement recurring, Settlement previousSettlement, LocalDate cycleDate) {
        Settlement lockedLatest =
                settlementRepository.findLatestByRecurringIdForUpdate(
                                recurring.getRecurringSettlementId(),
                                PageRequest.of(0, 1)
                        )
                        .stream()
                        .findFirst()
                        .orElse(null);
        if (lockedLatest == null || !lockedLatest.getSettlementId().equals(previousSettlement.getSettlementId())) {
            log.warn("동시 생성 감지, 스킵 recurringId={}", recurring.getRecurringSettlementId());
            return CycleGenerationOutcome.concurrentlySkipped();
        }

        List<SettlementParticipant> activeParticipants =
                settlementParticipantRepository.findBySettlementIdAndStatus(
                        previousSettlement.getSettlementId(),
                        SettlementParticipantStatus.ACTIVE
                );
        if (activeParticipants.isEmpty()) {
            log.warn("ACTIVE 참여자 없음, 생성 스킵 recurringId={}, cycleDate={}",
                    recurring.getRecurringSettlementId(), cycleDate);
            return CycleGenerationOutcome.noActiveParticipant();
        }

        Settlement newSettlement = Settlement.builder()
                .recurringSettlement(recurring)
                .owner(recurring.getOwner())
                .settlementType(SettlementType.RECURRING)
                .settlementStatus(SettlementStatus.IN_PROGRESS)
                .settlementCategory(recurring.getSettlementCategory())
                .title(recurring.getTitle())
                .splitType(recurring.getSplitType())
                .totalAmount(recurring.getTotalAmount())
                .cycleDate(cycleDate)
                .dueDate(null)
                .createdAt(LocalDateTime.now())
                .build();

        Settlement savedSettlement =
                settlementRepository.save(newSettlement);

        if (recurring.getSplitType() == SplitType.EQUAL) {
            copyParticipantsWithEqualSplit(activeParticipants, savedSettlement, recurring.getTotalAmount());
        } else {
            copyParticipantsWithCustomAmounts(activeParticipants, savedSettlement);
        }

        copySettlementAccount(previousSettlement.getSettlementId(), savedSettlement.getSettlementId());

        return CycleGenerationOutcome.created(savedSettlement);
    }

    private void copySettlementAccount(Long previousSettlementId, Long newSettlementId) {
        Optional<SettlementAccountDTO> previousAccount =
                settlementAccountMapper.findActiveBySettlementId(previousSettlementId);

        if (previousAccount.isEmpty()) {
            log.warn("직전 회차에 연결된 계좌 없음, 계좌 승계 스킵 previousSettlementId={}", previousSettlementId);
            return;
        }

        SettlementAccountDTO newAccount = SettlementAccountDTO.builder()
                .settlementId(newSettlementId)
                .linkedAccountId(previousAccount.get().getLinkedAccountId())
                .accountStatus(SettlementAccountStatus.ACTIVE)
                .selectedAt(LocalDateTime.now())
                .endedAt(null)
                .build();

        int insertedCount = settlementAccountMapper.insert(newAccount);
        if (insertedCount != 1) {
            log.error("정산 계좌 승계 실패 newSettlementId={}", newSettlementId);
            slackNotifier.send(
                    "[정기정산] 계좌 승계 실패 - settlementId=" + newSettlementId
                            + " (이 회차는 은행거래 자동매칭이 되지 않습니다. 수동 확인 필요)"
            );
        }
    }

    private void copyParticipantsWithEqualSplit(
            List<SettlementParticipant> activeParticipants, Settlement newSettlement, BigDecimal totalAmount
    ) {
        BigDecimal perPersonAmount =
                settlementAmountCalculator.calculateEqualAmount(
                        totalAmount,
                        activeParticipants.size()
                );

        for (SettlementParticipant oldParticipant : activeParticipants) {
            copyParticipantWithObligation(
                    oldParticipant,
                    newSettlement,
                    perPersonAmount
            );
        }
    }

    private void copyParticipantsWithCustomAmounts(
            List<SettlementParticipant> activeParticipants, Settlement newSettlement
    ) {
        List<Long> participantIds = activeParticipants.stream()
                .map(SettlementParticipant::getParticipantId)
                .toList();

        Map<Long, BigDecimal> latestObligationByParticipant = paymentObligationRepository
                .findLatestByParticipantIdsAndObligationStatuses(
                        participantIds,
                        List.of(
                                ObligationStatus.ACTIVE,
                                ObligationStatus.EXCLUDED,
                                ObligationStatus.CANCELLED,
                                ObligationStatus.WRITTEN_OFF
                        ))
                .stream()
                .collect(Collectors.toMap(PaymentObligationEntity::getParticipantId, PaymentObligationEntity::getExpectedAmount));

        for (SettlementParticipant oldParticipant : activeParticipants) {
            BigDecimal expectedAmount = latestObligationByParticipant.get(oldParticipant.getParticipantId());
            if (expectedAmount == null) {
                throw PaymentErrorCode.PAYMENT_OBLIGATION_NOT_FOUND.toException();
            }
            copyParticipantWithObligation(oldParticipant, newSettlement, expectedAmount);
        }
    }

    private void copyParticipantWithObligation(
            SettlementParticipant oldParticipant, Settlement newSettlement, BigDecimal expectedAmount
    ) {
        SettlementParticipant newParticipant = SettlementParticipant.builder()
                .user(oldParticipant.getUser())
                .settlement(newSettlement)
                .participantRole(oldParticipant.getParticipantRole())
                .participantStatus(SettlementParticipantStatus.ACTIVE)
                .joinedAt(LocalDateTime.now())
                .build();

        SettlementParticipant savedParticipant =
                settlementParticipantRepository.save(newParticipant);

        settlementPaymentService.createObligation(savedParticipant.getParticipantId(), expectedAmount);
    }

    public LocalDate calculateNthCycleDate(LocalDate startDate, CycleRule cycleRule, int n) {
        return switch (cycleRule) {
            case DAILY -> startDate.plusDays(n);
            case WEEKLY -> startDate.plusWeeks(n);
            case MONTHLY -> clampToMonth(YearMonth.from(startDate).plusMonths(n), startDate.getDayOfMonth());
            case YEARLY -> clampToMonth(YearMonth.of(startDate.getYear() + n, startDate.getMonthValue()), startDate.getDayOfMonth());
        };
    }

    private LocalDate clampToMonth(YearMonth targetMonth, int anchorDay) {
        int actualDay = Math.min(anchorDay, targetMonth.lengthOfMonth());
        return targetMonth.atDay(actualDay);
    }
}