package com.maxcapital.withdrawals.web;

import com.maxcapital.withdrawals.domain.Withdrawal;
import com.maxcapital.withdrawals.domain.WithdrawalStatus;
import com.maxcapital.withdrawals.repository.TransferRepository;
import com.maxcapital.withdrawals.repository.WithdrawalStatusHistoryRepository;
import com.maxcapital.withdrawals.service.WithdrawalService;
import com.maxcapital.withdrawals.web.dto.CreateWithdrawalRequest;
import com.maxcapital.withdrawals.web.dto.WithdrawalDetailResponse;
import com.maxcapital.withdrawals.web.dto.WithdrawalResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/withdrawals")
@RequiredArgsConstructor
// required for @NotBlank on @RequestHeader params to actually be enforced — unlike
// @Valid @RequestBody, method-parameter constraints (@RequestHeader/@RequestParam/@PathVariable)
// are silently no-ops without this. Without it, X-Operator-Id: "" (present but blank) passed
// straight through and got persisted as the actor in withdrawal_status_history.
@Validated
public class WithdrawalController {

    private final WithdrawalService withdrawalService;
    private final TransferRepository transferRepository;
    private final WithdrawalStatusHistoryRepository historyRepository;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WithdrawalResponse create(@Valid @RequestBody CreateWithdrawalRequest request) {
        Withdrawal withdrawal = withdrawalService.createWithdrawal(
                request.accountId(), request.destinationCbu(), request.amount());
        return WithdrawalResponse.from(withdrawal);
    }

    @GetMapping
    public Page<WithdrawalResponse> search(
            @RequestParam(required = false) WithdrawalStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateTo,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return withdrawalService.search(status, dateFrom, dateTo, search, pageable).map(WithdrawalResponse::from);
    }

    @GetMapping("/{id}")
    public WithdrawalDetailResponse getById(@PathVariable UUID id) {
        Withdrawal withdrawal = withdrawalService.getById(id);
        return WithdrawalDetailResponse.from(withdrawal, transferRepository.findByWithdrawalId(id).orElse(null),
                historyRepository.findByWithdrawalIdOrderByOccurredAt(id));
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

    @PostMapping("/{id}/resolve-manual-review")
    public WithdrawalResponse resolveManualReview(@PathVariable UUID id, @RequestHeader("X-Operator-Id") @NotBlank String operatorId) {
        return WithdrawalResponse.from(withdrawalService.resolveManualReview(id, operatorId));
    }
}
