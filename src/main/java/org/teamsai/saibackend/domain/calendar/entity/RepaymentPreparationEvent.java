package org.teamsai.saibackend.domain.calendar.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(
        name = "repayment_preparation_event",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_preparation_user_schedule",
                        columnNames = {"user_id", "schedule_id"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_preparation_user_time",
                        columnList = "user_id,starts_at"
                ),
                @Index(
                        name = "idx_preparation_reminder_due",
                        columnList = "reminder_processed_at,starts_at,user_id"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepaymentPreparationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "reminder_processed_at")
    private Instant reminderProcessedAt;

    @Version
    @Column(name = "revision", nullable = false)
    private Long revision;

    public void reschedule(
            Instant newStart,
            Instant newEnd,
            Instant now
    ) {
        if (reminderProcessedAt != null
                || !startsAt.isAfter(now)) {
            throw new IllegalStateException(
                    "Only future unprocessed reminders can be rescheduled"
            );
        }

        if (newStart == null
                || newEnd == null
                || !newStart.isAfter(now)
                || !newEnd.isAfter(newStart)) {
            throw new IllegalArgumentException(
                    "Invalid reschedule time"
            );
        }

        this.startsAt = newStart;
        this.endsAt = newEnd;
    }
    
    public void markReminderProcessed(Instant processedAt) {
        if (processedAt == null) {
            throw new IllegalArgumentException("processedAt is required");
        }
        if (reminderProcessedAt == null) {
            reminderProcessedAt = processedAt;
        }
    }

    public RepaymentPreparationEvent(
            Long userId,
            Long contractId,
            Long scheduleId,
            String title,
            Instant startsAt,
            Instant endsAt,
            Instant createdAt
    ) {
        this.userId = userId;
        this.contractId = contractId;
        this.scheduleId = scheduleId;
        this.title = title;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.createdAt = createdAt;
    }
}