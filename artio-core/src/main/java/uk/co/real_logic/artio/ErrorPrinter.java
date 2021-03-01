/*
 * Copyright 2015-2021 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio;

import io.aeron.archive.client.AeronArchive;
import org.agrona.concurrent.*;
import org.agrona.concurrent.errors.ErrorConsumer;
import org.agrona.concurrent.errors.ErrorLogReader;
import uk.co.real_logic.artio.engine.EngineConfiguration;

import static uk.co.real_logic.artio.CommonConfiguration.DEFAULT_NAME_PREFIX;

public class ErrorPrinter implements Agent
{
    public static void main(final String[] args)
    {
        final EngineConfiguration configuration = new EngineConfiguration();
        configuration.libraryAeronChannel("").conclude();
        final MonitoringFile monitoringFile = new MonitoringFile(false, configuration);
        final ErrorPrinter printer = new ErrorPrinter(
            monitoringFile.errorBuffer(), DEFAULT_NAME_PREFIX, 0, null, null,
            new SystemEpochClock());
        final IdleStrategy idleStrategy = new BackoffIdleStrategy(1, 1, 1000, 1_000_000);
        final AgentRunner runner = new AgentRunner(idleStrategy, Throwable::printStackTrace, null, printer);
        runner.run();
    }

    static final ErrorConsumer PRINTING_ERROR_CONSUMER =
        (observationCount, firstObservationTimestampInMs, lastObservationTimestampInMs, encodedException) ->
        {
            System.err.println(encodedException);
            System.err.printf(
                "count=%d,firstTime=%d,lastTime=%d%n%n",
                observationCount,
                firstObservationTimestampInMs,
                lastObservationTimestampInMs);
        };

    private final ErrorConsumer errorConsumer;
    private final EpochClock clock;

    private final AtomicBuffer errorBuffer;
    private final String agentNamePrefix;

    private long lastPollTimeInMs;
    private final AeronArchive aeronArchive;

    ErrorPrinter(
        final AtomicBuffer errorBuffer,
        final String agentNamePrefix,
        final long startTimeInMs,
        final AeronArchive aeronArchive,
        final ErrorConsumer customErrorConsumer,
        final EpochClock clock)
    {
        this.errorBuffer = errorBuffer;
        this.agentNamePrefix = agentNamePrefix;
        lastPollTimeInMs = startTimeInMs;
        this.aeronArchive = aeronArchive;
        this.errorConsumer = customErrorConsumer == null ? PRINTING_ERROR_CONSUMER : customErrorConsumer;
        this.clock = clock;
    }

    public int doWork()
    {
        int work = 0;
        if (aeronArchive != null)
        {
            final String errorResponse = aeronArchive.pollForErrorResponse();
            if (errorResponse != null)
            {
                System.err.println(errorResponse);
                work++;
            }
        }

        final long timeInMs = clock.time();
        final long lastPolledTimeInMs = lastPollTimeInMs;
        if (timeInMs > lastPolledTimeInMs)
        {
            this.lastPollTimeInMs = timeInMs;
            final int errors = ErrorLogReader.read(errorBuffer, errorConsumer, lastPolledTimeInMs + 1);
            work += errors;
        }

        return work;
    }

    public String roleName()
    {
        return agentNamePrefix + "Error Printer";
    }
}
