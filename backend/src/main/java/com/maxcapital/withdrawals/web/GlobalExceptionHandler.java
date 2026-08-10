package com.maxcapital.withdrawals.web;

import com.maxcapital.withdrawals.domain.Withdrawal;
import com.maxcapital.withdrawals.repository.WithdrawalRepository;
import com.maxcapital.withdrawals.service.InsufficientBalanceException;
import com.maxcapital.withdrawals.service.InvalidTransitionException;
import com.maxcapital.withdrawals.web.dto.ConflictResponse;
import com.maxcapital.withdrawals.web.dto.ErrorResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final WithdrawalRepository withdrawalRepository;

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientBalance(InsufficientBalanceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("INSUFFICIENT_BALANCE", ex.getMessage()));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", ex.getMessage()));
    }

    // C4: an operator acting on a withdrawal that's no longer in the state they expect
    // (e.g. someone else already resolved it) — 409 with the real current state, not a 500.
    @ExceptionHandler(InvalidTransitionException.class)
    public ResponseEntity<ConflictResponse> handleInvalidTransition(InvalidTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ConflictResponse("INVALID_TRANSITION", ex.getMessage(), ex.getCurrentStatus(), ex.getCurrentUpdatedBy()));
    }

    // C4, the race itself: two operators both passed the precondition check and both committed
    // an UPDATE — @Version guarantees only one succeeds. The loser lands here, not the one above.
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ConflictResponse> handleOptimisticLockConflict(ObjectOptimisticLockingFailureException ex) {
        String message = "This withdrawal was just updated by someone else — reload it and try again.";
        if (ex.getIdentifier() instanceof UUID withdrawalId) {
            Withdrawal current = withdrawalRepository.findById(withdrawalId).orElse(null);
            if (current != null) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(new ConflictResponse("CONFLICT", message, current.getStatus(), current.getUpdatedBy()));
            }
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ConflictResponse("CONFLICT", message, null, null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationError(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", message));
    }
}
