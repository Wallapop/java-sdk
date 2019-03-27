package com.optimizely.ab.processor.batch;

import com.optimizely.ab.common.internal.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A buffer of items bounded by size and/or age.
 *
 * @param <E> the type of buffer
 */
class BatchBufferTask<E, C extends Collection<? super E>> implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(BatchBufferTask.class);
    private static final AtomicInteger sequence = new AtomicInteger();

    private final int id = sequence.incrementAndGet();

    private final Supplier<C> bufferSupplier;
    private final Consumer<C> bufferConsumer;
    private final Integer maxSize;
    private final Duration maxAge;
    private final Clock clock;

    private final Lock lock = new ReentrantLock(true);
    private final Condition condition = lock.newCondition();

    private C buffer;
    private Date deadline; // populated on first element
    private boolean closed;

    public BatchBufferTask(
        Supplier<C> bufferSupplier,
        Consumer<C> bufferConsumer,
        Integer maxSize,
        Duration maxAge
    ) {
        this(bufferSupplier, bufferConsumer, maxSize, maxAge, Clock.systemUTC());
    }

    BatchBufferTask(
        Supplier<C> bufferSupplier,
        Consumer<C> bufferConsumer,
        Integer maxSize,
        Duration maxAge,
        Clock clock
    ) {
        this.bufferSupplier = Assert.notNull(bufferSupplier, "bufferSupplier");
        this.bufferConsumer = bufferConsumer;
        this.maxSize = (maxSize != null && maxSize > 0) ? maxSize : null;
        this.maxAge = (maxAge != null && !maxAge.isNegative() && !maxAge.isZero()) ? maxAge : null;
        this.clock = Assert.notNull(clock, "clock");
    }

    @Override
    public void run() {
        lock.lock();
        try {
            while (!closed) {
                if (deadline != null) {
                    // wait until deadline reached or closed by another thread
                    closed = !condition.awaitUntil(deadline) || closed;
                } else {
                    // unbounded wait until first item is added
                    condition.await();
                }
                logger.info("[{}] Consumer is awake. closed={}", id, closed);
            }
        } catch (InterruptedException e) {
            throw new BatchInterruptedException(this.buffer, "Interrupted while waiting for lock", e);
        } finally {
            lock.unlock();
        }

        if (bufferConsumer != null) {
            bufferConsumer.accept(buffer);
        }
    }

    /**
     * Inserts the specified element into this buffer if the buffer is still open.
     *
     * @param element
     * @return true if element was inserted, otherwise false
     */
    public boolean offer(E element) {
        lock.lock();
        try {
            return insert(element);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Marks the buffer as closed and signals for it to be flushed to consumer on buffering thread. The buffer will no
     * longer accept new elements after this method is invoked.
     */
    public void close() {
        lock.lock();
        try {
            if (closed) {
                logger.trace("[{}] Batch is already closed", id);
                return;
            }

            closed = true;
            condition.signal();
            logger.trace("[{}] Batch is now closed", id);
        } finally {
            lock.unlock();
        }
    }

    Date getDeadline() {
        return deadline;
    }

    int size() {
        return buffer.size();
    }

    boolean isFull() {
        return maxSize != null && size() >= maxSize;
    }

    boolean isClosed() {
        return closed;
    }

    // assumes lock is being held
    private boolean insert(E element) {
        if (closed) {
            return false;
        }

        boolean signal = false;
        if (buffer == null) {
            buffer = bufferSupplier.get();

            // set the deadline when first added and signal to pick it up
            if (maxAge != null) {
                deadline = new Date(clock.millis() + maxAge.toMillis());
                signal = true;
                logger.debug("[{}] Batch deadline: {}", id, deadline);
            }
        }

        buffer.add(element);

        if (isFull()) {
            logger.trace("[{}] Batch is now full", id);
            closed = true;
            signal = true;
        }

        if (signal) {
            logger.trace("[{}] Signalling consumer", id);
            condition.signalAll();
        }

        return true;
    }

    public static class BatchInterruptedException extends BatchingProcessorException {
        private final Collection buffer;

        BatchInterruptedException(Collection buffer, String message, Throwable cause) {
            super(message, cause);
            this.buffer = buffer;
        }

        @Nullable
        public Collection getBuffer() {
            return buffer;
        }
    }
}
