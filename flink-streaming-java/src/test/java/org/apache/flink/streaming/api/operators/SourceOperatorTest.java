/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package org.apache.flink.streaming.api.operators;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.connector.source.SourceEvent;
import org.apache.flink.api.connector.source.mocks.MockSourceReader;
import org.apache.flink.api.connector.source.mocks.MockSourceSplit;
import org.apache.flink.api.connector.source.mocks.MockSourceSplitSerializer;
import org.apache.flink.runtime.io.network.api.StopMode;
import org.apache.flink.runtime.operators.coordination.MockOperatorEventGateway;
import org.apache.flink.runtime.operators.coordination.OperatorEvent;
import org.apache.flink.runtime.source.event.AddSplitEvent;
import org.apache.flink.runtime.source.event.IsProcessingBacklogEvent;
import org.apache.flink.runtime.source.event.ReaderRegistrationEvent;
import org.apache.flink.runtime.source.event.SourceEventWrapper;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContextSynchronousImpl;
import org.apache.flink.streaming.api.operators.source.CollectingDataOutput;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.io.DataInputStatus;
import org.apache.flink.streaming.runtime.io.PushingAsyncDataInput;
import org.apache.flink.streaming.runtime.streamrecord.LatencyMarker;
import org.apache.flink.streaming.runtime.streamrecord.RecordAttributes;
import org.apache.flink.streaming.runtime.streamrecord.StreamElement;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.watermarkstatus.WatermarkStatus;
import org.apache.flink.streaming.util.CollectorOutput;
import org.apache.flink.util.CollectionUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Unit test for {@link SourceOperator}. */
@SuppressWarnings("serial")
public class SourceOperatorTest {

    @Nullable private SourceOperatorTestContext context;
    @Nullable private SourceOperator<Integer, MockSourceSplit> operator;
    @Nullable private MockSourceReader mockSourceReader;
    @Nullable private MockOperatorEventGateway mockGateway;

    @Before
    public void setup() throws Exception {
        context = new SourceOperatorTestContext();
        operator = context.getOperator();
        mockSourceReader = context.getSourceReader();
        mockGateway = context.getGateway();
    }

    @After
    public void tearDown() throws Exception {
        context.close();
        context = null;
        operator = null;
        mockSourceReader = null;
        mockGateway = null;
    }

    @Test
    public void testInitializeState() throws Exception {
        StateInitializationContext stateContext = context.createStateContext();
        operator.initializeState(stateContext);

        assertNotNull(
                stateContext
                        .getOperatorStateStore()
                        .getListState(SourceOperator.SPLITS_STATE_DESC));
    }

    @Test
    public void testOpen() throws Exception {
        // Initialize the operator.
        operator.initializeState(context.createStateContext());
        // Open the operator.
        operator.open();
        // The source reader should have been assigned a split.
        assertEquals(
                Collections.singletonList(SourceOperatorTestContext.MOCK_SPLIT),
                mockSourceReader.getAssignedSplits());
        // The source reader should have started.
        assertTrue(mockSourceReader.isStarted());

        // A ReaderRegistrationRequest should have been sent.
        assertEquals(1, mockGateway.getEventsSent().size());
        OperatorEvent operatorEvent = mockGateway.getEventsSent().get(0);
        assertTrue(operatorEvent instanceof ReaderRegistrationEvent);
        assertEquals(
                SourceOperatorTestContext.SUBTASK_INDEX,
                ((ReaderRegistrationEvent) operatorEvent).subtaskId());
    }

    @Test
    public void testStop() throws Exception {
        // Initialize the operator.
        operator.initializeState(context.createStateContext());
        // Open the operator.
        operator.open();
        // The source reader should have been assigned a split.
        assertEquals(
                Collections.singletonList(SourceOperatorTestContext.MOCK_SPLIT),
                mockSourceReader.getAssignedSplits());

        CollectingDataOutput<Integer> dataOutput = new CollectingDataOutput<>();
        assertEquals(DataInputStatus.NOTHING_AVAILABLE, operator.emitNext(dataOutput));
        assertFalse(operator.isAvailable());

        CompletableFuture<Void> sourceStopped = operator.stop(StopMode.DRAIN);
        assertTrue(operator.isAvailable());
        assertFalse(sourceStopped.isDone());
        assertEquals(DataInputStatus.END_OF_DATA, operator.emitNext(dataOutput));
        operator.finish();
        assertTrue(sourceStopped.isDone());
    }

