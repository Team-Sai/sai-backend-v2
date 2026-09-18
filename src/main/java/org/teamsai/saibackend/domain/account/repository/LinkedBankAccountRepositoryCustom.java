package org.teamsai.saibackend.domain.account.repository;

import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;

public interface LinkedBankAccountRepositoryCustom {
    Long insertOne(LinkedBankAccount account);
}
