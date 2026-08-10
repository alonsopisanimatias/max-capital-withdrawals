package com.maxcapital.withdrawals.repository;

import com.maxcapital.withdrawals.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountRepositoryTest extends AbstractIntegrationTest {

    // seeded by V2__seed_data.sql
    private static final UUID ACC_002_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void flywayAppliesMigrationsAndSeedData() {
        assertThat(accountRepository.findAll()).hasSize(3);
    }

    @Test
    void mapsAccountFieldsCorrectly() {
        var account = accountRepository.findById(ACC_002_ID).orElseThrow();

        assertThat(account.getAccountNumber()).isEqualTo("ACC-002");
        assertThat(account.getHolderName()).isEqualTo("Maria Gomez");
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("50000.00"));
        assertThat(account.getReservedBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
