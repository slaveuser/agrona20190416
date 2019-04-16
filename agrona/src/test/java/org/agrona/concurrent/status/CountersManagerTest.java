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
package org.agrona.concurrent.status;

import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.agrona.DirectBuffer;
import org.agrona.collections.IntObjConsumer;

import java.nio.ByteBuffer;

import static java.nio.ByteBuffer.allocateDirect;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.agrona.concurrent.status.CountersReader.MAX_LABEL_LENGTH;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.agrona.concurrent.status.CountersReader.COUNTER_LENGTH;
import static org.agrona.concurrent.status.CountersReader.METADATA_LENGTH;

public class CountersManagerTest
{
    private static final int NUMBER_OF_COUNTERS = 4;
    private static final long FREE_TO_REUSE_TIMEOUT = 1000;

    private long currentTimestamp = 0;

    private final UnsafeBuffer labelsBuffer = new UnsafeBuffer(allocateDirect(NUMBER_OF_COUNTERS * METADATA_LENGTH));
    private final UnsafeBuffer counterBuffer = new UnsafeBuffer(allocateDirect(NUMBER_OF_COUNTERS * COUNTER_LENGTH));
    private final CountersManager manager = new CountersManager(labelsBuffer, counterBuffer, US_ASCII);
    private final CountersReader reader = new CountersManager(labelsBuffer, counterBuffer, US_ASCII);
    private final CountersManager managerWithCooldown =
        new CountersManager(labelsBuffer, counterBuffer, US_ASCII, () -> currentTimestamp, FREE_TO_REUSE_TIMEOUT);

    @SuppressWarnings("unchecked")
    private final IntObjConsumer<String> consumer = mock(IntObjConsumer.class);
    private final CountersReader.MetaData metaData = mock(CountersReader.MetaData.class);

    @Test
    public void shouldTruncateLongLabel()
    {
        final int labelLength = MAX_LABEL_LENGTH + 10;
        final StringBuilder sb = new StringBuilder(labelLength);

        for (int i = 0; i < labelLength; i++)
        {
            sb.append('x');
        }

        final String label = sb.toString();
        final int counterId = manager.allocate(label);

        reader.forEach(consumer);
        verify(consumer).accept(counterId, label.substring(0, MAX_LABEL_LENGTH));
    }

    @Test
    public void shouldCopeWithExceptionKeyFunc()
    {
        final RuntimeException ex = new RuntimeException();

        try
        {
            manager.allocate(
                "label",
                CountersManager.DEFAULT_TYPE_ID,
                (buffer) ->
                {
                    throw ex;
                });
        }
        catch (final RuntimeException caught)
        {
            assertThat(caught, is(ex));

            final AtomicCounter counter = manager.newCounter("new label");
            assertThat(counter.id(), is(0));

            return;
        }

        fail("Should have thrown exception");
    }

    @Test
    public void shouldStoreLabels()
    {
        final int counterId = manager.allocate("abc");
        reader.forEach(consumer);
        verify(consumer).accept(counterId, "abc");
    }

