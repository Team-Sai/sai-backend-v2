package org.teamsai.saibackend.domain.batch.common.reader;

import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.database.JpaPagingItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.account.dto.LinkedAccountSyncTargetDTO;

import java.util.Map;

import static org.teamsai.saibackend.domain.account.type.ConnectionStatus.AVAILABLE;

@Component
@RequiredArgsConstructor
public class LinkedAccountSyncTargetReaderFactory {

    private static final int PAGE_SIZE = 50;

    private final EntityManagerFactory entityManagerFactory;

    public JpaPagingItemReader<LinkedAccountSyncTargetDTO> create(String readerName) {
        return new JpaPagingItemReaderBuilder<LinkedAccountSyncTargetDTO>()
                .name(readerName)
                .entityManagerFactory(entityManagerFactory)
                .queryString("""
                        select new org.teamsai.saibackend.domain.account.dto.LinkedAccountSyncTargetDTO(
                            a.linkedAccountId,
                            a.userId
                        )
                        from LinkedBankAccount a
                        where a.connectionStatus = :status
                        order by a.linkedAccountId
                        """)
                .parameterValues(Map.of(
                        "status", AVAILABLE
                ))
                .pageSize(PAGE_SIZE)
                .build();
    }
}