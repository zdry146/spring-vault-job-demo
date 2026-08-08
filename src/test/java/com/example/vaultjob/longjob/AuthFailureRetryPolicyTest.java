package com.example.vaultjob.longjob;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.retry.RetryContext;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthFailureRetryPolicyTest {

    private final AuthFailureRetryPolicy policy = new AuthFailureRetryPolicy(3);

    @Test
    void canRetry_returnsTrueWhenNoThrowablesRegistered_toAllowFirstAttempt() {
        // Spring Retry calls canRetry BEFORE the first attempt to decide whether
        // to enter the do-while loop. The policy must return true here so the
        // first attempt runs. Returning false here would cause handleRetryExhausted
        // to be called immediately, wrapping the absent throwable in RetryException.
        RetryContext ctx = policy.open(null);
        assertThat(policy.canRetry(ctx)).isTrue();
    }

    @Test
    void canRetry_returnsTrueForPostgresAuthFailure_28P01() {
        RetryContext ctx = policy.open(null);
        policy.registerThrowable(ctx, new SQLException("password auth failed", "28P01"));
        assertThat(policy.canRetry(ctx)).isTrue();
    }

    @Test
    void canRetry_returnsTrueForOracleAuthFailure_Ora1017() {
        RetryContext ctx = policy.open(null);
        policy.registerThrowable(ctx, new SQLException("ORA-01017", null, 1017));
        assertThat(policy.canRetry(ctx)).isTrue();
    }

    @Test
    void canRetry_returnsFalseForNonAuthSqlException_42P01() {
        RetryContext ctx = policy.open(null);
        policy.registerThrowable(ctx, new SQLException("relation \"foo\" does not exist", "42P01"));
        assertThat(policy.canRetry(ctx)).isFalse();
    }

    @Test
    void canRetry_returnsFalseAfterMaxAttemptsReached() {
        RetryContext ctx = policy.open(null);
        // Simulate 3 attempts (count = 0, 1, 2, then count >= maxAttempts).
        for (int i = 0; i < policy.getMaxAttempts(); i++) {
            policy.registerThrowable(ctx, new SQLException("password auth failed", "28P01"));
            // Each registerThrowable increments retryCount via RetryContextSupport
        }
        assertThat(policy.canRetry(ctx)).isFalse();
    }

    @Test
    void canRetry_walksCauseChain_viaWrapperException() {
        RetryContext ctx = policy.open(null);
        // DataAccessResourceFailureException wraps a SQLException
        SQLException cause = new SQLException("password auth failed", "28P01");
        policy.registerThrowable(ctx, new DataAccessResourceFailureException("wrapped", cause));
        assertThat(policy.canRetry(ctx)).isTrue();
    }

    @Test
    void constructor_rejectsZeroOrNegativeMaxAttempts() {
        assertThatThrownBy(() -> new AuthFailureRetryPolicy(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthFailureRetryPolicy(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void close_isNoOp() {
        RetryContext ctx = policy.open(null);
        // No state to leak; just verify it doesn't throw
        policy.close(ctx);
    }
}