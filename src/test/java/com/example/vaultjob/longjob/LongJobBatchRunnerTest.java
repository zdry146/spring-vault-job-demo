package com.example.vaultjob.longjob;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongJobBatchRunnerTest {

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private ResultSet resultSet;
    private LongJobCredentialManager manager;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        resultSet = mock(ResultSet.class);
        manager = mock(LongJobCredentialManager.class);

        when(manager.dataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(7);
    }

    @Test
    void run_queriesViaDataSource_andClosesConnection() throws SQLException {
        LongJobBatchRunner runner = new LongJobBatchRunner(manager);

        runner.run();

        verify(dataSource).getConnection();
        verify(statement).executeQuery(anyString());
        verify(resultSet).next();
        verify(resultSet).getInt(1);
        verify(statement).close();
        verify(resultSet).close();
        verify(connection).close();
    }

    @Test
    void run_retriesOnSqlException_thenSucceeds() throws SQLException {
        // First getConnection() throws 28P01 (auth); second succeeds.
        // The retry template's backoff is real but short here, so we mock
        // the SECOND call to return normally.
        when(dataSource.getConnection())
                .thenThrow(new SQLException("password auth failed", "28P01"))
                .thenReturn(connection);

        LongJobBatchRunner runner = new LongJobBatchRunner(manager);
        runner.run();

        // Verify retry: getConnection called twice, but the statement still
        // ran exactly once on the successful retry.
        verify(dataSource, times(2)).getConnection();
        verify(statement).executeQuery(anyString());
    }

    @Test
    void run_propagatesNonSqlExceptionsImmediately() throws SQLException {
        when(dataSource.getConnection())
                .thenThrow(new IllegalStateException("config broken"));

        LongJobBatchRunner runner = new LongJobBatchRunner(manager);

        try {
            runner.run();
            org.junit.jupiter.api.Assertions.fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // RetryTemplate's SimpleRetryPolicy does not retry on non-SQLException
            // because traverseCauses=true walks only SQLExceptions' cause chains.
            verify(dataSource, times(1)).getConnection();
        }
    }
}