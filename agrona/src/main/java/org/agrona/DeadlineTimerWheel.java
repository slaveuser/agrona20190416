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
package org.agrona;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Deadline scheduled Timer Wheel (NOT thread safe)
 * <p>
 * Based on netty's HashedTimerWheel, which is based on
 * <a href="http://cseweb.ucsd.edu/users/varghese/">George Varghese</a> and
 * Tony Lauck's paper,
 * <a href="http://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z">'Hashed
 * and Hierarchical Timing Wheels: data structures to efficiently implement a
 * timer facility'</a>.  More comprehensive slides are located
 * <a href="http://www.cse.wustl.edu/~cdgill/courses/cs6874/TimingWheels.ppt">here</a>.
 * <p>
 * Wheel is backed by arrays. Timer cancellation is O(1). Timer scheduling might be slightly
 * longer if a lot of timers are in the same tick. The underlying tick contains an array. That
 * array grows when needed, but does not shrink.
 * <p>
 * <b>Caveats</b>
 * <p>
 * Timers that expire in the same tick will not be ordered with one another. As ticks are
 * fairly coarse resolution normally, this means that some timers may expire out of order.
 * <p>
 * <b>Note:</b> Not threadsafe.
 */
public class DeadlineTimerWheel
{
    public static final long NULL_TIMER = Long.MAX_VALUE;

    private static final int INITIAL_TICK_ALLOCATION = 16;

    private final long[][] wheel;
    private final int tickResolution;
    private final int wheelMask;
    private final int resolutionBitsToShift;
    private final TimeUnit timeUnit;

    private long timerCount;
    private long startTime;
    private int currentTick;
    private int pollIndex;

    /**
     * Handler for expired timers.
     */
    @FunctionalInterface
    public interface TimerHandler
    {
        /**
         * Called when the deadline is past.
         *
         * @param timeUnit for the time.
         * @param now      for the expired timer.
         * @param timerId  for the expired timer.
         * @return true to consume timer and continue processing or false to keep timer active and abort.
         */
        boolean onTimerExpiry(TimeUnit timeUnit, long now, long timerId);
    }

    /**
     * Consumer of timer entries as deadline to timerId.
     */
    @FunctionalInterface
    public interface TimerConsumer
    {
        void accept(long deadline, long timerId);
    }

    /**
     * Construct timer wheel with given parameters.
     *
     * @param timeUnit       for the values used to express the time.
     * @param startTime      for the wheel (in given {@link TimeUnit})
     * @param tickResolution for the wheel, i.e. how many {@link TimeUnit}s per tick.
     * @param ticksPerWheel  for the wheel (must be power of 2)
     */
    public DeadlineTimerWheel(
        final TimeUnit timeUnit, final long startTime, final int tickResolution, final int ticksPerWheel)
    {
        this(timeUnit, startTime, tickResolution, ticksPerWheel, INITIAL_TICK_ALLOCATION);
    }

    /**
     * Construct timer wheel with given parameters.
     *
     * @param timeUnit              for the values used to express the time.
     * @param startTime             for the wheel (in given {@link TimeUnit})
     * @param tickResolution        for the wheel, i.e. how many {@link TimeUnit}s per tick.
     * @param ticksPerWheel         for the wheel (must be power of 2)
     * @param initialTickAllocation space allocated in the wheel.
     */
    public DeadlineTimerWheel(
        final TimeUnit timeUnit,
        final long startTime,
        final int tickResolution,
        final int ticksPerWheel,
        final int initialTickAllocation)
    {
        checkTicksPerWheel(ticksPerWheel);
        checkResolution(tickResolution);

        this.timeUnit = timeUnit;
        this.wheelMask = ticksPerWheel - 1;
        this.tickResolution = tickResolution;
        this.resolutionBitsToShift = Integer.numberOfTrailingZeros(tickResolution);
        this.startTime = startTime;
        this.timerCount = 0;
        this.pollIndex = 0;

        wheel = new long[ticksPerWheel][];

        for (int i = 0; i < wheel.length; i++)
        {
            wheel[i] = new long[initialTickAllocation];

            for (int j = 0; j < wheel[i].length; j++)
            {
                wheel[i][j] = NULL_TIMER;
            }
        }
    }

    /**
     * Time unit for the time values.
     *
     * @return time unit for the ticks.
     */
    public TimeUnit timeUnit()
    {
        return timeUnit;
    }

    /**
     * Resolution of a tick of the wheel in {@link #timeUnit()}s.
     *
     * @return resolution of a tick of the wheel in {@link #timeUnit()}s.
     */
    public long tickResolution()
    {
        return tickResolution;
    }

    /**
     * Time of current tick of the wheel in {@link #timeUnit()}s.
     *
     * @return time of the current tick of the wheel in {@link #timeUnit()}s.
     */
    public long currentTickTime()
    {
        return ((currentTick + 1L) << resolutionBitsToShift) + startTime;
    }

    /**
     * Number of active timers.
     *
     * @return number of active timers.
     */
    public long timerCount()
    {
        return timerCount;
    }

    /**
     * Reset the start time of the wheel.
     *
     * @param startTime to set the wheel to.
     * @exception IllegalStateException if wheel has any active timers.
     */
    public void resetStartTime(final long startTime)
    {
        if (timerCount > 0)
        {
            throw new IllegalStateException("can not set startTime with active timers");
        }

        this.startTime = startTime;
        this.currentTick = 0;
        this.pollIndex = 0;
    }

