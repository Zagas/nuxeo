/*
 * (C) Copyright 2017 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     bdelbosc
 */
package org.nuxeo.lib.stream.computation.log;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.lib.stream.codec.Codec;
import org.nuxeo.lib.stream.computation.Computation;
import org.nuxeo.lib.stream.computation.ComputationMetadataMapping;
import org.nuxeo.lib.stream.computation.ComputationPolicy;
import org.nuxeo.lib.stream.computation.Record;
import org.nuxeo.lib.stream.computation.Watermark;
import org.nuxeo.lib.stream.computation.internals.ComputationContextImpl;
import org.nuxeo.lib.stream.computation.internals.WatermarkMonotonicInterval;
import org.nuxeo.lib.stream.log.LogAppender;
import org.nuxeo.lib.stream.log.LogManager;
import org.nuxeo.lib.stream.log.LogPartition;
import org.nuxeo.lib.stream.log.LogRecord;
import org.nuxeo.lib.stream.log.LogTailer;
import org.nuxeo.lib.stream.log.RebalanceException;
import org.nuxeo.lib.stream.log.RebalanceListener;

import io.opencensus.common.Scope;
import io.opencensus.trace.AttributeValue;
import io.opencensus.trace.BlankSpan;
import io.opencensus.trace.Link;
import io.opencensus.trace.Span;
import io.opencensus.trace.SpanContext;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import io.opencensus.trace.propagation.BinaryFormat;
import io.opencensus.trace.propagation.SpanContextParseException;

import net.jodah.failsafe.Failsafe;

/**
 * Thread driving a Computation
 *
 * @since 9.3
 */
@SuppressWarnings("EmptyMethod")
public class ComputationRunner implements Runnable, RebalanceListener {
    public static final Duration READ_TIMEOUT = Duration.ofMillis(25);

    protected static final long STARVING_TIMEOUT_MS = 1000;

    protected static final long INACTIVITY_BREAK_MS = 100;

    private static final Log log = LogFactory.getLog(ComputationRunner.class);

    protected final LogManager logManager;

    protected final ComputationMetadataMapping metadata;

    protected final LogTailer<Record> tailer;

    protected final Supplier<Computation> supplier;

    protected final CountDownLatch assignmentLatch = new CountDownLatch(1);

    protected final WatermarkMonotonicInterval lowWatermark = new WatermarkMonotonicInterval();

    protected final Codec<Record> inputCodec;

    protected final Codec<Record> outputCodec;

    protected final ComputationPolicy policy;

    protected ComputationContextImpl context;

    protected volatile boolean stop;

    protected volatile boolean drain;

    protected Computation computation;

    protected long counter;

    protected long inRecords;

    protected long inCheckpointRecords;

    protected long outRecords;

    protected long lastReadTime = System.currentTimeMillis();

    protected long lastTimerExecution;

    protected String threadName;

    protected SpanContext lastSpanContext;

    @SuppressWarnings("unchecked")
    public ComputationRunner(Supplier<Computation> supplier, ComputationMetadataMapping metadata,
            List<LogPartition> defaultAssignment, LogManager logManager, Codec<Record> inputCodec,
            Codec<Record> outputCodec, ComputationPolicy policy) {
        this.supplier = supplier;
        this.metadata = metadata;
        this.logManager = logManager;
        this.context = new ComputationContextImpl(logManager, metadata, policy);
        this.inputCodec = inputCodec;
        this.outputCodec = outputCodec;
        this.policy = policy;
        if (metadata.inputStreams().isEmpty()) {
            this.tailer = null;
            assignmentLatch.countDown();
        } else if (logManager.supportSubscribe()) {
            this.tailer = logManager.subscribe(metadata.name(), metadata.inputStreams(), this, inputCodec);
        } else {
            this.tailer = logManager.createTailer(metadata.name(), defaultAssignment, inputCodec);
            assignmentLatch.countDown();
        }
    }

    public void stop() {
        log.debug(metadata.name() + ": Receives Stop signal");
        stop = true;
        if (computation != null) {
            computation.signalStop();
        }
    }

    public void drain() {
        log.debug(metadata.name() + ": Receives Drain signal");
        drain = true;
    }