    @Test
    public void testHandleAddSplitsEvent() throws Exception {
        operator.initializeState(context.createStateContext());
        operator.open();
        MockSourceSplit newSplit = new MockSourceSplit((2));
        operator.handleOperatorEvent(
                new AddSplitEvent<>(
                        Collections.singletonList(newSplit), new MockSourceSplitSerializer()));
        // The source reader should have been assigned two splits.
        assertEquals(
                Arrays.asList(SourceOperatorTestContext.MOCK_SPLIT, newSplit),
                mockSourceReader.getAssignedSplits());
    }

    @Test
    public void testHandleAddSourceEvent() throws Exception {
        operator.initializeState(context.createStateContext());
        operator.open();
        SourceEvent event = new SourceEvent() {};
        operator.handleOperatorEvent(new SourceEventWrapper(event));
        // The source reader should have been assigned two splits.
        assertEquals(Collections.singletonList(event), mockSourceReader.getReceivedSourceEvents());
    }

    @Test
    public void testSnapshotState() throws Exception {
        StateInitializationContext stateContext = context.createStateContext();
        operator.initializeState(stateContext);
        operator.open();
        MockSourceSplit newSplit = new MockSourceSplit((2));
        operator.handleOperatorEvent(
                new AddSplitEvent<>(
                        Collections.singletonList(newSplit), new MockSourceSplitSerializer()));
        operator.snapshotState(new StateSnapshotContextSynchronousImpl(100L, 100L));

        // Verify the splits in state.
        List<MockSourceSplit> splitsInState =
                CollectionUtil.iterableToList(operator.getReaderState().get());
        assertEquals(Arrays.asList(SourceOperatorTestContext.MOCK_SPLIT, newSplit), splitsInState);
    }

    @Test
    public void testNotifyCheckpointComplete() throws Exception {
        StateInitializationContext stateContext = context.createStateContext();
        operator.initializeState(stateContext);
        operator.open();
        operator.snapshotState(new StateSnapshotContextSynchronousImpl(100L, 100L));
        operator.notifyCheckpointComplete(100L);
        assertEquals(100L, (long) mockSourceReader.getCompletedCheckpoints().get(0));
    }

    @Test
    public void testNotifyCheckpointAborted() throws Exception {
        StateInitializationContext stateContext = context.createStateContext();
        operator.initializeState(stateContext);
        operator.open();
        operator.snapshotState(new StateSnapshotContextSynchronousImpl(100L, 100L));
        operator.notifyCheckpointAborted(100L);
        assertEquals(100L, (long) mockSourceReader.getAbortedCheckpoints().get(0));
    }

    @Test
    public void testHandleBacklogEvent() throws Exception {
        List<StreamElement> outputStreamElements = new ArrayList<>();
        context =
                new SourceOperatorTestContext(
                        false,
                        false,
                        WatermarkStrategy.<Integer>forMonotonousTimestamps()
                                .withTimestampAssigner((element, recordTimestamp) -> element),
                        new CollectorOutput<>(outputStreamElements));
        operator = context.getOperator();
        operator.initializeState(context.createStateContext());
        operator.open();

        MockSourceSplit newSplit = new MockSourceSplit(2);
        newSplit.addRecord(1);
        newSplit.addRecord(1001);
        operator.handleOperatorEvent(
                new AddSplitEvent<>(
                        Collections.singletonList(newSplit), new MockSourceSplitSerializer()));
        final DataOutputToOutput<Integer> output = new DataOutputToOutput<>(operator.output);
        operator.emitNext(output);
        operator.handleOperatorEvent(new IsProcessingBacklogEvent(true));

        operator.emitNext(output);
        operator.handleOperatorEvent(new IsProcessingBacklogEvent(false));

        assertThat(outputStreamElements)
                .containsExactly(
                        new StreamRecord<>(1, 1),
                        new Watermark(0),
                        new RecordAttributes(true),
                        new StreamRecord<>(1001, 1001),
                        new Watermark(1000),
                        new RecordAttributes(false));
    }

    private static class DataOutputToOutput<T> implements PushingAsyncDataInput.DataOutput<T> {

        private final Output<StreamRecord<T>> output;

        DataOutputToOutput(Output<StreamRecord<T>> output) {
            this.output = output;
        }

        @Override
        public void emitRecord(StreamRecord<T> streamRecord) throws Exception {
            output.collect(streamRecord);
        }

        @Override
        public void emitWatermark(Watermark watermark) throws Exception {
            output.emitWatermark(watermark);
        }

        @Override
        public void emitWatermarkStatus(WatermarkStatus watermarkStatus) throws Exception {
            output.emitWatermarkStatus(watermarkStatus);
        }

        @Override
        public void emitLatencyMarker(LatencyMarker latencyMarker) throws Exception {
            output.emitLatencyMarker(latencyMarker);
        }

        @Override
        public void emitRecordAttributes(RecordAttributes recordAttributes) throws Exception {
            output.emitRecordAttributes(recordAttributes);
        }
    }
}
