/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hyracks.dataflow.std.join;

import java.util.LinkedList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.hyracks.api.comm.IFrameTupleAccessor;
import org.apache.hyracks.api.comm.IFrameWriter;
import org.apache.hyracks.api.comm.VSizeFrame;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.dataflow.value.RecordDescriptor;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.dataflow.common.comm.io.FrameTupleAppender;
import org.apache.hyracks.dataflow.common.comm.util.FrameUtils;
import org.apache.hyracks.dataflow.std.buffermanager.DeallocatableFramePool;
import org.apache.hyracks.dataflow.std.buffermanager.IDeallocatableFramePool;
import org.apache.hyracks.dataflow.std.buffermanager.IDeletableTupleBufferManager;
import org.apache.hyracks.dataflow.std.buffermanager.ITupleAccessor;
import org.apache.hyracks.dataflow.std.buffermanager.ITuplePointerAccessor;
import org.apache.hyracks.dataflow.std.buffermanager.TupleAccessor;
import org.apache.hyracks.dataflow.std.buffermanager.VariableDeletableTupleMemoryManager;
import org.apache.hyracks.dataflow.std.structures.TuplePointer;

/**
 * Merge Joiner takes two sorted streams of input and joins.
 * The two sorted streams must be in a logical order and the comparator must
 * support keeping that order so the join will work.
 * The left stream will spill to disk when memory is full.
 * The right stream spills to memory and pause when memory is full.
 */
public class MergeJoiner extends AbstractMergeJoiner {

    private MergeStatus status;

    private final IDeallocatableFramePool framePool;
    private IDeletableTupleBufferManager bufferManager;
    private ITuplePointerAccessor memoryAccessor;
    private LinkedList<TuplePointer> memoryBuffer = new LinkedList<>();

    private int leftStreamIndex;
    private RunFileStream runFileStream;

    private final IMergeJoinChecker mjc;

    private static final Logger LOGGER = Logger.getLogger(MergeJoiner.class.getName());

