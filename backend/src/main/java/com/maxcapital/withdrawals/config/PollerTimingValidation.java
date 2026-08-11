package com.maxcapital.withdrawals.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fails startup fast if the reconciliation grace period isn't safely larger than the transfer
 * poller's client-side bank-call timeout. DECISIONS.md section 8 (C6) documents why this has to
 * hold: reconciliation only asks the bank about a withdrawal once it's confident the original
 * call can no longer be in flight (worst case: bank's own 3-10s + the client timeout). If the
 * grace period ever dropped to or below the timeout, reconciliation could start querying —
 * and, worse, resetting a withdrawal back to AUTHORIZED for a fresh attempt — while the
 * original bank call might still be running, which is exactly the double-transfer C6 exists to
 * prevent. This was previously only a comment in DECISIONS.md, not anything that would stop a
 * misconfigured deployment from starting.
 *
 * <p>{@code @Profile("!test")}: application-test.yml deliberately shrinks the grace period to 2s
 * so tests don't sleep 30s, while the bank-call timeout stays at its production default (15s) —
 * that's fine there because {@code TestBankService} resolves instantly, so there's no real
 * in-flight-call window for the invariant to protect against in the first place.
 */
@Component
@Profile("!test")
public class PollerTimingValidation {

    @Value("${withdrawals.poller.transfer-execution.bank-call-timeout-seconds}")
    private int bankCallTimeoutSeconds;

    @Value("${withdrawals.poller.reconciliation.grace-period-seconds}")
    private int gracePeriodSeconds;

    @PostConstruct
    void validate() {
        if (gracePeriodSeconds <= bankCallTimeoutSeconds) {
            throw new IllegalStateException(
                    "withdrawals.poller.reconciliation.grace-period-seconds (" + gracePeriodSeconds
                            + ") must be strictly greater than withdrawals.poller.transfer-execution.bank-call-timeout-seconds ("
                            + bankCallTimeoutSeconds + ") — otherwise reconciliation could query (and reset) a "
                            + "withdrawal while the original bank call might still be in flight (C6).");
        }
    }
}
