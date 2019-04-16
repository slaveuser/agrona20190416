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

import org.agrona.ErrorHandler;
import org.agrona.concurrent.status.AtomicCounter;

import java.util.Objects;

/**
 * An {@link ErrorHandler} which calls {@link AtomicCounter#increment()} before delegating the exception.
 */
public class CountedErrorHandler implements ErrorHandler
{
    private final ErrorHandler errorHandler;
    private final AtomicCounter errorCounter;

    /**
     * Construct a counted error handler with a delegate and counter.
     *
     * @param errorHandler to delegate to.
     * @param errorCounter to increment before delegation.
     */
    public CountedErrorHandler(final ErrorHandler errorHandler, final AtomicCounter errorCounter)
    {
        Objects.requireNonNull(errorHandler, "errorHandler");
        Objects.requireNonNull(errorCounter, "errorCounter");

        this.errorHandler = errorHandler;
        this.errorCounter = errorCounter;
    }

    public void onError(final Throwable throwable)
    {
        errorCounter.increment();
        errorHandler.onError(throwable);
    }
}