    public MergeJoiner(IHyracksTaskContext ctx, int memorySize, int partition, MergeStatus status, MergeJoinLocks locks,
            IMergeJoinChecker mjc, RecordDescriptor leftRd, RecordDescriptor rightRd) throws HyracksDataException {
        super(ctx, partition, status, locks, leftRd, rightRd);
        this.status = status;
        this.mjc = mjc;

        // Memory (right buffer)
        if (memorySize < 1) {
            throw new HyracksDataException(
                    "MergeJoiner does not have enough memory (needs > 0, got " + memorySize + ").");
        }
        framePool = new DeallocatableFramePool(ctx, (memorySize) * ctx.getInitialFrameSize());
        bufferManager = new VariableDeletableTupleMemoryManager(framePool, rightRd);
        memoryAccessor = bufferManager.createTuplePointerAccessor();

        // Run File and frame cache (left buffer)
        leftStreamIndex = TupleAccessor.UNSET;
        runFileStream = new RunFileStream(ctx, "left", status.branch[LEFT_PARTITION]);

        // Result
        resultAppender = new FrameTupleAppender(new VSizeFrame(ctx));
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(
                    "MergeJoiner has started partition " + partition + " with " + memorySize + " frames of memory.");
        }
    }

    private boolean addToMemory(ITupleAccessor accessor) throws HyracksDataException {
        TuplePointer tp = new TuplePointer();
        if (bufferManager.insertTuple(accessor, accessor.getTupleId(), tp)) {
            memoryBuffer.add(tp);
            return true;
        }
        return false;
    }

    private void removeFromMemory(TuplePointer tp) throws HyracksDataException {
        memoryBuffer.remove(tp);
        bufferManager.deleteTuple(tp);
    }

    private void addToResult(IFrameTupleAccessor accessorLeft, int leftTupleIndex, IFrameTupleAccessor accessorRight,
            int rightTupleIndex, IFrameWriter writer) throws HyracksDataException {
        FrameUtils.appendConcatToWriter(writer, resultAppender, accessorLeft, leftTupleIndex, accessorRight,
                rightTupleIndex);
    }

    @Override
    public void closeResult(IFrameWriter writer) throws HyracksDataException {
        resultAppender.write(writer, true);
    }

    private void flushMemory() throws HyracksDataException {
        bufferManager.reset();
    }

    // memory management
    private boolean memoryHasTuples() {
        return bufferManager.getNumTuples() > 0;
    }

    /**
     * Ensures a frame exists for the right branch, either from memory or the run file.
     *
     * @throws HyracksDataException
     */
    private TupleStatus loadRightTuple() throws HyracksDataException {
        TupleStatus loaded = loadMemoryTuple(RIGHT_PARTITION);
        if (loaded == TupleStatus.UNKNOWN) {
            loaded = pauseAndLoadRightTuple();
        }
        return loaded;
    }

    /**
     * Ensures a frame exists for the right branch, either from memory or the run file.
     *
     * @throws HyracksDataException
     */
    private TupleStatus loadLeftTuple() throws HyracksDataException {
        TupleStatus loaded;
        if (status.branch[LEFT_PARTITION].isRunFileReading()) {
            loaded = loadSpilledTuple(LEFT_PARTITION);
            if (loaded.isEmpty()) {
                continueStream(inputAccessor[LEFT_PARTITION]);
                loaded = loadLeftTuple();
            }
        } else {
            loaded = loadMemoryTuple(LEFT_PARTITION);
        }
        return loaded;
    }

    private TupleStatus loadSpilledTuple(int partition) throws HyracksDataException {
        if (!inputAccessor[partition].exists()) {
            if (!runFileStream.loadNextBuffer(inputAccessor[partition])) {
                return TupleStatus.EMPTY;
            }
        }
        return TupleStatus.LOADED;
    }

    /**
     * Left
     *
     * @throws HyracksDataException
     */
    @Override
    public void processMergeUsingLeftTuple(IFrameWriter writer) throws HyracksDataException {
        TupleStatus ts = loadLeftTuple();
        while (ts.isLoaded() && (status.branch[RIGHT_PARTITION].hasMore() || memoryHasTuples())) {
            if (status.branch[LEFT_PARTITION].isRunFileWriting()) {
                // Left side from disk
                processLeftTupleSpill(writer);
                ts = loadLeftTuple();
            } else if (loadRightTuple().isLoaded()
                    && mjc.checkToLoadNextRightTuple(inputAccessor[LEFT_PARTITION], inputAccessor[RIGHT_PARTITION])) {
                // Right side from stream
                processRightTuple();
            } else {
                // Left side from stream
                processLeftTuple(writer);
                ts = loadLeftTuple();
            }
        }
    }

    private void processLeftTupleSpill(IFrameWriter writer) throws HyracksDataException {
        runFileStream.addToRunFile(inputAccessor[LEFT_PARTITION]);
        processLeftTuple(writer);
        // Memory is empty and we can start processing the run file.
        if (!memoryHasTuples() && status.branch[LEFT_PARTITION].isRunFileWriting()) {
            unfreezeAndContinue(inputAccessor[LEFT_PARTITION]);
        }
    }

    private void processLeftTuple(IFrameWriter writer) throws HyracksDataException {
        // Check against memory (right)
        if (memoryHasTuples()) {
            for (int i = memoryBuffer.size() - 1; i > -1; --i) {
                memoryAccessor.reset(memoryBuffer.get(i));
                //                TuplePrinterUtil.printTuple("     --- A outer", inputAccessor[LEFT_PARTITION]);
                //                TuplePrinterUtil.printTuple("     --- A inner", memoryAccessor);
                if (mjc.checkToSaveInResult(inputAccessor[LEFT_PARTITION], inputAccessor[LEFT_PARTITION].getTupleId(),
                        memoryAccessor, memoryBuffer.get(i).getTupleIndex(), false)) {
                    // add to result
                    //                    System.err.println("  -- Matched --");
                    addToResult(inputAccessor[LEFT_PARTITION], inputAccessor[LEFT_PARTITION].getTupleId(),
                            memoryAccessor, memoryBuffer.get(i).getTupleIndex(), writer);
                }
                if (mjc.checkToRemoveInMemory(inputAccessor[LEFT_PARTITION], inputAccessor[LEFT_PARTITION].getTupleId(),
                        memoryAccessor, memoryBuffer.get(i).getTupleIndex())) {
                    // remove from memory
                    removeFromMemory(memoryBuffer.get(i));
                }
            }
        }
        inputAccessor[LEFT_PARTITION].next();
    }

    private void processRightTuple() throws HyracksDataException {
        // append to memory
        if (mjc.checkToSaveInMemory(inputAccessor[LEFT_PARTITION], inputAccessor[RIGHT_PARTITION])) {
            if (!addToMemory(inputAccessor[RIGHT_PARTITION])) {
                // go to log saving state
                freezeAndSpill();
                return;
            }
        }
        inputAccessor[RIGHT_PARTITION].next();
    }

    private void freezeAndSpill() throws HyracksDataException {
        runFileStream.startRunFile();
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(
                    "Memory is full. Freezing the right branch. (memory tuples: " + bufferManager.getNumTuples() + ")");
        }
    }

    private void continueStream(ITupleAccessor accessor) throws HyracksDataException {
        runFileStream.closeRunFile();
        accessor.reset(inputBuffer[LEFT_PARTITION]);
        accessor.setTupleId(leftStreamIndex);
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Continue with left stream.");
        }
    }

    private void unfreezeAndContinue(ITupleAccessor accessor) throws HyracksDataException {
        runFileStream.flushAndStopRunFile(accessor);
        flushMemory();
        if (!status.branch[LEFT_PARTITION].isRunFileReading()) {
            leftStreamIndex = accessor.getTupleId();
        }
        runFileStream.openRunFile(accessor);
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Unfreezing right partition.");
        }
    }

}