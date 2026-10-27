package com.phoenix.linuxagent.mqtt;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class MonteCarloTest {

    @Test
    void estimatesPi() {
        MonteCarlo.Result result = MonteCarlo.run(2_000_000, () -> false);

        double pi = Double.parseDouble(result.output.substring(result.output.lastIndexOf(' ') + 1));
        assertTrue(Math.abs(pi - Math.PI) < 0.01, result.output);
        assertTrue(result.output.startsWith("Computed Monte-Carlo pi ~= "));
        assertTrue(result.executionTimeMs >= 0);
    }

    @Test
    void stopsWhenAsked() {
        AtomicInteger checks = new AtomicInteger();
        assertThrows(CancellationException.class,
                () -> MonteCarlo.run(100_000_000, () -> checks.incrementAndGet() > 3));
        assertTrue(checks.get() == 4);
    }

    @Test
    void refusesNonPositiveSampleCounts() {
        assertThrows(IllegalArgumentException.class, () -> MonteCarlo.run(0, () -> false));
        assertThrows(IllegalArgumentException.class, () -> MonteCarlo.run(-5, () -> false));
    }
}
