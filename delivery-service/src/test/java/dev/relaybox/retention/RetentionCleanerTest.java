package dev.relaybox.retention;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetentionCleanerTest {
    private static final RetentionProperties PROPERTIES = new RetentionProperties(
            false, false, 3_600_000, 60_000, 500, 7, 30, 30, 90, 30, 30);

    @Test
    void deletesEachOwnedTableInBatches() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), anyInt(), anyInt())).thenReturn(0, 0, 0, 0, 0, 0);

        RetentionReport report = new RetentionCleaner(jdbc, PROPERTIES).runOnce(false);

        assertThat(report.dryRun()).isFalse();
        verify(jdbc).update(contains("DELETE FROM webhook_delivery_attempt"), eq(30), eq(500));
        verify(jdbc).update(contains("DELETE FROM webhook_delivery WHERE"), eq(30), eq(500));
        verify(jdbc).update(contains("DELETE FROM webhook_event"), eq(90), eq(500));
        verify(jdbc).update(contains("DELETE FROM processed_delivery_event"), eq(30), eq(500));
        verify(jdbc).update(contains("DELETE FROM dead_letter_message"), eq(30), eq(500));
        verify(jdbc, times(5)).update(anyString(), anyInt(), anyInt());
    }

    @Test
    void repeatsBatchesUntilFewerThanBatchSizeAreDeleted() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), anyInt(), anyInt())).thenReturn(500, 0);

        new RetentionCleaner(jdbc, PROPERTIES).runOnce(false);

        verify(jdbc, times(2)).update(contains("DELETE FROM webhook_delivery_attempt"),
                eq(30), eq(500));
    }

    @Test
    void dryRunOnlyCounts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyInt())).thenReturn(1);

        RetentionReport report = new RetentionCleaner(jdbc, PROPERTIES).runOnce(true);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.inbox()).isEqualTo(1);
        assertThat(report.deadLetters()).isEqualTo(1);
        verify(jdbc, never()).update(anyString(), anyInt(), anyInt());
    }
}
