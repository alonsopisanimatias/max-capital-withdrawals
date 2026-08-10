package com.maxcapital.withdrawals.web;

import com.maxcapital.withdrawals.domain.Withdrawal;
import com.maxcapital.withdrawals.service.WithdrawalService;
import com.maxcapital.withdrawals.web.dto.CreateWithdrawalRequest;
import com.maxcapital.withdrawals.web.dto.WithdrawalResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/withdrawals")
@RequiredArgsConstructor
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WithdrawalResponse create(@Valid @RequestBody CreateWithdrawalRequest request) {
        Withdrawal withdrawal = withdrawalService.createWithdrawal(
                request.accountId(), request.destinationCbu(), request.amount());
        return WithdrawalResponse.from(withdrawal);
    }

    // no real auth for this challenge (out of scope) — the operator identity is a plain header,
    // trusted as-is, but still recorded on every transition for the audit trail.
    @PostMapping("/{id}/authorize")
    public WithdrawalResponse authorize(@PathVariable UUID id, @RequestHeader("X-Operator-Id") @NotBlank String operatorId) {
        return WithdrawalResponse.from(withdrawalService.authorize(id, operatorId));
    }

    @PostMapping("/{id}/reject")
    public WithdrawalResponse reject(@PathVariable UUID id, @RequestHeader("X-Operator-Id") @NotBlank String operatorId) {
        return WithdrawalResponse.from(withdrawalService.reject(id, operatorId));
    }

    @PostMapping("/{id}/retry")
    public WithdrawalResponse retry(@PathVariable UUID id, @RequestHeader("X-Operator-Id") @NotBlank String operatorId) {
        return WithdrawalResponse.from(withdrawalService.retry(id, operatorId));
    }
}
