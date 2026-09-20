package org.teamsai.saibackend.domain.account.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;
import org.teamsai.saibackend.domain.account.repository.LinkedBankAccountRepository;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LinkedAccountWriter {
    private final LinkedBankAccountRepository accounts;

    @Transactional
    public List<LinkedBankAccount> insertAll(List<LinkedBankAccount> candidates) {
        List<LinkedBankAccount> saved = new ArrayList<>();
        for (LinkedBankAccount candidate : candidates) {
            Long id = accounts.insertOne(candidate);
            if (id != null) {
                saved.add(accounts.findById(id).orElseThrow());
            }
        }
        return saved;
    }
}
