package com.phoenix.linuxagent.mqtt;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/** The Monte-Carlo-Pi workload: estimates pi by sampling the unit square. */
public final class MonteCarlo {

    /** {@link #run} asks whether to stop every 65,536 samples (a few milliseconds). */
    private static final long STOP_CHECK_MASK = 0xFFFFL;

    /** What a run produced. */
    public static final class Result {

        public final String output;
        public final long executionTimeMs;

        public Result(String output, long executionTimeMs) {
            this.output = output;
            this.executionTimeMs = executionTimeMs;
        }
    }

    /** Runs a task; tests replace it. */
    public interface Workload {

        /**
         * @param shouldStop polled while running; true abandons the run
         * @throws CancellationException if {@code shouldStop} returned true
         */
        Result run(long samples, BooleanSupplier shouldStop);
    }

    private MonteCarlo() {
    }

    /**
     * @throws IllegalArgumentException if {@code samples} is not positive
     * @throws CancellationException if {@code shouldStop} returned true
     */
    public static Result run(long samples, BooleanSupplier shouldStop) {
        if (samples <= 0) {
            throw new IllegalArgumentException("samples must be positive, was " + samples);
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();
        long start = System.nanoTime();
        long inside = 0;

        for (long i = 0; i < samples; i++) {
            if ((i & STOP_CHECK_MASK) == 0 && shouldStop.getAsBoolean()) {
                throw new CancellationException("stopped after " + i + " of " + samples + " samples");
            }
            double x = random.nextDouble();
            double y = random.nextDouble();
            if (x * x + y * y <= 1.0) {
                inside++;
            }
        }

        double pi = 4.0 * inside / samples;
        return new Result("Computed Monte-Carlo pi ~= " + pi, (System.nanoTime() - start) / 1_000_000);
    }
}
