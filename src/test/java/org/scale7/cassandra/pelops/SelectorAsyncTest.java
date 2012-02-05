package org.scale7.cassandra.pelops;

import static org.junit.Assert.assertEquals;
import static org.scale7.cassandra.pelops.ColumnFamilyManager.CFDEF_COMPARATOR_BYTES;
import static org.scale7.cassandra.pelops.ColumnFamilyManager.CFDEF_TYPE_STANDARD;

import java.util.Arrays;

import org.apache.cassandra.thrift.CfDef;
import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.junit.BeforeClass;
import org.junit.Test;
import org.scale7.cassandra.pelops.support.AbstractIntegrationTest;

/**
 * Integration test of the asynchronous {@link Selector} methods.
 * 
 * @author Andrew Swan
 */
public class SelectorAsyncTest extends AbstractIntegrationTest {

    private static final ConsistencyLevel CONSISTENCY_LEVEL = ConsistencyLevel.ONE;
    private static final String COLUMN_FAMILY = MutatorAsyncTest.class.getSimpleName();
    private static final String ROW_KEY = "foo";
    private static final String COLUMN_NAME_PREFIX = "column";
    private static final int COLUMN_COUNT = 27;

    @BeforeClass
    public static void setup() throws Exception {
        setup(Arrays.asList(
                new CfDef(KEYSPACE, COLUMN_FAMILY)
                .setColumn_type(CFDEF_TYPE_STANDARD)
                .setComparator_type(CFDEF_COMPARATOR_BYTES)
        ));
    }
    
    @Test
    public void testGetColumnCount() throws Exception {
        // Set up
        Mutator mutator = createMutator();
        for (int i = 1; i <= COLUMN_COUNT; i++) {
            writeColumn(mutator, i, Bytes.EMPTY);
        }
        mutator.execute(CONSISTENCY_LEVEL);
        
        // Invoke
        Selector selector = createSelector();
        BlockingCallback<Integer> callback = new BlockingCallback<Integer>();
        selector.getColumnCount(COLUMN_FAMILY, ROW_KEY, CONSISTENCY_LEVEL, callback);
        
        // Check
        assertEquals(COLUMN_COUNT, callback.getResult().intValue());
    }

    private void writeColumn(Mutator mutator, int index, Bytes value) {
        mutator.writeColumn(COLUMN_FAMILY, ROW_KEY, mutator.newColumn(COLUMN_NAME_PREFIX + index, value));
    }
    
    @Test(timeout = 1000)
    public void testGetColumnFromRow() throws Exception {
        // Set up
        Mutator mutator = createMutator();
        Bytes rowKey = Bytes.fromUTF8(ROW_KEY);
        Bytes columnName = Bytes.fromUTF8(COLUMN_NAME_PREFIX);
        String columnValue = "guff";
        mutator.writeColumn(COLUMN_FAMILY, rowKey, mutator.newColumn(columnName, columnValue));
        mutator.execute(CONSISTENCY_LEVEL);
        Selector selector = createSelector();
        BlockingCallback<Column> callback = new BlockingCallback<Column>();
        
        // Invoke
        selector.getColumnFromRow(COLUMN_FAMILY, rowKey, columnName, CONSISTENCY_LEVEL, callback);
        
        // Check
        assertEquals(columnValue, new String(callback.getResult().getValue()));
    }
}
