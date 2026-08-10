package com.maxcapital.withdrawals.web;

import com.maxcapital.withdrawals.domain.Withdrawal;
import com.maxcapital.withdrawals.service.WithdrawalService;
import com.maxcapital.withdrawals.web.dto.CreateWithdrawalRequest;
import com.maxcapital.withdrawals.web.dto.WithdrawalResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
}
