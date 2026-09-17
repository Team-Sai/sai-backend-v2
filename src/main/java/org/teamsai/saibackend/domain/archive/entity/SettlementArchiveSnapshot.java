package org.teamsai.saibackend.domain.archive.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "settlement_archive_snapshot")
public class SettlementArchiveSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long settlementArchiveSnapshotId;

    @Column(name = "settlement_id", nullable = false, unique = true)
    private Long settlementId;

    @Lob
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "LONGTEXT")
    private String snapshotJson;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public SettlementArchiveSnapshot(Long settlementId, String snapshotJson) {
        this.settlementId = settlementId;
        this.snapshotJson = snapshotJson;
    }
}
