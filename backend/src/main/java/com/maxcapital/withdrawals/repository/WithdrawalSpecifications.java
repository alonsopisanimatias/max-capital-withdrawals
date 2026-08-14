package com.maxcapital.withdrawals.repository;

import com.maxcapital.withdrawals.domain.Withdrawal;
import com.maxcapital.withdrawals.domain.WithdrawalStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

/**
 * Each method returns null when its filter isn't supplied — Spring Data's Specification.and()
 * treats a null predicate as "no constraint" rather than "match nothing", so callers can chain
 * every filter unconditionally without a wall of if-statements building the query by hand.
 */
public final class WithdrawalSpecifications {

    private WithdrawalSpecifications() {
    }

    public static Specification<Withdrawal> hasStatus(WithdrawalStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    public static Specification<Withdrawal> createdFrom(Instant from) {
        return (root, query, cb) -> from == null ? null : cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    public static Specification<Withdrawal> createdTo(Instant to) {
        return (root, query, cb) -> to == null ? null : cb.lessThanOrEqualTo(root.get("createdAt"), to);
    }

    /**
     * "Search by account, CBU, or withdrawal id" (matches the operator-facing requirement):
     * partial match on the destination CBU, partial case-insensitive match on the withdrawal's
     * own id, plus an exact match on the account id when the search term happens to parse as a
     * UUID. There's no denormalized account number on withdrawal to search against without
     * joining account — out of scope for this backoffice screen, documented in DECISIONS.md
     * rather than added as an undiscussed join.
     */
    public static Specification<Withdrawal> matchesSearch(String search) {
        return (root, query, cb) -> {
            if (search == null || search.isBlank()) {
                return null;
            }
            String trimmed = search.trim();
            Predicate cbuMatch = cb.like(root.get("destinationCbu"), "%" + trimmed + "%");
            Predicate idMatch = cb.like(cb.lower(root.get("id").as(String.class)), "%" + trimmed.toLowerCase() + "%");
            try {
                Predicate accountMatch = cb.equal(root.get("accountId"), UUID.fromString(trimmed));
                return cb.or(cbuMatch, idMatch, accountMatch);
            } catch (IllegalArgumentException notAUuid) {
                return cb.or(cbuMatch, idMatch);
            }
        };
    }
}