    public boolean waitForAssignments(Duration timeout) throws InterruptedException {
        if (!assignmentLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            log.warn(metadata.name() + ": Timeout waiting for assignment");
            return false;
        }
        return true;
    }

    @Override
    public void run() {
        threadName = Thread.currentThread().getName();
        boolean interrupted = false;
        computation = supplier.get();
        log.debug(metadata.name() + ": Init");
        try {
            computation.init(context);
            log.debug(metadata.name() + ": Start");
            processLoop();
        } catch (InterruptedException e) {
            interrupted = true; // Thread.currentThread().interrupt() in finally
            // this is expected when the pool is shutdownNow
            String msg = metadata.name() + ": Interrupted";
            if (log.isTraceEnabled()) {
                log.debug(msg, e);
            } else {
                log.debug(msg);
            }
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                // this can happen when pool is shutdownNow throwing ClosedByInterruptException
                log.info(metadata.name() + ": Interrupted", e);
            } else {
                log.error(metadata.name() + ": Exception in processLoop: " + e.getMessage(), e);
                throw e;
            }
        } finally {
            try {
                computation.destroy();
                closeTailer();
                log.debug(metadata.name() + ": Exited");
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    protected void closeTailer() {
        if (tailer != null && !tailer.closed()) {
            tailer.close();
        }
    }

    protected void processLoop() throws InterruptedException {
        boolean activity;
        while (continueLoop()) {
            activity = processTimer();
            activity |= processRecord();
            counter++;
            if (!activity) {
                // no timer nor record to process, take a break
                Thread.sleep(INACTIVITY_BREAK_MS);
            }
        }
    }

    protected boolean continueLoop() {
        if (stop || Thread.currentThread().isInterrupted()) {
            return false;
        } else if (drain) {
            long now = System.currentTimeMillis();
            // for a source we take lastTimerExecution starvation
            if (metadata.inputStreams().isEmpty()) {
                if (lastTimerExecution > 0 && (now - lastTimerExecution) > STARVING_TIMEOUT_MS) {
                    log.info(metadata.name() + ": End of source drain, last timer " + STARVING_TIMEOUT_MS + " ms ago");
                    return false;
                }
            } else {
                if ((now - lastReadTime) > STARVING_TIMEOUT_MS) {
                    log.info(metadata.name() + ": End of drain no more input after " + (now - lastReadTime) + " ms, "
                            + inRecords + " records read, " + counter + " reads attempt");
                    return false;
                }
            }
        }
        return true;
    }

    protected boolean processTimer() {
        Map<String, Long> timers = context.getTimers();
        if (timers.isEmpty()) {
            return false;
        }
        if (tailer != null && tailer.assignments().isEmpty()) {
            // needed to ensure single source across multiple nodes
            return false;
        }
        long now = System.currentTimeMillis();
        // filter and order timers
        LinkedHashMap<String, Long> sortedTimer = timers.entrySet()
                                                        .stream()
                                                        .filter(entry -> entry.getValue() <= now)
                                                        .sorted(Map.Entry.comparingByValue())
                                                        .collect(
                                                                Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                                                                        (e1, e2) -> e1, LinkedHashMap::new));
        return processTimerWithTracing(now, sortedTimer);
    }

    protected boolean processTimerWithTracing(long now, LinkedHashMap<String, Long> sortedTimer) {
        Tracer tracer = Tracing.getTracer();
        Span span;
        if (lastSpanContext != null) {
            span = tracer.spanBuilderWithRemoteParent("comp." + computation.metadata().name() + ".timer",
                    lastSpanContext).startSpan();
            span.addLink(Link.fromSpanContext(lastSpanContext, Link.Type.PARENT_LINKED_SPAN));
            HashMap<String, AttributeValue> map = new HashMap<>();
            map.put("comp.thread", AttributeValue.stringAttributeValue(Thread.currentThread().getName()));
            map.put("record.last_offset", AttributeValue.stringAttributeValue(context.getLastOffset().toString()));
            span.putAttributes(map);
            lastSpanContext = null;
        } else {
            span = BlankSpan.INSTANCE;
        }
        try (Scope scope = Tracing.getTracer().withSpan(span)) {
            final boolean[] timerUpdate = { false };
            sortedTimer.forEach((key, value) -> {
                context.removeTimer(key);
                processTimerWithRetry(key, value);
                timerUpdate[0] = true;
            });
            if (timerUpdate[0]) {
                checkSourceLowWatermark();
                lastTimerExecution = now;
                setThreadName("timer");
                checkpointIfNecessary();
                if (context.requireTerminate()) {
                    stop = true;
                }
                return true;
            }
            return false;
        } finally {
            span.end();
        }
    }

    protected void processTimerWithRetry(String key, Long value) {
        Failsafe.with(policy.getRetryPolicy())
                .onRetry(failure -> computation.processRetry(context, failure))
                .onFailure(failure -> computation.processFailure(context, failure))
                .withFallback(() -> processFallback(context))
                .run(() -> computation.processTimer(context, key, value));
    }

    protected boolean processRecord() throws InterruptedException {
        if (context.requireTerminate()) {
            stop = true;
            return true;
        }
        if (tailer == null) {
            return false;
        }
        Duration timeoutRead = getTimeoutDuration();
        LogRecord<Record> logRecord = null;
        try {
            logRecord = tailer.read(timeoutRead);
        } catch (RebalanceException e) {
            // the revoke has done a checkpoint we can continue
        }
        Record record;
        if (logRecord != null) {
            record = logRecord.message();
            lastReadTime = System.currentTimeMillis();
            inRecords++;
            lowWatermark.mark(record.getWatermark());
            String from = metadata.reverseMap(logRecord.offset().partition().name());
            context.setLastOffset(logRecord.offset());
            processRecordWithTracing(from, record);
            return true;
        }
        return false;
    }

    protected void processRecordWithTracing(String from, Record record) {
        Span span = getSpanFromRecord(record);
        try (Scope scope = Tracing.getTracer().withSpan(span)) {
            processRecordWithRetry(from, record);
            checkRecordFlags(record);
            checkSourceLowWatermark();
            setThreadName("record");
            checkpointIfNecessary();
        } finally {
            span.end();
        }
    }

    protected Span getSpanFromRecord(Record record) {
        byte[] traceContext = record.getTraceContext();
        if (traceContext == null || traceContext.length == 0) {
            return BlankSpan.INSTANCE;
        }
        Tracer tracer = Tracing.getTracer();
        BinaryFormat binaryFormat = Tracing.getPropagationComponent().getBinaryFormat();
        try {
            // Build a span that has a follows from relationship with the parent span to denote an async processing
            lastSpanContext = binaryFormat.fromByteArray(traceContext);
            Span span = tracer.spanBuilderWithRemoteParent("comp." + computation.metadata().name(), lastSpanContext)
                              .startSpan();
            span.addLink(Link.fromSpanContext(lastSpanContext, Link.Type.PARENT_LINKED_SPAN));

            HashMap<String, AttributeValue> map = new HashMap<>();
            map.put("comp.thread", AttributeValue.stringAttributeValue(Thread.currentThread().getName()));
            map.put("record.key", AttributeValue.stringAttributeValue(record.getKey()));
            map.put("record.offset", AttributeValue.stringAttributeValue(context.getLastOffset().toString()));
            map.put("record.watermark",
                    AttributeValue.stringAttributeValue(Watermark.ofValue(record.getWatermark()).toString()));
            map.put("record.submit_thread", AttributeValue.stringAttributeValue(record.getAppenderThread()));
            map.put("record.data.length", AttributeValue.longAttributeValue(record.getData().length));
            span.putAttributes(map);
            return span;
        } catch (SpanContextParseException e) {
            log.warn("Invalid span context " + traceContext.length);
        }
        return BlankSpan.INSTANCE;
    }

    protected void processRecordWithRetry(String from, Record record) {
            Failsafe.with(policy.getRetryPolicy())
                    .onRetry(failure -> computation.processRetry(context, failure))
                    .onFailure(failure -> computation.processFailure(context, failure))
                    .withFallback(() -> processFallback(context))
                    .run(() -> computation.processRecord(context, from, record));
    }

    protected void processFallback(ComputationContextImpl context) {
        if (policy.continueOnFailure()) {
            log.error(String.format("Skip record after failure: %s", context.getLastOffset()));
            context.askForCheckpoint();
        } else {
            log.error(String.format("Terminate computation: %s due to previous failure", metadata.name()));
            context.cancelAskForCheckpoint();
            context.askForTermination();
        }
    }

    protected Duration getTimeoutDuration() {
        // Adapt the duration so we are not throttling when one of the input stream is empty
        return Duration.ofMillis(Math.min(READ_TIMEOUT.toMillis(), System.currentTimeMillis() - lastReadTime));
    }

    protected void checkSourceLowWatermark() {
        long watermark = context.getSourceLowWatermark();
        if (watermark > 0) {
            lowWatermark.mark(Watermark.ofValue(watermark));
            context.setSourceLowWatermark(0);
        }
    }

    protected void checkRecordFlags(Record record) {
        if (record.getFlags().contains(Record.Flag.POISON_PILL)) {
            log.info(metadata.name() + ": Receive POISON PILL");
            context.askForCheckpoint();
            stop = true;
        } else if (record.getFlags().contains(Record.Flag.COMMIT)) {
            context.askForCheckpoint();
        }
    }

    protected void checkpointIfNecessary() {
        if (context.requireCheckpoint()) {
            boolean completed = false;
            try {
                checkpoint();
                completed = true;
            } finally {
                if (!completed) {
                    log.error(metadata.name() + ": CHECKPOINT FAILURE: Resume may create duplicates.");
                }
            }
        }
    }

    protected void checkpoint() {
        sendRecords();
        saveTimers();
        saveState();
        // To Simulate slow checkpoint add a Thread.sleep(1)
        saveOffsets();
        lowWatermark.checkpoint();
        context.removeCheckpointFlag();
        log.debug(metadata.name() + ": checkpoint");
        inCheckpointRecords = inRecords;
        setThreadName("checkpoint");
    }

    protected void saveTimers() {
        // TODO: save timers in the key value store NXP-22112
    }

    protected void saveState() {
        // TODO: save key value store NXP-22112
    }

    protected void saveOffsets() {
        if (tailer != null) {
            tailer.commit();
            Span span = Tracing.getTracer().getCurrentSpan();
            span.addAnnotation("Checkpoint positions" + Instant.now().toString());
        }
    }

    protected void sendRecords() {
        boolean firstRecord = true;
        for (String stream : metadata.outputStreams()) {
            LogAppender<Record> appender = logManager.getAppender(stream, outputCodec);
            for (Record record : context.getRecords(stream)) {
                if (record.getWatermark() == 0) {
                    // use low watermark when not set
                    record.setWatermark(lowWatermark.getLow().getValue());
                }
                if (firstRecord) {
                    Span span = Tracing.getTracer().getCurrentSpan();
                    span.addAnnotation("Sending records " + Instant.now().toString());
                    firstRecord = false;
                }
                appender.append(record.getKey(), record);
                outRecords++;
            }
            context.getRecords(stream).clear();
        }
    }

    public Watermark getLowWatermark() {
        return lowWatermark.getLow();
    }

    protected void setThreadName(String message) {
        String name = threadName + ",in:" + inRecords + ",inCheckpoint:" + inCheckpointRecords + ",out:" + outRecords
                + ",lastRead:" + lastReadTime + ",lastTimer:" + lastTimerExecution + ",wm:"
                + lowWatermark.getLow().getValue() + ",loop:" + counter;
        if (message != null) {
            name += "," + message;
        }
        Thread.currentThread().setName(name);
    }

    @Override
    public void onPartitionsRevoked(Collection<LogPartition> partitions) {
        setThreadName("rebalance revoked");
    }

    @Override
    public void onPartitionsAssigned(Collection<LogPartition> partitions) {
        lastReadTime = System.currentTimeMillis();
        setThreadName("rebalance assigned");
        // reset the context
        this.context = new ComputationContextImpl(logManager, metadata, policy);
        log.debug(metadata.name() + ": Init");
        computation.init(context);
        lastReadTime = System.currentTimeMillis();
        lastTimerExecution = 0;
        assignmentLatch.countDown();
        // what about watermark ?
    }
}
