package org.teamsai.saibackend.domain.archive.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "archive_file")
public class ArchiveFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long fileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "domain_type", nullable = false, length = 50)
    private ArchiveStatus domainType;      // 도메인 구분 (예: CONTRACT, SETTLEMENT)

    @Column(name = "reference_id", nullable = false)
    private Long referenceId;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "saved_filename", nullable = false)
    private String savedFilename;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "file_type")
    private String fileType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public ArchiveFile(
            ArchiveStatus domainType,
            Long referenceId,
            String originalFilename,
            String savedFilename,
            Long fileSize,
            String fileType
    ) {
        this.domainType = domainType;
        this.referenceId = referenceId;
        this.originalFilename = originalFilename;
        this.savedFilename = savedFilename;
        this.fileSize = fileSize;
        this.fileType = fileType;
    }
}
