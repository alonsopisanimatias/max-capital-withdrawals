package com.maxcapital.withdrawals.repository;

import com.maxcapital.withdrawals.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountRepositoryTest extends AbstractIntegrationTest {

    // seeded by V2__seed_data.sql
    private static final UUID ACC_001_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACC_002_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACC_003_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void flywayAppliesMigrationsAndSeedData() {
        // asserts the specific seeded rows exist rather than the table's total row count:
        // other test classes share this same Postgres instance and add their own accounts
        // (see AbstractIntegrationTest's singleton container pattern), so an exact count here
        // would be a false failure depending on which tests ran before this one.
        assertThat(accountRepository.findById(ACC_001_ID)).isPresent();
        assertThat(accountRepository.findById(ACC_002_ID)).isPresent();
        assertThat(accountRepository.findById(ACC_003_ID)).isPresent();
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
