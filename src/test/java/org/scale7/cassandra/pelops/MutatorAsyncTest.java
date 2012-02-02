package org.scale7.cassandra.pelops;

import static org.junit.Assert.assertEquals;
import static org.scale7.cassandra.pelops.ColumnFamilyManager.CFDEF_COMPARATOR_BYTES;
import static org.scale7.cassandra.pelops.ColumnFamilyManager.CFDEF_TYPE_STANDARD;

import java.util.Arrays;

import org.apache.cassandra.thrift.Cassandra.AsyncClient.batch_mutate_call;
import org.apache.cassandra.thrift.CfDef;
import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.junit.BeforeClass;
import org.junit.Test;
import org.scale7.cassandra.pelops.support.AbstractIntegrationTest;

/**
 * Integration test of the asynchronous {@link Mutator} methods.
 * 
 * @author Andrew Swan
 */
public class MutatorAsyncTest extends AbstractIntegrationTest {

    // Constants
    private static final ConsistencyLevel CONSISTENCY_LEVEL = ConsistencyLevel.ONE;
    private static final String COLUMN_FAMILY = MutatorAsyncTest.class.getSimpleName();
    private static final String COLUMN_NAME = "foo";
    private static final String COLUMN_VALUE = "bar";

    @BeforeClass
    public static void setup() throws Exception {
        setup(Arrays.asList(
                new CfDef(KEYSPACE, COLUMN_FAMILY)
                .setColumn_type(CFDEF_TYPE_STANDARD)
                .setComparator_type(CFDEF_COMPARATOR_BYTES)
        ));
    }
    
    @Test(timeout = 200)
    public void testExecuteWithDefaultOperandPolicy() throws Exception {
        // Set up
        Mutator mutator = createMutator();
        String rowKey = "the-key";
        mutator.writeColumn(COLUMN_FAMILY, rowKey, mutator.newColumn(COLUMN_NAME, COLUMN_VALUE));
        
        // Invoke
        BlockingCallback<batch_mutate_call> callback = new BlockingCallback<batch_mutate_call>();
        mutator.execute(CONSISTENCY_LEVEL, callback);
        callback.getResult().getResult();
        
        // Check
        Selector selector = createSelector();
        Column column = selector.getColumnFromRow(COLUMN_FAMILY, rowKey, COLUMN_NAME, CONSISTENCY_LEVEL);
        assertEquals(COLUMN_VALUE, new String(column.getValue()));
    }
}
