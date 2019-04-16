/*
 * Copyright 2014-2019 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.agrona.concurrent;

/**
 * When idle this strategy is to sleep for a specified period time in milliseconds.
 * <p>
 * This class uses {@link Thread#sleep(long)} to idle.
 */
public final class SleepingMillisIdleStrategy implements IdleStrategy
{
    private final long sleepPeriodMs;

    /**
     * Constructed a new strategy that will sleep for a given period when idle.
     *
     * @param sleepPeriodMs period in milliseconds for which the strategy will sleep when work count is 0.
     */
    public SleepingMillisIdleStrategy(final long sleepPeriodMs)
    {
        this.sleepPeriodMs = sleepPeriodMs;
    }

    /**
     *  {@inheritDoc}
     */
    public void idle(final int workCount)
    {
        if (workCount > 0)
        {
            return;
        }

        try
        {
            Thread.sleep(sleepPeriodMs);
        }
        catch (final InterruptedException ignore)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     *  {@inheritDoc}
     */
    public void idle()
    {
        try
        {
            Thread.sleep(sleepPeriodMs);
        }
        catch (final InterruptedException ignore)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     *  {@inheritDoc}
     */
    public void reset()
    {
    }

    public String toString()
    {
        return "SleepingMillisIdleStrategy{" +
            "sleepPeriodMs=" + sleepPeriodMs +
            '}';
    }
}
