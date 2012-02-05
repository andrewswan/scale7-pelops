package org.scale7.cassandra.pelops;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.apache.commons.lang.ObjectUtils;
import org.apache.thrift.async.AsyncMethodCallback;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit test of {@link AbstractConvertingCallback}.
 * 
 * @author Andrew Swan
 */
public class ConvertingCallbackTest {

    // Fixture
    @Mock private AsyncMethodCallback<String> mockOuterCallback;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testOnComplete() {
        // Set up
        final AsyncMethodCallback<Integer> innerCallback = new AbstractConvertingCallback<Integer, String>(mockOuterCallback) {
            @Override
            protected String convert(final Integer fromData) {
                return ObjectUtils.toString(fromData);
            }
        };
        final int result = 27;

        // Invoke
        innerCallback.onComplete(result);

        // Check
        verify(mockOuterCallback).onComplete(String.valueOf(result));
    }
    
    @Test
    public void testOnError() {
        // Set up
        final AsyncMethodCallback<Integer> innerCallback = new AbstractConvertingCallback<Integer, String>(mockOuterCallback) {
            @Override
            protected String convert(final Integer fromData) {
                throw new IllegalStateException("Should never be called");
            }
        };
        final Exception error = mock(Exception.class);

        // Invoke
        innerCallback.onError(error);

        // Check
        verify(mockOuterCallback).onError(error);
    }
}
