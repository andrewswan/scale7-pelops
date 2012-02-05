package org.scale7.cassandra.pelops;

import org.apache.thrift.async.AsyncMethodCallback;

/**
 * An {@link AsyncMethodCallback} that invokes another such callback, converting
 * its own return value into that other callback's return value.
 * 
 * @author Andrew Swan
 * @param <I> the input data type (being converted from)
 * @param <O> the output data type (being converted to)
 */
public abstract class AbstractConvertingCallback<I, O> implements AsyncMethodCallback<I> {

    // Fields
    private final AsyncMethodCallback<O> outerCallback;

    /**
     * Constructor.
     * 
     * @param outerCallback the callback to be invoked by this callback (required)
     */
    protected AbstractConvertingCallback(final AsyncMethodCallback<O> outerCallback) {
        if (outerCallback == null) {
            throw new IllegalArgumentException("Outer callback is required");
        }
        this.outerCallback = outerCallback;
    }
    
    @Override
    public final void onComplete(I inputData) {
        try {
            outerCallback.onComplete(convert(inputData));
        }
        catch (Exception e) {
            onError(e);
        }
    }

    @Override
    public final void onError(Exception exception) {
        outerCallback.onError(exception);
    }

    /**
     * Converts this inner callback's result into the outer callback's result.
     * 
     * @param fromData the data to convert (can be <code>null</code>)
     * @return the converted data (can be <code>null</code>)
     * @throws Exception which will be passed to {@link #onError(Exception)}
     */
    protected abstract O convert(I fromData) throws Exception;
}
