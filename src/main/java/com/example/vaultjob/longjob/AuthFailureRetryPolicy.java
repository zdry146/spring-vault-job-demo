package com.example.vaultjob.longjob;

import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.context.RetryContextSupport;

/**
 * {@link RetryPolicy} that retries <em>only</em> on authentication failures,
 * as classified by {@link AuthFailureClassifier}.
 *
 * <p>This is a tighter alternative to the default {@code SimpleRetryPolicy}
 * used by {@link LongJobBatchRunner} before this class existed. With the broad
 * policy, any {@link java.sql.SQLException} is retried - fine in practice,
 * but it muddies the signal: a 42P01 (undefined table) gets 5 retries before
 * failing. With this policy, only credential-rotation-era errors (PG 28P01,
 * MySQL 1045, Oracle ORA-01017, etc.) are retried; everything else propagates
 * immediately as the deterministic failure it is.</p>
 *
 * <p><b>Behaviour:</b></p>
 * <ul>
 *   <li>{@code maxAttempts} total attempts (initial + retries).</li>
 *   <li>{@code canRetry} returns {@code true} for the first attempt (so the
 *       work actually runs) and on subsequent attempts iff the last thrown
 *       exception passes {@link AuthFailureClassifier#isAuthFailure(Throwable)}
 *       and attempts remain.</li>
 *   <li>{@code registerThrowable} stores the throwable so the next
 *       {@code canRetry} call can inspect it.</li>
 * </ul>
 */
public class AuthFailureRetryPolicy implements RetryPolicy {

    private final int maxAttempts;

    public AuthFailureRetryPolicy(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        this.maxAttempts = maxAttempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    @Override
    public boolean canRetry(RetryContext context) {
        if (context.getRetryCount() >= maxAttempts) {
            return false;
        }
        Throwable last = context.getLastThrowable();
        if (last == null) {
            // Spring Retry calls canRetry BEFORE the first attempt to decide
            // whether to enter the do-while loop. We must return true here so
            // the first attempt actually runs. Subsequent calls (after a
            // throwable is registered) gate on AuthFailureClassifier.
            // Same pattern SimpleRetryPolicy uses: `t == null || retryForException(t)`.
            return true;
        }
        return AuthFailureClassifier.isAuthFailure(last);
    }

    @Override
    public RetryContext open(RetryContext parent) {
        return new RetryContextSupport(parent);
    }

    @Override
    public void close(RetryContext context) {
        // Nothing to release; RetryContextSupport is GC'd with the context.
    }

    @Override
    public void registerThrowable(RetryContext context, Throwable throwable) {
        RetryContextSupport ctx = (RetryContextSupport) context;
        ctx.registerThrowable(throwable);
    }
}