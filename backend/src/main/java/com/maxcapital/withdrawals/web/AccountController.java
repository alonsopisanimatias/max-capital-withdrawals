package com.maxcapital.withdrawals.web;

import com.maxcapital.withdrawals.repository.AccountRepository;
import com.maxcapital.withdrawals.web.dto.AccountResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Convenience listing for manual testing/demo — not part of the evaluated domain (retiros), just seed data visibility. */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountRepository accountRepository;

    @GetMapping
    public List<AccountResponse> list() {
        return accountRepository.findAll().stream().map(AccountResponse::from).toList();
    }
}
