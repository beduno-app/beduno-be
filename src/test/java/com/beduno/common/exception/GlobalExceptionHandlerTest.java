package com.beduno.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two database-level failures that user input can still reach. Both used to fall through to
 * the catch-all and report INTERNAL_ERROR, telling callers the server was broken when the correct
 * answer was "someone got there first, retry". They are exercised directly because neither is
 * reproducible over HTTP on demand: the services close both cases with a check-then-act pre-check,
 * and only a genuine race gets past it.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shouldReturnConflict_whenDatabaseRejectsAWrite() {
        var response = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error()).isEqualTo("CONFLICT");
        assertThat(response.getBody().message()).isEqualTo("error.conflict");
    }

    @Test
    void shouldReturnConflict_whenOptimisticLockIsLost() {
        var response = handler.handleOptimisticLock(
                new ObjectOptimisticLockingFailureException("Stay", "id"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error()).isEqualTo("CONFLICT");
        assertThat(response.getBody().message()).isEqualTo("error.concurrent_modification");
    }

    @Test
    void shouldNotLeakTheOffendingConstraint_whenDatabaseRejectsAWrite() {
        // The constraint name identifies rows the caller may not be entitled to know exist --
        // uq_users_email is global, so it spans other agencies.
        var response = handler.handleDataIntegrityViolation(
                new DataIntegrityViolationException("uq_users_email"));

        assertThat(response.getBody().message()).doesNotContain("uq_users_email");
    }
}
