package org.scale7.cassandra.pelops;

import java.util.concurrent.CountDownLatch;

import org.apache.thrift.async.AsyncMethodCallback;

/**
 * An {@link AsyncMethodCallback} that blocks until one of the callback methods
 * has been invoked.
 * <p>
 * Allows an asynchronous method to be easily invoked in a synchronous way.
 *
 * @author Andrew Swan
 * @param <C> the type of call being made
 */
public class BlockingCallback<C> implements AsyncMethodCallback<C> {
            
    // Fields
    private final CountDownLatch latch;
    private Exception exception;
    private C response;
    
    /**
     * Constructor.
     */
    public BlockingCallback() {
        latch = new CountDownLatch(1);
    }

    @Override
    public final void onComplete(final C response) {
        this.response = response;
        latch.countDown();
        doOnComplete(response);
    }

    /**
     * Extension point for subclasses to perform custom handling if and when
     * {@link #onComplete(Object)} is called.
     * <p>
     * This implementation does nothing.
     * 
     * @param response the response passed to {@link #onComplete(Object)}; might
     * be <code>null</code>
     */
    protected void doOnComplete(C response) {}

    @Override
    public final void onError(Exception exception) {
        latch.countDown();
        this.exception = exception;
        doOnError(exception);
    }
    
    /**
     * Extension point for subclasses to perform custom handling if and when
     * {@link #onError(Exception)} is called.
     * <p>
     * This implementation does nothing.
     * 
     * @param exception the exception passed to {@link #onError(Exception)}
     * (never <code>null</code>)
     */
    protected void doOnError(Exception exception) {}
    
    /**
     * Blocks until the callback has been invoked.
     * 
     * @return if {@link #onComplete(Object)} was called, the result of the operation
     * @throws Exception if {@link #onError(Exception)} was called
     */
    public C getResult() throws Exception {
        latch.await();
        if (exception == null) {
            return response;
        }
        throw exception;
    }
}