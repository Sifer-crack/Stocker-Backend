package com.stocker.notifications.infrastructure.scheduling;

import com.stocker.notifications.application.ExpirationScanService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExpirationScanSchedulerTest {

    private final ExpirationScanService scanService = mock(ExpirationScanService.class);
    private final ExpirationScanScheduler scheduler = new ExpirationScanScheduler(scanService);

    @Test
    void runsTheScan() {
        when(scanService.scan()).thenReturn(2);

        scheduler.runScan();

        verify(scanService).scan();
    }

    /** One failed run (inventory down) must not kill the schedule: the next tick tries again. */
    @Test
    void aFailingScanDoesNotEscapeTheScheduler() {
        when(scanService.scan()).thenThrow(new IllegalStateException("inventory unreachable"));

        assertThatCode(scheduler::runScan).doesNotThrowAnyException();
    }
}
