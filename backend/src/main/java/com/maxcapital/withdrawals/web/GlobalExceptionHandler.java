package com.maxcapital.withdrawals.web;

import com.maxcapital.withdrawals.domain.Withdrawal;
import com.maxcapital.withdrawals.repository.WithdrawalRepository;
import com.maxcapital.withdrawals.service.InsufficientBalanceException;
import com.maxcapital.withdrawals.service.InvalidTransitionException;
import com.maxcapital.withdrawals.web.dto.ConflictResponse;
import com.maxcapital.withdrawals.web.dto.ErrorResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
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

    // A DB-level constraint (check/unique/FK) rejected the write — bean validation is the first
    // line of defense (see amount's @Digits), this is the backstop so a gap between the two
    // still surfaces as a clean 400, not an opaque 500.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_REQUEST", "The request violates a data constraint."));
    }

    // A required header (X-Operator-Id on every operator action) was missing. Spring resolves
    // this itself as a 400 by default — but registering the catch-all Exception handler below
    // intercepts it first unless there's a more specific handler here, silently turning a clean
    // 400 into an opaque 500. Found via manual HTTP-level testing (Postman): none of the
    // automated tests call these endpoints over real HTTP, so nothing else would have caught it.
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", ex.getMessage()));
    }

    // A path variable didn't match its declared type — e.g. {id} in /withdrawals/{id} isn't a
    // valid UUID. Same category of gap as the header case above: this used to be Spring's own
    // 400 until the catch-all below started shadowing it.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = ex.getName() + " has an invalid value: " + ex.getValue();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", message));
    }

    // last resort — anything unmapped becomes a clean, non-leaking 500 instead of a raw stack
    // trace / default Spring error page reaching the client.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred."));
    }
}
