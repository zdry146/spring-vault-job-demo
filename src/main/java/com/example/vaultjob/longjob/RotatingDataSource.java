package com.example.vaultjob.longjob;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;

/**
 * A {@link DelegatingDataSource} that swaps its target DataSource at runtime.
 *
 * <p>Spring beans that captured this object (e.g. {@code JdbcTemplate})
 * continue to work after a rotation because every {@link #getConnection()}
 * call is forwarded to the current target. This is the trick that lets a
 * long-running job keep its {@code JdbcTemplate} while the underlying
 * credential pair rotates underneath.</p>
 */
public class RotatingDataSource extends DelegatingDataSource {

    public RotatingDataSource(DataSource initial) {
        super(initial);
    }

    /**
     * Atomically swap the target DataSource and close the old one.
     * New connections are served from the new pool immediately; in-flight
     * connections on the old pool complete normally as Hikari drains it.
     */
    public void rotate(DataSource newTarget) {
        DataSource old = getTargetDataSource();
        setTargetDataSource(newTarget);
        // Best-effort close — failures are non-fatal since the old pool is
        // already replaced in the delegate.
        if (old instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Pool already closed or interrupted; nothing to do.
            }
        }
    }
}