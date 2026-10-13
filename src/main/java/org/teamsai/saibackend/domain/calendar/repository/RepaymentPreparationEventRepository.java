package org.teamsai.saibackend.domain.calendar.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.calendar.entity.RepaymentPreparationEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RepaymentPreparationEventRepository
        extends JpaRepository<RepaymentPreparationEvent, Long> {

    Optional<RepaymentPreparationEvent> findByEventIdAndUserId(
            Long eventId,
            Long userId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select e
        from RepaymentPreparationEvent e
        where e.eventId = :eventId
          and e.userId = :userId
        """)
    Optional<RepaymentPreparationEvent> findOwnedEventForUpdate(
            @Param("eventId") Long eventId,
            @Param("userId") Long userId
    );

    Optional<RepaymentPreparationEvent> findByUserIdAndScheduleId(
            Long userId,
            Long scheduleId
    );

    @Query("""
            select e
            from RepaymentPreparationEvent e
            where e.userId = :userId
              and e.startsAt < :rangeEnd
              and e.endsAt > :rangeStart
            order by e.startsAt asc, e.eventId asc
            """)
    List<RepaymentPreparationEvent> findOverlapping(
            @Param("userId") Long userId,
            @Param("rangeStart") Instant rangeStart,
            @Param("rangeEnd") Instant rangeEnd
    );

    List<RepaymentPreparationEvent> findByUserIdAndScheduleIdIn(
            Long userId,
            List<Long> scheduleIds
    );

    @Query("""
    select distinct e.userId
    from RepaymentPreparationEvent e
    where e.reminderProcessedAt is null
      and e.startsAt <= :cutoff
      and e.userId > :afterUserId
    order by e.userId asc
    """)
    List<Long> findDueReminderUserIdsAfter(
            @Param("cutoff") Instant cutoff,
            @Param("afterUserId") Long afterUserId,
            Pageable pageable
    );
    @Query("""
        select e
        from RepaymentPreparationEvent e
        where e.userId = :userId
          and e.reminderProcessedAt is null
          and e.startsAt <= :cutoff
        order by e.startsAt, e.eventId
        """)
    List<RepaymentPreparationEvent> findDueRemindersByUserId(
            @Param("userId") Long userId,
            @Param("cutoff") Instant cutoff
    );
}