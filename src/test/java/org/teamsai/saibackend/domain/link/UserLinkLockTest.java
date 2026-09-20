package org.teamsai.saibackend.domain.link;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.teamsai.saibackend.domain.link.service.UserLinkLock;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserLinkLockTest {
    @ParameterizedTest
    @CsvSource({"1,1", "10,0", "10,-1", "10,6"})
    void rejectsUnsafeConcurrency(int poolSize, int permits) {
        assertThatThrownBy(() -> new UserLinkLock(mock(DataSource.class), poolSize, permits))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configuredPermitLimitsActionsAndIsReleasedAfterFailure() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(1);
        UserLinkLock lock = new UserLinkLock(source, 10, 1);
        IllegalStateException failure = new IllegalStateException("action failed");

        assertThatThrownBy(() -> lock.execute(1L, () -> {
            assertThatThrownBy(() -> lock.execute(2L, () -> "unexpected"))
                    .extracting("errorCode").isEqualTo(AccountErrorCode.LINK_IN_PROGRESS);
            throw failure;
        })).isSameAs(failure);

        assertThat(lock.execute(2L, () -> "next action")).isEqualTo("next action");
        verify(source, times(2)).getConnection();
        verify(connection, times(2)).close();
    }
}