    /**
     * Schedule a timer for a given absolute time as a deadline in {@link #timeUnit()}s. A timerId will be assigned
     * and returned for future reference.
     *
     * @param deadline after which the timer should expire.
     * @return timerId for the scheduled timer
     */
    public long scheduleTimer(final long deadline)
    {
        final long ticks = Math.max((deadline - startTime) >> resolutionBitsToShift, currentTick);
        final int wheelIndex = (int)(ticks & wheelMask);
        final long[] array = wheel[wheelIndex];

        for (int i = 0; i < array.length; i++)
        {
            if (NULL_TIMER == array[i])
            {
                array[i] = deadline;
                timerCount++;

                return timerIdForSlot(wheelIndex, i);
            }
        }

        final long[] newArray = Arrays.copyOf(array, array.length + 1);
        newArray[array.length] = deadline;

        wheel[wheelIndex] = newArray;
        timerCount++;

        return timerIdForSlot(wheelIndex, array.length);
    }

    /**
     * Cancel a previously scheduled timer.
     *
     * @param timerId of the timer to cancel.
     * @return true if successful otherwise false if the timerId did not exist.
     */
    public boolean cancelTimer(final long timerId)
    {
        final int wheelIndex = tickForTimerId(timerId);
        final int arrayIndex = indexInTickArray(timerId);

        if (wheelIndex < wheel.length)
        {
            final long[] array = wheel[wheelIndex];

            if (arrayIndex < array.length && NULL_TIMER != array[arrayIndex])
            {
                array[arrayIndex] = NULL_TIMER;
                timerCount--;

                return true;
            }
        }

        return false;
    }

    /**
     * Poll for timers expired by the deadline passing.
     *
     * @param now               current time to compare deadlines against.
     * @param handler           to call for each expired timer.
     * @param maxTimersToExpire to process in one poll operation.
     * @return number of expired timers.
     */
    public int poll(final long now, final TimerHandler handler, final int maxTimersToExpire)
    {
        int timersExpired = 0;

        if (timerCount > 0)
        {
            final long[] array = wheel[currentTick & wheelMask];

            for (int i = 0, length = array.length; i < length && maxTimersToExpire > timersExpired; i++)
            {
                final long deadline = array[pollIndex];

                if (deadline <= now)
                {
                    array[pollIndex] = NULL_TIMER;
                    timerCount--;
                    timersExpired++;

                    if (!handler.onTimerExpiry(timeUnit, now, timerIdForSlot(currentTick & wheelMask, pollIndex)))
                    {
                        array[pollIndex] = deadline;
                        timerCount++;

                        return timersExpired;
                    }
                }

                pollIndex = (pollIndex + 1) >= length ? 0 : (pollIndex + 1);
            }

            if (maxTimersToExpire > timersExpired && currentTickTime() <= now)
            {
                currentTick++;
                pollIndex = 0;
            }
            else if (pollIndex >= array.length)
            {
                pollIndex = 0;
            }
        }
        else if (currentTickTime() <= now)
        {
            currentTick++;
            pollIndex = 0;
        }

        return timersExpired;
    }

    /**
     * Iterate over wheel so all active timers can be consumed without expiring them.
     *
     * @param consumer to call for each active timer.
     */
    public void forEach(final TimerConsumer consumer)
    {
        long numTimersLeft = timerCount;

        for (int j = currentTick, end = currentTick + wheel.length; j <= end; j++)
        {
            final long[] array = wheel[j & wheelMask];

            for (int i = 0, length = array.length; i < length; i++)
            {
                final long deadline = array[i];

                if (deadline != NULL_TIMER)
                {
                    consumer.accept(deadline, timerIdForSlot(j & wheelMask, i));

                    if (--numTimersLeft == 0)
                    {
                        return;
                    }
                }
            }
        }
    }

    /**
     * Return the deadline for the given timerId.
     *
     * @param timerId of the timer to return the deadline of.
     * @return deadline for the given timerId or {@link #NULL_TIMER} if timerId is not running.
     */
    public long deadline(final long timerId)
    {
        final int wheelIndex = tickForTimerId(timerId);
        final int arrayIndex = indexInTickArray(timerId);

        if (wheelIndex < wheel.length)
        {
            final long[] array = wheel[wheelIndex];

            if (arrayIndex < array.length)
            {
                return array[arrayIndex];
            }
        }

        return NULL_TIMER;
    }

    private static long timerIdForSlot(final int tickOnWheel, final int tickArrayIndex)
    {
        return ((long)tickOnWheel << 32) | tickArrayIndex;
    }

    private static int tickForTimerId(final long timerId)
    {
        return (int)(timerId >> 32);
    }

    private static int indexInTickArray(final long timerId)
    {
        return (int)timerId;
    }

    private static void checkTicksPerWheel(final int ticksPerWheel)
    {
        if (!BitUtil.isPowerOfTwo(ticksPerWheel))
        {
            throw new IllegalArgumentException("ticks per wheel must be a power of 2: " + ticksPerWheel);
        }
    }

    private static void checkResolution(final int tickResolution)
    {
        if (!BitUtil.isPowerOfTwo(tickResolution))
        {
            throw new IllegalArgumentException(" tick resolution must be a power of 2: " + tickResolution);
        }
    }
}