    @Test
    public void shouldStoreMultipleLabels()
    {
        final int abc = manager.allocate("abc");
        final int def = manager.allocate("def");
        final int ghi = manager.allocate("ghi");

        reader.forEach(consumer);

        final InOrder inOrder = Mockito.inOrder(consumer);
        inOrder.verify(consumer).accept(abc, "abc");
        inOrder.verify(consumer).accept(def, "def");
        inOrder.verify(consumer).accept(ghi, "ghi");
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void shouldFreeAndReuseCounters()
    {
        final int abc = manager.allocate("abc");
        final int def = manager.allocate("def");
        final int ghi = manager.allocate("ghi");

        manager.free(def);

        reader.forEach(consumer);

        final InOrder inOrder = Mockito.inOrder(consumer);
        inOrder.verify(consumer).accept(abc, "abc");
        inOrder.verify(consumer).accept(ghi, "ghi");
        inOrder.verifyNoMoreInteractions();

        assertThat(manager.allocate("the next label"), is(def));
    }

    @Test
    public void shouldFreeAndNotReuseCountersThatHaveCooldown()
    {
        final int abc = managerWithCooldown.allocate("abc");
        final int def = managerWithCooldown.allocate("def");
        final int ghi = managerWithCooldown.allocate("ghi");

        managerWithCooldown.free(def);

        currentTimestamp += FREE_TO_REUSE_TIMEOUT - 1;
        assertThat(managerWithCooldown.allocate("the next label"), is(greaterThan(ghi)));
    }

    @Test
    public void shouldFreeAndReuseCountersAfterCooldown()
    {
        final int abc = managerWithCooldown.allocate("abc");
        final int def = managerWithCooldown.allocate("def");
        final int ghi = managerWithCooldown.allocate("ghi");

        managerWithCooldown.free(def);

        currentTimestamp += FREE_TO_REUSE_TIMEOUT;
        assertThat(managerWithCooldown.allocate("the next label"), is(def));
    }

    @Test(expected = IllegalStateException.class)
    public void shouldNotOverAllocateCounters()
    {
        manager.allocate("abc");
        manager.allocate("def");
        manager.allocate("ghi");
        manager.allocate("jkl");
        manager.allocate("mno");
    }

    @Test
    public void shouldMapAllocatedCounters()
    {
        manager.allocate("def");

        final int id = manager.allocate("abc");
        final ReadablePosition reader = new UnsafeBufferPosition(counterBuffer, id);
        final Position writer = new UnsafeBufferPosition(counterBuffer, id);
        final long expectedValue = 0xF_FFFF_FFFFL;

        writer.setOrdered(expectedValue);

        assertThat(reader.getVolatile(), is(expectedValue));
    }

    @Test
    public void shouldStoreMetaData()
    {
        final int typeIdOne = 333;
        final long keyOne = 777L;

        final int typeIdTwo = 222;
        final long keyTwo = 444;

        final int counterIdOne = manager.allocate("Test Label One", typeIdOne, (buffer) -> buffer.putLong(0, keyOne));
        final int counterIdTwo = manager.allocate("Test Label Two", typeIdTwo, (buffer) -> buffer.putLong(0, keyTwo));

        manager.forEach(metaData);

        final ArgumentCaptor<DirectBuffer> argCaptorOne = ArgumentCaptor.forClass(DirectBuffer.class);
        final ArgumentCaptor<DirectBuffer> argCaptorTwo = ArgumentCaptor.forClass(DirectBuffer.class);

        final InOrder inOrder = Mockito.inOrder(metaData);
        inOrder.verify(metaData).accept(eq(counterIdOne), eq(typeIdOne), argCaptorOne.capture(), eq("Test Label One"));
        inOrder.verify(metaData).accept(eq(counterIdTwo), eq(typeIdTwo), argCaptorTwo.capture(), eq("Test Label Two"));
        inOrder.verifyNoMoreInteractions();

        final DirectBuffer keyOneBuffer = argCaptorOne.getValue();
        assertThat(keyOneBuffer.getLong(0), is(keyOne));

        final DirectBuffer keyTwoBuffer = argCaptorTwo.getValue();
        assertThat(keyTwoBuffer.getLong(0), is(keyTwo));
    }

    @Test
    public void shouldStoreRawData()
    {
        final int typeIdOne = 333;
        final long keyOne = 777L;
        final MutableDirectBuffer keyOneBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(8));
        keyOneBuffer.putLong(0, keyOne);
        final DirectBuffer labelOneBuffer = new UnsafeBuffer("Test Label One".getBytes(US_ASCII));

        final int typeIdTwo = 222;
        final long keyTwo = 444;
        final MutableDirectBuffer keyTwoBuffer = new UnsafeBuffer(ByteBuffer.allocateDirect(8));
        keyTwoBuffer.putLong(0, keyTwo);
        final DirectBuffer labelTwoBuffer = new UnsafeBuffer("Test Label Two".getBytes(US_ASCII));

        final int counterIdOne = manager.allocate(
            typeIdOne, keyOneBuffer, 0, keyOneBuffer.capacity(), labelOneBuffer, 0, labelOneBuffer.capacity());

        final int counterIdTwo = manager.allocate(
            typeIdTwo, keyTwoBuffer, 0, keyTwoBuffer.capacity(), labelTwoBuffer, 0, labelTwoBuffer.capacity());

        manager.forEach(metaData);

        final ArgumentCaptor<DirectBuffer> argCaptorOne = ArgumentCaptor.forClass(DirectBuffer.class);
        final ArgumentCaptor<DirectBuffer> argCaptorTwo = ArgumentCaptor.forClass(DirectBuffer.class);

        final InOrder inOrder = Mockito.inOrder(metaData);
        inOrder.verify(metaData).accept(eq(counterIdOne), eq(typeIdOne), argCaptorOne.capture(), eq("Test Label One"));
        inOrder.verify(metaData).accept(eq(counterIdTwo), eq(typeIdTwo), argCaptorTwo.capture(), eq("Test Label Two"));
        inOrder.verifyNoMoreInteractions();

        final DirectBuffer keyOneBufferCapture = argCaptorOne.getValue();
        assertThat(keyOneBufferCapture.getLong(0), is(keyOne));

        final DirectBuffer keyTwoBufferCapture = argCaptorTwo.getValue();
        assertThat(keyTwoBufferCapture.getLong(0), is(keyTwo));
    }

    @Test
    public void shouldStoreAndLoadValue()
    {
        final int counterId = manager.allocate("Test Counter");

        final long value = 7L;
        manager.setCounterValue(counterId, value);

        assertThat(manager.getCounterValue(counterId), is(value));
    }
}
