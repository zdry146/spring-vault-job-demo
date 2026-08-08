package com.example.vaultjob.longjob;

import java.sql.SQLException;
import java.util.Set;

/**
 * Identifies SQLExceptions that signal a credential rotation in progress,
 * so the long-job runner can retry the query against the freshly-rotated pool.
 *
 * <p>Coverage:</p>
 * <ul>
 *   <li><b>PostgreSQL</b> — SQLState starting with {@code "28"} (SQL standard
 *       class 28 = "invalid authorization specification"). Includes {@code 28000}
 *       and {@code 28P01}.</li>
 *   <li><b>MySQL / MariaDB</b> — error codes {@code 1045}, {@code 1044},
 *       {@code 1042} (Access denied). Connector/J does not surface these as
 *       SQLState.</li>
 *   <li><b>Oracle</b> — error codes {@code 1017}, {@code 28000}, {@code 1917};
 *       message patterns {@code ORA-01017}, {@code ORA-28000}.</li>
 *   <li><b>SQL Server</b> — error code {@code 18456} (login failed).</li>
 * </ul>
 *
 * <p>Walks the cause chain, so a wrapped exception (e.g. Hikari's
 * {@code HikariPool.PoolInitializationException}) is still classified
 * correctly.</p>
 */
public final class AuthFailureClassifier {

    private static final Set<String> ORACLE_MESSAGE_PATTERNS = Set.of(
            "ORA-01017", "ORA-28000", "ORA-01917", "ORA-01004");

    private AuthFailureClassifier() {
    }

    public static boolean isAuthFailure(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof SQLException sql) {
            String state = sql.getSQLState();
            if (state != null && state.startsWith("28")) {
                return true;
            }
            int code = sql.getErrorCode();
            // MySQL/MariaDB
            if (code == 1045 || code == 1044 || code == 1042) {
                return true;
            }
            // Oracle
            if (code == 1017 || code == 28000 || code == 1917) {
                return true;
            }
            // SQL Server
            if (code == 18456) {
                return true;
            }
            String msg = sql.getMessage();
            if (msg != null) {
                for (String p : ORACLE_MESSAGE_PATTERNS) {
                    if (msg.contains(p)) {
                        return true;
                    }
                }
            }
            // Walk the SQL cause chain (some drivers nest SQLExceptions)
            if (sql.getCause() instanceof SQLException cause) {
                return isAuthFailure(cause);
            }
        }
        // Walk the generic cause chain (Hikari wraps in PoolInitializationException, etc.)
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            return isAuthFailure(cause);
        }
        return false;
    }
}