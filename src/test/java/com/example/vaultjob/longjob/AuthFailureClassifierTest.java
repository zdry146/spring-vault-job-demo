package com.example.vaultjob.longjob;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class AuthFailureClassifierTest {

    @Test
    void postgres_28P01_isClassifiedAsAuthFailure() {
        SQLException sql = new SQLException(
                "password authentication failed for user \"v-approle-abc\"", "28P01");
        assertThat(AuthFailureClassifier.isAuthFailure(sql)).isTrue();
    }

    @Test
    void postgres_28000_isClassifiedAsAuthFailure() {
        SQLException sql = new SQLException(
                "invalid authorization specification", "28000");
        assertThat(AuthFailureClassifier.isAuthFailure(sql)).isTrue();
    }

    @Test
    void mysql_1045_isClassifiedAsAuthFailure() {
        SQLException sql = new SQLException(
                "Access denied for user 'foo'@'bar' (using password: YES)",
                "HY000", 1045);
        assertThat(AuthFailureClassifier.isAuthFailure(sql)).isTrue();
    }

    @Test
    void mysql_1044_isClassifiedAsAuthFailure() {
        SQLException sql = new SQLException(
                "Access denied for user 'foo'@'bar' to database 'baz'",
                "42000", 1044);
        assertThat(AuthFailureClassifier.isAuthFailure(sql)).isTrue();
    }

    @Test
    void oracle_1017_isClassifiedAsAuthFailure() {
        SQLException sql = new SQLException(
                "ORA-01017: invalid username/password; logon denied",
                "72000", 1017);
        assertThat(AuthFailureClassifier.isAuthFailure(sql)).isTrue();
    }

    @Test
    void oracle_oracleErrorCode28000_isClassifiedAsAuthFailure() {
        SQLException sql = new SQLException(
                "ORA-28000: the account is locked",
                "99999", 28000);
        assertThat(AuthFailureClassifier.isAuthFailure(sql)).isTrue();
    }

    @Test
    void oracle_messagePatternWithoutErrorCode_isClassifiedAsAuthFailure() {
        // Some Oracle drivers report ORA-XXXXX without a numeric errorCode.
        // (String) null disambiguates between (String, String) and (String, Throwable).
        SQLException sql = new SQLException(
                "ORA-01917: user does not exist",
                (String) null);
        assertThat(AuthFailureClassifier.isAuthFailure(sql)).isTrue();
    }

    @Test
    void sqlServer_18456_isClassifiedAsAuthFailure() {
        SQLException sql = new SQLException(
                "Login failed for user 'foo'.",
                "08001", 18456);
        assertThat(AuthFailureClassifier.isAuthFailure(sql)).isTrue();
    }

    @Test
    void generic_tableNotFound_isNotClassifiedAsAuthFailure() {
        SQLException sql = new SQLException(
                "relation \"foo\" does not exist", "42P01");
        assertThat(AuthFailureClassifier.isAuthFailure(sql)).isFalse();
    }

    @Test
    void mysql_tableNotFound_isNotClassifiedAsAuthFailure() {
        // MySQL: error code 1146 = "Table doesn't exist", not auth
        SQLException sql = new SQLException(
                "Table 'mydb.foo' doesn't exist",
                "42S02", 1146);
        assertThat(AuthFailureClassifier.isAuthFailure(sql)).isFalse();
    }

    @Test
    void null_isNotClassifiedAsAuthFailure() {
        assertThat(AuthFailureClassifier.isAuthFailure(null)).isFalse();
    }

    @Test
    void wrappedSqlException_viaRuntimeException_isStillDetected() {
        SQLException cause = new SQLException("password auth failed", "28P01");
        RuntimeException wrapper = new RuntimeException("Spring DataAccess wrapped", cause);
        assertThat(AuthFailureClassifier.isAuthFailure(wrapper)).isTrue();
    }

    @Test
    void wrappedSqlException_inAnotherSqlException_isStillDetected() {
        SQLException inner = new SQLException("ORA-01017", null, 1017);
        SQLException outer = new SQLException("wrapped sql error", "HY000", inner);
        assertThat(AuthFailureClassifier.isAuthFailure(outer)).isTrue();
    }

    @Test
    void unwrappedNonSqlException_isNotClassified() {
        RuntimeException re = new RuntimeException("nonsense");
        assertThat(AuthFailureClassifier.isAuthFailure(re)).isFalse();
    }

    @Test
    void causeChainLoop_doesNotInfiniteLoop() {
        SQLException a = new SQLException("a", "28P01");
        SQLException b = new SQLException("b", "HY000", a);
        a.initCause(b);  // intentional cycle
        // Must terminate and return based on a's SQLState
        assertThat(AuthFailureClassifier.isAuthFailure(a)).isTrue();
    }
}