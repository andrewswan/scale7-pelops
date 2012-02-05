/*
 * The MIT License
 *
 * Copyright (c) 2011 Dominic Williams, Daniel Washusen and contributors.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.scale7.cassandra.pelops;

import static org.scale7.cassandra.pelops.Bytes.fromByteBuffer;
import static org.scale7.cassandra.pelops.Bytes.fromUTF8;
import static org.scale7.cassandra.pelops.Bytes.nullSafeGet;
import static org.scale7.cassandra.pelops.Bytes.toUTF8;
import static org.scale7.cassandra.pelops.ColumnOrSuperColumnHelper.COLUMN;
import static org.scale7.cassandra.pelops.ColumnOrSuperColumnHelper.COUNTER_COLUMN;
import static org.scale7.cassandra.pelops.ColumnOrSuperColumnHelper.SUPER_COLUMN;
import static org.scale7.cassandra.pelops.ColumnOrSuperColumnHelper.transform;
import static org.scale7.cassandra.pelops.ColumnOrSuperColumnHelper.transformKeySlices;
import static org.scale7.cassandra.pelops.ColumnOrSuperColumnHelper.transformKeySlicesUtf8;
import static org.scale7.cassandra.pelops.ColumnOrSuperColumnHelper.transformUtf8;
import static org.scale7.cassandra.pelops.Validation.safeGetRowKey;
import static org.scale7.cassandra.pelops.Validation.validateRowKeys;
import static org.scale7.cassandra.pelops.Validation.validateRowKeysUtf8;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.cassandra.thrift.Cassandra.AsyncClient.get_call;
import org.apache.cassandra.thrift.Cassandra.AsyncClient.get_count_call;
import org.apache.cassandra.thrift.Cassandra.AsyncClient.get_indexed_slices_call;
import org.apache.cassandra.thrift.Cassandra.AsyncClient.get_range_slices_call;
import org.apache.cassandra.thrift.Cassandra.AsyncClient.get_slice_call;
import org.apache.cassandra.thrift.Cassandra.AsyncClient.multiget_slice_call;
import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.ColumnOrSuperColumn;
import org.apache.cassandra.thrift.ColumnParent;
import org.apache.cassandra.thrift.ColumnPath;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.CounterColumn;
import org.apache.cassandra.thrift.IndexClause;
import org.apache.cassandra.thrift.IndexExpression;
import org.apache.cassandra.thrift.IndexOperator;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.thrift.KeyRange;
import org.apache.cassandra.thrift.KeySlice;
import org.apache.cassandra.thrift.SlicePredicate;
import org.apache.cassandra.thrift.SliceRange;
import org.apache.cassandra.thrift.SuperColumn;
import org.apache.thrift.async.AsyncMethodCallback;
import org.scale7.cassandra.pelops.exceptions.NotFoundException;
import org.scale7.cassandra.pelops.exceptions.PelopsException;
import org.scale7.cassandra.pelops.pool.IThriftPool;
import org.scale7.cassandra.pelops.pool.IThriftPool.IPooledConnection;

/**
 * Facilitates the selective retrieval of column data from rows in a Cassandra keyspace.<p/>
 *
 * <p><b>Note</b>:The methods that are marked as throwing {@link org.scale7.cassandra.pelops.exceptions.NotFoundException}
 * are the only methods in Pelops that throw exceptions under non-failure conditions.
 *
 * @author dominicwilliams
 * TODO decide whether to make Iterator-returning methods asynchronous
 */
public class Selector extends Operand {

    // SlicePredicates constants for common internal uses
    private static final SlicePredicate COLUMNS_PREDICATE_ALL = newColumnsPredicateAll(false);
    private static final SlicePredicate COLUMNS_PREDICATE_ALL_REVERSED = newColumnsPredicateAll(true);

    /**
     * Get the count of columns in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The count of the columns
     * @throws PelopsException if an error occurs
     */
    public int getColumnCount(String columnFamily, Bytes rowKey, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnCount(newColumnParent(columnFamily), rowKey, COLUMNS_PREDICATE_ALL, cLevel);
    }
    
    /**
     * Asynchronously get the count of columns in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnCount(String columnFamily, Bytes rowKey, ConsistencyLevel cLevel, AsyncMethodCallback<Integer> callback) throws PelopsException {
        getColumnCount(newColumnParent(columnFamily), rowKey, COLUMNS_PREDICATE_ALL, cLevel, callback);
    }

    /**
     * Get the count of columns in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param predicate						A predicate selecting the columns to be counted
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The count of the columns
     * @throws PelopsException if an error occurs
     */
    public int getColumnCount(String columnFamily, Bytes rowKey, SlicePredicate predicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnCount(newColumnParent(columnFamily), rowKey, predicate, cLevel);
    }

    /**
     * Asynchronously get the count of columns in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param predicate                     A predicate selecting the columns to be counted
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnCount(String columnFamily, Bytes rowKey, SlicePredicate predicate, ConsistencyLevel cLevel, AsyncMethodCallback<Integer> callback) throws PelopsException {
        getColumnCount(newColumnParent(columnFamily), rowKey, predicate, cLevel, callback);
    }

    /**
     * Get the count of columns in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The count of the columns
     * @throws PelopsException if an error occurs
     */
    public int getColumnCount(String columnFamily, String rowKey, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnCount(newColumnParent(columnFamily), fromUTF8(rowKey), COLUMNS_PREDICATE_ALL, cLevel);
    }

    /**
     * Asynchronously get the count of columns in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnCount(String columnFamily, String rowKey, ConsistencyLevel cLevel, AsyncMethodCallback<Integer> callback) throws PelopsException {
        getColumnCount(newColumnParent(columnFamily), fromUTF8(rowKey), COLUMNS_PREDICATE_ALL, cLevel, callback);
    }
    
    /**
     * Get the count of columns in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param predicate                     A predicate selecting the columns to be counted
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The count of the columns
     * @throws PelopsException if an error occurs
     */
    public int getColumnCount(String columnFamily, String rowKey, SlicePredicate predicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnCount(newColumnParent(columnFamily), fromUTF8(rowKey), predicate, cLevel);
    }
    
    /**
     * Asynchronously get the count of columns in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param predicate						A predicate selecting the columns to be counted
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnCount(String columnFamily, String rowKey, SlicePredicate predicate, ConsistencyLevel cLevel, AsyncMethodCallback<Integer> callback) throws PelopsException {
        getColumnCount(newColumnParent(columnFamily), fromUTF8(rowKey), predicate, cLevel, callback);
    }

    /**
     * Get the count of sub-columns inside a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The count of the sub-columns
     * @throws PelopsException if an error occurs
     */
    public int getSubColumnCount(String columnFamily, String rowKey, Bytes superColName, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnCount(newColumnParent(columnFamily, superColName), fromUTF8(rowKey), COLUMNS_PREDICATE_ALL, cLevel);
    }

    /**
     * Asynchronously get the count of sub-columns inside a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnCount(String columnFamily, String rowKey, Bytes superColName, ConsistencyLevel cLevel, AsyncMethodCallback<Integer> callback) throws PelopsException {
        getColumnCount(newColumnParent(columnFamily, superColName), fromUTF8(rowKey), COLUMNS_PREDICATE_ALL, cLevel, callback);
    }

    /**
     * Get the count of sub-columns inside a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column
     * @param predicate						A predicate selecting the sub columns to be counted
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The count of the sub-columns
     * @throws PelopsException if an error occurs
     */
    public int getSubColumnCount(String columnFamily, String rowKey, Bytes superColName, SlicePredicate predicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnCount(newColumnParent(columnFamily, superColName), fromUTF8(rowKey), predicate, cLevel);
    }

    /**
     * Asynchronously get the count of sub-columns inside a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column
     * @param predicate                     A predicate selecting the sub columns to be counted
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnCount(String columnFamily, String rowKey, Bytes superColName, SlicePredicate predicate, ConsistencyLevel cLevel, AsyncMethodCallback<Integer> callback) throws PelopsException {
        getColumnCount(newColumnParent(columnFamily, superColName), fromUTF8(rowKey), predicate, cLevel, callback);
    }
    
    /**
     * Get the count of sub-columns inside a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The count of the sub-columns
     * @throws PelopsException if an error occurs
     */
    public int getSubColumnCount(String columnFamily, Bytes rowKey, Bytes superColName, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnCount(newColumnParent(columnFamily, superColName), rowKey, COLUMNS_PREDICATE_ALL, cLevel);
    }
    
    /**
     * Asynchronously get the count of sub-columns inside a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnCount(String columnFamily, Bytes rowKey, Bytes superColName, ConsistencyLevel cLevel, AsyncMethodCallback<Integer> callback) throws PelopsException {
        getColumnCount(newColumnParent(columnFamily, superColName), rowKey, COLUMNS_PREDICATE_ALL, cLevel, callback);
    }
    
    /**
     * Get the count of sub-columns inside a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column
     * @param predicate						A predicate selecting the sub columns to be counted
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The count of the sub-columns
     * @throws PelopsException if an error occurs
     */
    public int getSubColumnCount(String columnFamily, Bytes rowKey, Bytes superColName, SlicePredicate predicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnCount(newColumnParent(columnFamily, superColName), rowKey, predicate, cLevel);
    }
    
    /**
     * Asynchronously get the count of sub-columns inside a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column
     * @param predicate                     A predicate selecting the sub columns to be counted
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnCount(String columnFamily, Bytes rowKey, Bytes superColName, SlicePredicate predicate, ConsistencyLevel cLevel, AsyncMethodCallback<Integer> callback) throws PelopsException {
        getColumnCount(newColumnParent(columnFamily, superColName), rowKey, predicate, cLevel, callback);
    }
    
    /**
     * Get the count of sub-columns inside a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The count of the sub-columns
     * @throws PelopsException if an error occurs
     */
    public int getSubColumnCount(String columnFamily, String rowKey, String superColName, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnCount(newColumnParent(columnFamily, superColName), fromUTF8(rowKey), COLUMNS_PREDICATE_ALL, cLevel);
    }
    
    /**
     * Asynchronously get the count of sub-columns inside a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnCount(String columnFamily, String rowKey, String superColName, ConsistencyLevel cLevel, AsyncMethodCallback<Integer> callback) throws PelopsException {
        getColumnCount(newColumnParent(columnFamily, superColName), fromUTF8(rowKey), COLUMNS_PREDICATE_ALL, cLevel, callback);
    }

    /**
     * Get the count of sub-columns inside a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column
     * @param predicate						A predicate selecting the sub columns to be counted
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The count of the sub-columns
     * @throws PelopsException if an error occurs
     */
    public int getSubColumnCount(String columnFamily, String rowKey, String superColName, SlicePredicate predicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnCount(newColumnParent(columnFamily, superColName), fromUTF8(rowKey), predicate, cLevel);
    }

    /**
     * Asynchronously get the count of sub-columns inside a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column
     * @param predicate                     A predicate selecting the sub columns to be counted
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnCount(String columnFamily, String rowKey, String superColName, SlicePredicate predicate, ConsistencyLevel cLevel, AsyncMethodCallback<Integer> callback) throws PelopsException {
        getColumnCount(newColumnParent(columnFamily, superColName), fromUTF8(rowKey), predicate, cLevel, callback);
    }
    
    /**
     * Get the count of sub-columns inside a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The count of the sub-columns
     * @throws PelopsException if an error occurs
     */
    public int getSubColumnCount(String columnFamily, Bytes rowKey, String superColName, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnCount(newColumnParent(columnFamily, superColName), rowKey, COLUMNS_PREDICATE_ALL, cLevel);
    }
    
    /**
     * Asynchronously get the count of sub-columns inside a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnCount(String columnFamily, Bytes rowKey, String superColName, ConsistencyLevel cLevel, AsyncMethodCallback<Integer> callback) throws PelopsException {
        getColumnCount(newColumnParent(columnFamily, superColName), rowKey, COLUMNS_PREDICATE_ALL, cLevel, callback);
    }
    
    /**
     * Get the count of sub-columns inside a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column
     * @param predicate						A predicate selecting the sub columns to be counted
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The count of the sub-columns
     * @throws PelopsException if an error occurs
     */
    public int getSubColumnCount(String columnFamily, Bytes rowKey, String superColName, SlicePredicate predicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnCount(newColumnParent(columnFamily, superColName), rowKey, predicate, cLevel);
    }
    
    /**
     * Asynchronously get the count of sub-columns inside a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column
     * @param predicate                     A predicate selecting the sub columns to be counted
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnCount(String columnFamily, Bytes rowKey, String superColName, SlicePredicate predicate, ConsistencyLevel cLevel, AsyncMethodCallback<Integer> callback) throws PelopsException {
        getColumnCount(newColumnParent(columnFamily, superColName), rowKey, predicate, cLevel, callback);
    }
    
    /**
     * Get the count of super columns in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The count of the super columns
     * @throws PelopsException if an error occurs
     */
    public int getSuperColumnCount(String columnFamily, Bytes rowKey, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnCount(newColumnParent(columnFamily), rowKey, COLUMNS_PREDICATE_ALL, cLevel);
    }

    /**
     * Asynchronously get the count of super columns in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param predicate						A predicate selecting the super columns to be counted
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSuperColumnCount(String columnFamily, Bytes rowKey, SlicePredicate predicate, ConsistencyLevel cLevel, AsyncMethodCallback<Integer> callback) throws PelopsException {
        getColumnCount(newColumnParent(columnFamily), rowKey, predicate, cLevel, callback);
    }

    /**
     * Get the count of super columns in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The count of the super columns
     * @throws PelopsException if an error occurs
     */
    public int getSuperColumnCount(String columnFamily, String rowKey, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnCount(newColumnParent(columnFamily), fromUTF8(rowKey), COLUMNS_PREDICATE_ALL, cLevel);
    }

    /**
     * Asynchronously get the count of super columns in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSuperColumnCount(String columnFamily, String rowKey, ConsistencyLevel cLevel, AsyncMethodCallback<Integer> callback) throws PelopsException {
        getColumnCount(newColumnParent(columnFamily), fromUTF8(rowKey), COLUMNS_PREDICATE_ALL, cLevel, callback);
    }

    /**
     * Get the count of super columns in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param predicate                     A predicate selecting the super columns to be counted
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The count of the super columns
     * @throws PelopsException if an error occurs
     */
    public int getSuperColumnCount(String columnFamily, String rowKey, SlicePredicate predicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnCount(newColumnParent(columnFamily), fromUTF8(rowKey), predicate, cLevel);
    }
    
    /**
     * Asynchronously get the count of super columns in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param predicate						A predicate selecting the super columns to be counted
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSuperColumnCount(String columnFamily, String rowKey, SlicePredicate predicate, ConsistencyLevel cLevel, AsyncMethodCallback<Integer> callback) throws PelopsException {
        getColumnCount(newColumnParent(columnFamily), fromUTF8(rowKey), predicate, cLevel, callback);
    }

    /**
     * Get the count of columns in a row with a matching predicate
     * 
     * @param colParent						The parent of the columns to be counted
     * @param rowKey						The key of the row containing the columns
     * @param predicate						The slice predicate selecting the columns to be counted
     * @param cLevel						The Cassandra consistency level with which to perform the operation
     * @return								The number of matching columns
     * @throws PelopsException					The error
     */
    private int getColumnCount(final ColumnParent colParent, final Bytes rowKey, final SlicePredicate predicate, final ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<Integer> callback = new BlockingCallback<Integer>();
        getColumnCount(colParent, rowKey, predicate, cLevel, callback);
        return getResult(callback);
    }

    /**
     * Asynchronously get the count of columns in a row with a matching predicate
     * 
     * @param colParent                     The parent of the columns to be counted
     * @param rowKey                        The key of the row containing the columns
     * @param predicate                     The slice predicate selecting the columns to be counted
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException                  The error
     */
    private void getColumnCount(final ColumnParent colParent, final Bytes rowKey, final SlicePredicate predicate, final ConsistencyLevel cLevel, final AsyncMethodCallback<Integer> callback) throws PelopsException {
        final AsyncMethodCallback<get_count_call> innerCallback = new AbstractConvertingCallback<get_count_call, Integer>(callback) {

            @Override
            protected Integer convert(get_count_call getCountCall) throws Exception {
                return getCountCall.getResult();
            }
        };
        
        tryOperation(new IOperation<Void>() {
            @Override
            public Void execute(IPooledConnection conn) throws Exception {
                conn.getAPI().get_count(safeGetRowKey(rowKey), colParent, predicate, cLevel, innerCallback);
                return null;
            }
        });
    }
    
    /**
     * Retrieve a column from a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param colName                       The name of the column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The requested <code>Column</code>
     * @throws NotFoundException            If no value is present
     * @throws PelopsException if an error occurs
     */
    public Column getColumnFromRow(String columnFamily, String rowKey, String colName, ConsistencyLevel cLevel) throws NotFoundException, PelopsException {
        return getColumnFromRow(columnFamily, rowKey, fromUTF8(colName), cLevel);
    }

    /**
     * Asynchronously retrieve a column from a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param colName                       The name of the column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnFromRow(String columnFamily, String rowKey, String colName, ConsistencyLevel cLevel, AsyncMethodCallback<Column> callback) throws NotFoundException, PelopsException {
        getColumnFromRow(columnFamily, rowKey, fromUTF8(colName), cLevel, callback);
    }
    
    /**
     * Retrieve a column from a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param colName                       The name of the column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The requested <code>Column</code>
     * @throws NotFoundException            If no value is present
     * @throws PelopsException if an error occurs
     */
    public Column getColumnFromRow(String columnFamily, String rowKey, Bytes colName, ConsistencyLevel cLevel) throws NotFoundException, PelopsException {
        return getColumnFromRow(columnFamily, fromUTF8(rowKey), colName, cLevel);
    }
    
    /**
     * Asynchronously retrieve a column from a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param colName                       The name of the column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnFromRow(String columnFamily, String rowKey, Bytes colName, ConsistencyLevel cLevel, AsyncMethodCallback<Column> callback) throws PelopsException {
        getColumnFromRow(columnFamily, fromUTF8(rowKey), colName, cLevel, callback);
    }

    private ColumnOrSuperColumn getColumnOrSuperColumnFromRow(String columnFamily, final Bytes rowKey, Bytes superColName, Bytes colName, final ConsistencyLevel cLevel) throws NotFoundException, PelopsException {
        BlockingCallback<ColumnOrSuperColumn> callback = new BlockingCallback<ColumnOrSuperColumn>();
        getColumnOrSuperColumnFromRow(columnFamily, rowKey, superColName, colName, cLevel, callback);
        return getResult(callback);
    }

    private void getColumnOrSuperColumnFromRow(String columnFamily, final Bytes rowKey, Bytes superColName, Bytes colName, final ConsistencyLevel cLevel, final AsyncMethodCallback<ColumnOrSuperColumn> callback) throws PelopsException {
        final AsyncMethodCallback<get_call> getCallback = new AbstractConvertingCallback<get_call, ColumnOrSuperColumn>(callback) {

            @Override
            protected ColumnOrSuperColumn convert(get_call getCall) throws Exception {
                return getCall.getResult();
            }
        };
        final ColumnPath cp = newColumnPath(columnFamily, superColName, colName);
        tryOperation(new IOperation<Void>() {
            @Override
            public Void execute(IThriftPool.IPooledConnection conn) throws Exception {
                conn.getAPI().get(safeGetRowKey(rowKey), cp, cLevel, getCallback);
                return null;
            }
        });
    }
    
    /**
     * Retrieve a column from a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param colName                       The name of the column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The requested <code>Column</code>
     * @throws NotFoundException            If no value is present
     * @throws PelopsException if an error occurs
     */
    public Column getColumnFromRow(String columnFamily, Bytes rowKey, Bytes colName, ConsistencyLevel cLevel) throws NotFoundException, PelopsException {
        return getColumnOrSuperColumnFromRow(columnFamily, rowKey, null, colName, cLevel).column;
    }
    
    /**
     * Asynchronously retrieve a column from a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param colName                       The name of the column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnFromRow(String columnFamily, Bytes rowKey, Bytes colName, ConsistencyLevel cLevel, AsyncMethodCallback<Column> callback) {
        AsyncMethodCallback<ColumnOrSuperColumn> columnOrSuperColumnCallback = new AbstractConvertingCallback<ColumnOrSuperColumn, Column>(callback) {

            @Override
            protected Column convert(ColumnOrSuperColumn columnOrSuperColumn) throws Exception {
                return columnOrSuperColumn.column;
            }
        };
        getColumnOrSuperColumnFromRow(columnFamily, rowKey, null, colName, cLevel, columnOrSuperColumnCallback);
    }

    /**
     * Retrieves a counter column from a row.
     *
     * @param columnFamily        The column family containing the row
     * @param rowKey              The key of the row
     * @param colName             The name of the column to retrieve
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @return                    The requested <code>Column</code>
     * @throws NotFoundException  If no value is present
     * @throws PelopsException    If an error occurs
     */
    public CounterColumn getCounterColumnFromRow(String columnFamily, String rowKey, String colName, final ConsistencyLevel cLevel) throws NotFoundException, PelopsException {
        return getCounterColumnFromRow(columnFamily, fromUTF8(rowKey), fromUTF8(colName), cLevel);
    }

    /**
     * Asynchronously retrieves a counter column from a row.
     *
     * @param columnFamily        The column family containing the row
     * @param rowKey              The key of the row
     * @param colName             The name of the column to retrieve
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @param callback            The callback to invoke with the result
     * @throws PelopsException    If an error occurs
     */
    public void getCounterColumnFromRow(String columnFamily, String rowKey, String colName, final ConsistencyLevel cLevel, AsyncMethodCallback<CounterColumn> callback) throws PelopsException {
        getCounterColumnFromRow(columnFamily, fromUTF8(rowKey), fromUTF8(colName), cLevel, callback);
    }
    
    /**
     * Retrieves a counter column from a row.
     *
     * @param columnFamily        The column family containing the row
     * @param rowKey              The key of the row
     * @param colName             The name of the column to retrieve
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @return                    The requested <code>Column</code>
     * @throws NotFoundException  If no value is present
     * @throws PelopsException    If an error occurs
     */
    public CounterColumn getCounterColumnFromRow(String columnFamily, String rowKey, Bytes colName, final ConsistencyLevel cLevel) throws NotFoundException, PelopsException {
        return getCounterColumnFromRow(columnFamily, fromUTF8(rowKey), colName, cLevel);
    }

    /**
     * Asynchronously retrieves a counter column from a row.
     *
     * @param columnFamily        The column family containing the row
     * @param rowKey              The key of the row
     * @param colName             The name of the column to retrieve
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @param callback            The callback to invoke with the result
     * @throws PelopsException    If an error occurs
     */
    public void getCounterColumnFromRow(String columnFamily, String rowKey, Bytes colName, final ConsistencyLevel cLevel, AsyncMethodCallback<CounterColumn> callback) throws PelopsException {
        getCounterColumnFromRow(columnFamily, fromUTF8(rowKey), colName, cLevel, callback);
    }
    
    /**
     * Retrieve a counter column from a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param colName                       The name of the column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The requested <code>Column</code>
     * @throws NotFoundException            If no value is present
     * @throws PelopsException if an error occurs
     */
    public CounterColumn getCounterColumnFromRow(String columnFamily, Bytes rowKey, Bytes colName, ConsistencyLevel cLevel) throws NotFoundException, PelopsException {
        return getColumnOrSuperColumnFromRow(columnFamily, rowKey, null, colName, cLevel).counter_column;
    }
    
    /**
     * Asynchronously retrieve a counter column from a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param colName                       The name of the column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getCounterColumnFromRow(String columnFamily, Bytes rowKey, Bytes colName, ConsistencyLevel cLevel, AsyncMethodCallback<CounterColumn> callback) throws PelopsException {
        AsyncMethodCallback<ColumnOrSuperColumn> columnOrSuperColumnCallback = new AbstractConvertingCallback<ColumnOrSuperColumn, CounterColumn>(callback) {

            @Override
            protected CounterColumn convert(ColumnOrSuperColumn columnOrSuperColumn) throws Exception {
                return columnOrSuperColumn.counter_column;
            }
        };
        getColumnOrSuperColumnFromRow(columnFamily, rowKey, null, colName, cLevel, columnOrSuperColumnCallback);
    }

    /**
     * Retrieves the value of a counter column from a row.
     *
     * @param columnFamily        The column family containing the row
     * @param rowKey              The key of the row
     * @param colName             The name of the column to retrieve
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @return                    The value of the requested counter column
     * @throws NotFoundException  If no value is present
     * @throws PelopsException    If an error occurs
     */
    public long getCounterColumnValueFromRow(String columnFamily, String rowKey, String colName, final ConsistencyLevel cLevel) throws NotFoundException, PelopsException {
        return getCounterColumnValueFromRow(columnFamily, fromUTF8(rowKey), fromUTF8(colName), cLevel);
    }

    /**
     * Asynchronously retrieves the value of a counter column from a row.
     *
     * @param columnFamily        The column family containing the row
     * @param rowKey              The key of the row
     * @param colName             The name of the column to retrieve
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @param callback            The callback to invoke with the result
     * @throws PelopsException    If an error occurs
     */
    public void getCounterColumnValueFromRow(String columnFamily, String rowKey, String colName, final ConsistencyLevel cLevel, AsyncMethodCallback<Long> callback) throws PelopsException {
        getCounterColumnValueFromRow(columnFamily, fromUTF8(rowKey), fromUTF8(colName), cLevel, callback);
    }
    
    /**
     * Retrieves the value of a counter column from a row.
     *
     * @param columnFamily        The column family containing the row
     * @param rowKey              The key of the row
     * @param colName             The name of the column to retrieve
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @return                    The value of the requested counter column
     * @throws NotFoundException  If no value is present
     * @throws PelopsException    If an error occurs
     */
    public long getCounterColumnValueFromRow(String columnFamily, String rowKey, Bytes colName, final ConsistencyLevel cLevel) throws NotFoundException, PelopsException {
        return getCounterColumnValueFromRow(columnFamily, fromUTF8(rowKey), colName, cLevel);
    }
    
    /**
     * Asynchronously retrieves the value of a counter column from a row.
     *
     * @param columnFamily        The column family containing the row
     * @param rowKey              The key of the row
     * @param colName             The name of the column to retrieve
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @param callback            The callback to invoke with the result
     * @throws PelopsException    If an error occurs
     */
    public void getCounterColumnValueFromRow(String columnFamily, String rowKey, Bytes colName, final ConsistencyLevel cLevel, AsyncMethodCallback<Long> callback) throws NotFoundException, PelopsException {
        getCounterColumnValueFromRow(columnFamily, fromUTF8(rowKey), colName, cLevel, callback);
    }

    /**
     * Retrieves the value of a counter column from a row.
     *
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param colName                       The name of the column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The value of the requested counter column
     * @throws NotFoundException            If no value is present
     * @throws PelopsException if an error occurs
     */
    public long getCounterColumnValueFromRow(String columnFamily, final Bytes rowKey, Bytes colName, final ConsistencyLevel cLevel) throws NotFoundException, PelopsException {
        return getCounterColumnFromRow(columnFamily, rowKey, colName, cLevel).getValue();
    }
    
    /**
     * Asynchronously retrieves the value of a counter column from a row.
     *
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param colName                       The name of the column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getCounterColumnValueFromRow(String columnFamily, final Bytes rowKey, Bytes colName, final ConsistencyLevel cLevel, AsyncMethodCallback<Long> callback) throws PelopsException {
        AsyncMethodCallback<CounterColumn> counterColumnCallback = new AbstractConvertingCallback<CounterColumn, Long>(callback) {

            @Override
            protected Long convert(CounterColumn counterColumn) throws Exception {
                return counterColumn.getValue();
            }
        };
        getCounterColumnFromRow(columnFamily, rowKey, colName, cLevel, counterColumnCallback);
    }

    /**
     * Retrieve a super column from a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The requested <code>SuperColumn</code>
     * @throws NotFoundException            If no value is present
     * @throws PelopsException if an error occurs
     */
    public SuperColumn getSuperColumnFromRow(String columnFamily, String rowKey, String superColName, ConsistencyLevel cLevel) throws NotFoundException, PelopsException {
        return getSuperColumnFromRow(columnFamily, rowKey, fromUTF8(superColName), cLevel);
    }
    
    /**
     * Asynchronously retrieve a super column from a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSuperColumnFromRow(String columnFamily, String rowKey, String superColName, ConsistencyLevel cLevel, AsyncMethodCallback<SuperColumn> callback) throws PelopsException {
        getSuperColumnFromRow(columnFamily, rowKey, fromUTF8(superColName), cLevel, callback);
    }

    /**
     * Retrieve a super column from a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The requested <code>SuperColumn</code>
     * @throws NotFoundException            If no value is present
     * @throws PelopsException if an error occurs
     */
    public SuperColumn getSuperColumnFromRow(String columnFamily, String rowKey, Bytes superColName, ConsistencyLevel cLevel) throws NotFoundException, PelopsException {
        return getSuperColumnFromRow(columnFamily, fromUTF8(rowKey), superColName, cLevel);
    }
    
    /**
     * Asynchronously retrieve a super column from a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSuperColumnFromRow(String columnFamily, String rowKey, Bytes superColName, ConsistencyLevel cLevel, AsyncMethodCallback<SuperColumn> callback) throws PelopsException {
        getSuperColumnFromRow(columnFamily, fromUTF8(rowKey), superColName, cLevel, callback);
    }

    /**
     * Retrieve a super column from a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The requested <code>SuperColumn</code>
     * @throws NotFoundException            If no value is present
     * @throws PelopsException if an error occurs
     */
    public SuperColumn getSuperColumnFromRow(String columnFamily, Bytes rowKey, Bytes superColName, ConsistencyLevel cLevel) throws NotFoundException, PelopsException {
        return getColumnOrSuperColumnFromRow(columnFamily, rowKey, superColName, null, cLevel).super_column;
    }

    /**
     * Asynchronously retrieve a super column from a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSuperColumnFromRow(String columnFamily, Bytes rowKey, Bytes superColName, ConsistencyLevel cLevel, AsyncMethodCallback<SuperColumn> callback) throws PelopsException {
        AsyncMethodCallback<ColumnOrSuperColumn> columnOrSuperColumnCallback = new AbstractConvertingCallback<ColumnOrSuperColumn, SuperColumn>(callback) {

            @Override
            protected SuperColumn convert(ColumnOrSuperColumn columnOrSuperColumn) throws Exception {
                return columnOrSuperColumn.super_column;
            }
        };
        getColumnOrSuperColumnFromRow(columnFamily, rowKey, superColName, null, cLevel, columnOrSuperColumnCallback);
    }
    
    /**
     * Retrieve a sub column from a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column containing the sub column
     * @param subColName                    The name of the sub column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The requested <code>Column</code>
     * @throws NotFoundException            If no value is present
     * @throws PelopsException if an error occurs
     */
    public Column getSubColumnFromRow(String columnFamily, String rowKey, Bytes superColName, String subColName, ConsistencyLevel cLevel) throws NotFoundException, PelopsException {
        return getSubColumnFromRow(columnFamily, fromUTF8(rowKey), superColName, fromUTF8(subColName), cLevel);
    }
    
    /**
     * Asynchronously retrieve a sub column from a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column containing the sub column
     * @param subColName                    The name of the sub column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnFromRow(String columnFamily, String rowKey, Bytes superColName, String subColName, ConsistencyLevel cLevel, AsyncMethodCallback<Column> callback) throws PelopsException {
        getSubColumnFromRow(columnFamily, fromUTF8(rowKey), superColName, fromUTF8(subColName), cLevel, callback);
    }

    /**
     * Retrieve a sub column from a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column containing the sub column
     * @param subColName                    The name of the sub column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The requested <code>Column</code>
     * @throws NotFoundException            If no value is present
     * @throws PelopsException if an error occurs
     */
    public Column getSubColumnFromRow(String columnFamily, String rowKey, String superColName, String subColName, ConsistencyLevel cLevel) throws NotFoundException, PelopsException {
        return getSubColumnFromRow(columnFamily, fromUTF8(rowKey), fromUTF8(superColName), fromUTF8(subColName), cLevel);
    }

    /**
     * Asynchronously retrieve a sub column from a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column containing the sub column
     * @param subColName                    The name of the sub column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnFromRow(String columnFamily, String rowKey, String superColName, String subColName, ConsistencyLevel cLevel, AsyncMethodCallback<Column> callback) throws PelopsException {
        getSubColumnFromRow(columnFamily, fromUTF8(rowKey), fromUTF8(superColName), fromUTF8(subColName), cLevel, callback);
    }
    
    /**
     * Retrieve a sub column from a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column containing the sub column
     * @param subColName                    The name of the sub column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The requested <code>Column</code>
     * @throws NotFoundException            If no value is present
     * @throws PelopsException if an error occurs
     */
    public Column getSubColumnFromRow(String columnFamily, String rowKey, String superColName, Bytes subColName, ConsistencyLevel cLevel) throws NotFoundException, PelopsException {
        return getSubColumnFromRow(columnFamily, fromUTF8(rowKey), fromUTF8(superColName), subColName, cLevel);
    }
    
    /**
     * Asynchronously retrieve a sub column from a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column containing the sub column
     * @param subColName                    The name of the sub column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnFromRow(String columnFamily, String rowKey, String superColName, Bytes subColName, ConsistencyLevel cLevel, AsyncMethodCallback<Column> callback) throws PelopsException {
        getSubColumnFromRow(columnFamily, fromUTF8(rowKey), fromUTF8(superColName), subColName, cLevel, callback);
    }

    /**
     * Retrieve a sub column from a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column containing the sub column
     * @param subColName                    The name of the sub column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The requested <code>Column</code>
     * @throws NotFoundException            If no value is present
     * @throws PelopsException if an error occurs
     */
    public Column getSubColumnFromRow(String columnFamily, String rowKey, Bytes superColName, Bytes subColName, ConsistencyLevel cLevel) throws NotFoundException, PelopsException {
        return getSubColumnFromRow(columnFamily, fromUTF8(rowKey), superColName, subColName, cLevel);
    }
    
    /**
     * Asynchronously retrieve a sub column from a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column containing the sub column
     * @param subColName                    The name of the sub column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnFromRow(String columnFamily, String rowKey, Bytes superColName, Bytes subColName, ConsistencyLevel cLevel, AsyncMethodCallback<Column> callback) throws PelopsException {
        getSubColumnFromRow(columnFamily, fromUTF8(rowKey), superColName, subColName, cLevel, callback);
    }

    /**
     * Retrieve a sub column from a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column containing the sub column
     * @param subColName                    The name of the sub column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              The requested <code>Column</code>
     * @throws NotFoundException            If no value is present
     * @throws PelopsException if an error occurs
     */
    public Column getSubColumnFromRow(String columnFamily, Bytes rowKey, Bytes superColName, Bytes subColName, ConsistencyLevel cLevel) throws NotFoundException, PelopsException {
        return getColumnOrSuperColumnFromRow(columnFamily, rowKey, superColName, subColName, cLevel).column;
    }
    
    /**
     * Asynchronously retrieve a sub column from a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param superColName                  The name of the super column containing the sub column
     * @param subColName                    The name of the sub column to retrieve
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnFromRow(String columnFamily, Bytes rowKey, Bytes superColName, Bytes subColName, ConsistencyLevel cLevel, AsyncMethodCallback<Column> callback) throws PelopsException {
        AsyncMethodCallback<ColumnOrSuperColumn> columnOrSuperColumnCallback = new AbstractConvertingCallback<ColumnOrSuperColumn, Column>(callback) {

            @Override
            protected Column convert(ColumnOrSuperColumn columnOrSuperColumn)
                    throws Exception {
                return columnOrSuperColumn.column;
            }
        };
        getColumnOrSuperColumnFromRow(columnFamily, rowKey, superColName, subColName, cLevel, columnOrSuperColumnCallback);
    }

    /**
     * Retrieve all columns from a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param reversed                      Whether the results should be returned in descending column name order
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A list of matching columns
     * @throws PelopsException if an error occurs
     */
    public List<Column> getColumnsFromRow(String columnFamily, String rowKey, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRow(newColumnParent(columnFamily), rowKey, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all columns from a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param reversed                      Whether the results should be returned in descending column name order
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnsFromRow(String columnFamily, String rowKey, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<List<Column>> callback) throws PelopsException {
        getColumnsFromRow(newColumnParent(columnFamily), rowKey, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieve columns from a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param colPredicate                  The column selector predicate
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A list of matching columns
     * @throws PelopsException if an error occurs
     */
    public List<Column> getColumnsFromRow(String columnFamily, String rowKey, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRow(newColumnParent(columnFamily), rowKey, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieve columns from a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param colPredicate                  The column selector predicate
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnsFromRow(String columnFamily, String rowKey, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<List<Column>> callback) throws PelopsException {
        getColumnsFromRow(newColumnParent(columnFamily), rowKey, colPredicate, cLevel, callback);
    }

    /**
     * Retrieve all columns from a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param reversed                      Whether the results should be returned in descending column name order
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A list of matching columns
     * @throws PelopsException if an error occurs
     */
    public List<Column> getColumnsFromRow(String columnFamily, Bytes rowKey, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRow(newColumnParent(columnFamily), rowKey, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all columns from a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param reversed                      Whether the results should be returned in descending column name order
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnsFromRow(String columnFamily, Bytes rowKey, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<List<Column>> callback) throws PelopsException {
        getColumnsFromRow(newColumnParent(columnFamily), rowKey, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieve columns from a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param colPredicate                  The column selector predicate
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A list of matching columns
     * @throws PelopsException if an error occurs
     */
    public List<Column> getColumnsFromRow(String columnFamily, Bytes rowKey, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRow(newColumnParent(columnFamily), rowKey, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieve columns from a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param colPredicate                  The column selector predicate
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnsFromRow(String columnFamily, Bytes rowKey, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<List<Column>> callback) throws PelopsException {
        getColumnsFromRow(newColumnParent(columnFamily), rowKey, colPredicate, cLevel, callback);
    }

    /**
     * Retrieve all sub-columns from a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super column
     * @param superColName                  The name of the super column
     * @param reversed                      Whether the results should be returned in descending sub-column name order
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A list of matching columns
     * @throws PelopsException if an error occurs
     */
    public List<Column> getSubColumnsFromRow(String columnFamily, Bytes rowKey, Bytes superColName, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRow(newColumnParent(columnFamily, superColName), rowKey, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all sub-columns from a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super column
     * @param superColName                  The name of the super column
     * @param reversed                      Whether the results should be returned in descending sub-column name order
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRow(String columnFamily, Bytes rowKey, Bytes superColName, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<List<Column>> callback) throws PelopsException {
        getColumnsFromRow(newColumnParent(columnFamily, superColName), rowKey, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieve sub-columns from a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super column
     * @param superColName                  The name of the super column
     * @param colPredicate                  The sub-column selector predicate
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A list of matching columns
     * @throws PelopsException if an error occurs
     */
    public List<Column> getSubColumnsFromRow(String columnFamily, Bytes rowKey, Bytes superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRow(newColumnParent(columnFamily, superColName), rowKey, colPredicate, cLevel);
    }

    /**
     * Asynchronously retrieve sub-columns from a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super column
     * @param superColName                  The name of the super column
     * @param colPredicate                  The sub-column selector predicate
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRow(String columnFamily, Bytes rowKey, Bytes superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<List<Column>> callback) throws PelopsException {
        getColumnsFromRow(newColumnParent(columnFamily, superColName), rowKey, colPredicate, cLevel, callback);
    }
    
    /**
     * Retrieve all sub-columns from a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super column
     * @param superColName                  The name of the super column
     * @param reversed                      Whether the results should be returned in descending sub-column name order
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A list of matching columns
     * @throws PelopsException if an error occurs
     */
    public List<Column> getSubColumnsFromRow(String columnFamily, String rowKey, Bytes superColName, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRow(newColumnParent(columnFamily, superColName), rowKey, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all sub-columns from a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super column
     * @param superColName                  The name of the super column
     * @param reversed                      Whether the results should be returned in descending sub-column name order
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRow(String columnFamily, String rowKey, Bytes superColName, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<List<Column>> callback) throws PelopsException {
        getColumnsFromRow(newColumnParent(columnFamily, superColName), rowKey, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieve sub-columns from a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super column
     * @param superColName                  The name of the super column
     * @param colPredicate                  The sub-column selector predicate
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A list of matching columns
     * @throws PelopsException if an error occurs
     */
    public List<Column> getSubColumnsFromRow(String columnFamily, String rowKey, Bytes superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRow(newColumnParent(columnFamily, superColName), rowKey, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieve sub-columns from a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super column
     * @param superColName                  The name of the super column
     * @param colPredicate                  The sub-column selector predicate
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRow(String columnFamily, String rowKey, Bytes superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<List<Column>> callback) throws PelopsException {
        getColumnsFromRow(newColumnParent(columnFamily, superColName), rowKey, colPredicate, cLevel, callback);
    }

    /**
     * Retrieve all sub-columns from a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super column
     * @param superColName                  The name of the super column
     * @param reversed                      Whether the results should be returned in descending sub-column name order
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A list of matching columns
     * @throws PelopsException if an error occurs
     */
    public List<Column> getSubColumnsFromRow(String columnFamily, String rowKey, String superColName, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRow(newColumnParent(columnFamily, superColName), rowKey, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all sub-columns from a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super column
     * @param superColName                  The name of the super column
     * @param reversed                      Whether the results should be returned in descending sub-column name order
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRow(String columnFamily, String rowKey, String superColName, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<List<Column>> callback) throws PelopsException {
        getColumnsFromRow(newColumnParent(columnFamily, superColName), rowKey, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieve sub-columns from a super column in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super column
     * @param superColName                  The name of the super column
     * @param colPredicate                  The sub-column selector predicate
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A list of matching columns
     * @throws PelopsException if an error occurs
     */
    public List<Column> getSubColumnsFromRow(String columnFamily, String rowKey, String superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRow(newColumnParent(columnFamily, superColName), rowKey, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieve sub-columns from a super column in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super column
     * @param superColName                  The name of the super column
     * @param colPredicate                  The sub-column selector predicate
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRow(String columnFamily, String rowKey, String superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<List<Column>> callback) throws PelopsException {
        getColumnsFromRow(newColumnParent(columnFamily, superColName), rowKey, colPredicate, cLevel, callback);
    }

    private List<Column> getColumnsFromRow(ColumnParent colParent, String rowKey, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRow(colParent, fromUTF8(rowKey), colPredicate, cLevel);
    }

    private void getColumnsFromRow(ColumnParent colParent, String rowKey, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<List<Column>> callback) throws PelopsException {
        getColumnsFromRow(colParent, fromUTF8(rowKey), colPredicate, cLevel, callback);
    }
    
    private void getColumnOrSuperColumnsFromRow(final ColumnParent colParent, final Bytes rowKey, final SlicePredicate colPredicate, final ConsistencyLevel cLevel, final AsyncMethodCallback<List<ColumnOrSuperColumn>> callback) throws PelopsException {
        final AsyncMethodCallback<get_slice_call> getSliceCallback = new AbstractConvertingCallback<get_slice_call, List<ColumnOrSuperColumn>>(callback) {

            @Override
            protected List<ColumnOrSuperColumn> convert(get_slice_call getSliceCall) throws Exception {
                return getSliceCall.getResult();
            }
        };
        tryOperation(new IOperation<Void>() {
            @Override
            public Void execute(IPooledConnection conn) throws Exception {
                conn.getAPI().get_slice(safeGetRowKey(rowKey), colParent, colPredicate, cLevel, getSliceCallback);
                return null;
            }
        });
    }

    private List<Column> getColumnsFromRow(ColumnParent colParent, Bytes rowKey, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<List<Column>> callback = new BlockingCallback<List<Column>>();
        getColumnsFromRow(colParent, rowKey, colPredicate, cLevel, callback);
        return getResult(callback);
    }

    private <R> R getResult(BlockingCallback<R> callback) {
        try {
            return callback.getResult();
        }
        catch (RuntimeException e) {
            throw e;
        }
        catch (InvalidRequestException e) {
            throw new org.scale7.cassandra.pelops.exceptions.InvalidRequestException(e);
        }
        catch (Exception e) {
            throw new PelopsException(e);
        }
    }
    
    private void getColumnsFromRow(ColumnParent colParent, Bytes rowKey, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<List<Column>> callback) throws PelopsException {
        AsyncMethodCallback<List<ColumnOrSuperColumn>> innerCallback = new AbstractConvertingCallback<List<ColumnOrSuperColumn>, List<Column>>(callback) {

            @Override
            protected List<Column> convert(List<ColumnOrSuperColumn> fromData) throws Exception {
                return transform(fromData, COLUMN);
            }
        };
        getColumnOrSuperColumnsFromRow(colParent, rowKey, colPredicate, cLevel, innerCallback);
    }

    /**
     * Retrieves all counter columns from a row.
     *
     * @param columnFamily        The column family containing the row
     * @param rowKey              The key of the row
     * @param reversed            Whether the results should be returned in descending column name order
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @return                    A list of matching columns
     * @throws PelopsException    If an error occurs
     */
    public List<CounterColumn> getCounterColumnsFromRow(String columnFamily, String rowKey, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getCounterColumnsFromRow(columnFamily, fromUTF8(rowKey), reversed, cLevel);
    }
    
    /**
     * Asynchronously retrieves all counter columns from a row.
     *
     * @param columnFamily        The column family containing the row
     * @param rowKey              The key of the row
     * @param reversed            Whether the results should be returned in descending column name order
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @param callback            The callback to invoke with the result
     * @throws PelopsException    If an error occurs
     */
    public void getCounterColumnsFromRow(String columnFamily, String rowKey, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<List<CounterColumn>> callback) throws PelopsException {
        getCounterColumnsFromRow(columnFamily, fromUTF8(rowKey), reversed, cLevel, callback);
    }

    /**
     * Retrieve all counter columns from a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param reversed                      Whether the results should be returned in descending column name order
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A list of matching columns
     * @throws PelopsException if an error occurs
     */
    public List<CounterColumn> getCounterColumnsFromRow(String columnFamily, Bytes rowKey, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getCounterColumnsFromRow(newColumnParent(columnFamily), rowKey, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all counter columns from a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param reversed                      Whether the results should be returned in descending column name order
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getCounterColumnsFromRow(String columnFamily, Bytes rowKey, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<List<CounterColumn>> callback) throws PelopsException {
        getCounterColumnsFromRow(newColumnParent(columnFamily), rowKey, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieves counter columns from a row.
     *
     * @param columnFamily        The column family containing the row
     * @param rowKey              The key of the row
     * @param colPredicate        The column selector predicate
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @return                    A list of matching columns
     * @throws PelopsException    If an error occurs
     */
    public List<CounterColumn> getCounterColumnsFromRow(String columnFamily, String rowKey, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getCounterColumnsFromRow(columnFamily, fromUTF8(rowKey), colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieves counter columns from a row.
     *
     * @param columnFamily        The column family containing the row
     * @param rowKey              The key of the row
     * @param colPredicate        The column selector predicate
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @param callback            The callback to invoke with the result
     * @throws PelopsException    If an error occurs
     */
    public void getCounterColumnsFromRow(String columnFamily, String rowKey, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<List<CounterColumn>> callback) throws PelopsException {
        getCounterColumnsFromRow(columnFamily, fromUTF8(rowKey), colPredicate, cLevel, callback);
    }

    /**
     * Retrieve counter columns from a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param colPredicate                  The column selector predicate
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A list of matching columns
     * @throws PelopsException if an error occurs
     */
    public List<CounterColumn> getCounterColumnsFromRow(String columnFamily, Bytes rowKey, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getCounterColumnsFromRow(newColumnParent(columnFamily), rowKey, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieve counter columns from a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row
     * @param colPredicate                  The column selector predicate
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getCounterColumnsFromRow(String columnFamily, Bytes rowKey, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<List<CounterColumn>> callback) throws PelopsException {
        getCounterColumnsFromRow(newColumnParent(columnFamily), rowKey, colPredicate, cLevel, callback);
    }

    private List<CounterColumn> getCounterColumnsFromRow(ColumnParent colParent, Bytes rowKey, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<List<CounterColumn>> callback = new BlockingCallback<List<CounterColumn>>();
        getCounterColumnsFromRow(colParent, rowKey, colPredicate, cLevel, callback);
        return getResult(callback);
    }
    
    private void getCounterColumnsFromRow(ColumnParent colParent, Bytes rowKey, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<List<CounterColumn>> callback) throws PelopsException {
        AsyncMethodCallback<List<ColumnOrSuperColumn>> innerCallback = new AbstractConvertingCallback<List<ColumnOrSuperColumn>, List<CounterColumn>>(callback) {

            @Override
            protected List<CounterColumn> convert(List<ColumnOrSuperColumn> fromData) throws Exception {
                return transform(fromData, COUNTER_COLUMN);
            }
        };
        getColumnOrSuperColumnsFromRow(colParent, rowKey, colPredicate, cLevel, innerCallback);
    }

    /**
     * Retrieve all super columns from a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super columns
     * @param reversed                      Whether the results should be returned in descending super column name order
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A list of matching columns
     * @throws PelopsException if an error occurs
     */
    public List<SuperColumn> getSuperColumnsFromRow(String columnFamily, String rowKey, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getSuperColumnsFromRow(columnFamily, fromUTF8(rowKey), columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all super columns from a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super columns
     * @param reversed                      Whether the results should be returned in descending super column name order
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSuperColumnsFromRow(String columnFamily, String rowKey, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<List<SuperColumn>> callback) throws PelopsException {
        getSuperColumnsFromRow(columnFamily, fromUTF8(rowKey), columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieve super columns from a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super columns
     * @param colPredicate                  The super column selector predicate
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A list of matching columns
     * @throws PelopsException if an error occurs
     */
    public List<SuperColumn> getSuperColumnsFromRow(String columnFamily, String rowKey, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getSuperColumnsFromRow(columnFamily, fromUTF8(rowKey), colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieve super columns from a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super columns
     * @param colPredicate                  The super column selector predicate
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSuperColumnsFromRow(String columnFamily, String rowKey, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<List<SuperColumn>> callback) throws PelopsException {
        getSuperColumnsFromRow(columnFamily, fromUTF8(rowKey), colPredicate, cLevel, callback);
    }

    /**
     * Retrieve all super columns from a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super columns
     * @param reversed                      Whether the results should be returned in descending super column name order
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A list of matching columns
     * @throws PelopsException if an error occurs
     */
    public List<SuperColumn> getSuperColumnsFromRow(String columnFamily, Bytes rowKey, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getSuperColumnsFromRow(columnFamily, rowKey, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all super columns from a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super columns
     * @param reversed                      Whether the results should be returned in descending super column name order
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSuperColumnsFromRow(String columnFamily, Bytes rowKey, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<List<SuperColumn>> callback) throws PelopsException {
        getSuperColumnsFromRow(columnFamily, rowKey, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieve super columns from a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super columns
     * @param colPredicate                  The super column selector predicate
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A list of matching columns
     * @throws PelopsException if an error occurs
     */
    public List<SuperColumn> getSuperColumnsFromRow(String columnFamily, Bytes rowKey, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<List<SuperColumn>> callback = new BlockingCallback<List<SuperColumn>>();
        getSuperColumnsFromRow(columnFamily, rowKey, colPredicate, cLevel, callback);
        return getResult(callback);
    }

    /**
     * Asynchronously retrieve super columns from a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the super columns
     * @param colPredicate                  The super column selector predicate
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSuperColumnsFromRow(String columnFamily, Bytes rowKey, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<List<SuperColumn>> callback) throws PelopsException {
        AsyncMethodCallback<List<ColumnOrSuperColumn>> innerCallback = new AbstractConvertingCallback<List<ColumnOrSuperColumn>, List<SuperColumn>>(callback) {

            @Override
            protected List<SuperColumn> convert(List<ColumnOrSuperColumn> fromData) throws Exception {
                return transform(fromData, SUPER_COLUMN);
            }
        };
        getColumnOrSuperColumnsFromRow(newColumnParent(columnFamily), rowKey, colPredicate, cLevel, innerCallback);
    }

    /**
     * Retrieve a page of columns composed from a segment of the sequence of columns in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the columns
     * @param startBeyondName               The sequence of columns must begin with the smallest column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending column name order
     * @param count                         The maximum number of columns that can be retrieved by the scan
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A page of columns
     * @throws PelopsException if an error occurs
     */
    public List<Column> getPageOfColumnsFromRow(String columnFamily, String rowKey, Bytes startBeyondName, boolean reversed, int count, ConsistencyLevel cLevel) throws PelopsException {
        return getPageOfColumnsFromRow(columnFamily, fromUTF8(rowKey), startBeyondName, reversed, count, cLevel);
    }
    
    /**
     * Asynchronously retrieve a page of columns composed from a segment of the sequence of columns in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the columns
     * @param startBeyondName               The sequence of columns must begin with the smallest column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending column name order
     * @param count                         The maximum number of columns that can be retrieved by the scan
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getPageOfColumnsFromRow(String columnFamily, String rowKey, Bytes startBeyondName, boolean reversed, int count, ConsistencyLevel cLevel, AsyncMethodCallback<List<Column>> callback) throws PelopsException {
    	getPageOfColumnsFromRow(columnFamily, fromUTF8(rowKey), startBeyondName, reversed, count, cLevel, callback);
    }

    /**
     * Retrieve a page of columns composed from a segment of the sequence of columns in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the columns
     * @param startBeyondName               The sequence of columns must begin with the smallest column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending column name order
     * @param count                         The maximum number of columns that can be retrieved by the scan
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A page of columns
     * @throws PelopsException if an error occurs
     */
    public List<Column> getPageOfColumnsFromRow(String columnFamily, String rowKey, String startBeyondName, boolean reversed, int count, ConsistencyLevel cLevel) throws PelopsException {
        return getPageOfColumnsFromRow(columnFamily, fromUTF8(rowKey), fromUTF8(startBeyondName), reversed, count, cLevel);
    }
    
    /**
     * Asynchronously retrieve a page of columns composed from a segment of the sequence of columns in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the columns
     * @param startBeyondName               The sequence of columns must begin with the smallest column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending column name order
     * @param count                         The maximum number of columns that can be retrieved by the scan
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getPageOfColumnsFromRow(String columnFamily, String rowKey, String startBeyondName, boolean reversed, int count, ConsistencyLevel cLevel, AsyncMethodCallback<List<Column>> callback) throws PelopsException {
    	getPageOfColumnsFromRow(columnFamily, fromUTF8(rowKey), fromUTF8(startBeyondName), reversed, count, cLevel, callback);
    }

    /**
     * Retrieve a page of columns composed from a segment of the sequence of columns in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the columns
     * @param startBeyondName               The sequence of columns must begin with the smallest column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending column name order
     * @param count                         The maximum number of columns that can be retrieved by the scan
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A page of columns
     * @throws PelopsException if an error occurs
     */
    public List<Column> getPageOfColumnsFromRow(String columnFamily, Bytes rowKey, String startBeyondName, boolean reversed, int count, ConsistencyLevel cLevel) throws PelopsException {
        return getPageOfColumnsFromRow(columnFamily, rowKey, fromUTF8(startBeyondName), reversed, count, cLevel);
    }
    
    /**
     * Asynchronously retrieve a page of columns composed from a segment of the sequence of columns in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the columns
     * @param startBeyondName               The sequence of columns must begin with the smallest column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending column name order
     * @param count                         The maximum number of columns that can be retrieved by the scan
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getPageOfColumnsFromRow(String columnFamily, Bytes rowKey, String startBeyondName, boolean reversed, int count, ConsistencyLevel cLevel, AsyncMethodCallback<List<Column>> callback) throws PelopsException {
    	getPageOfColumnsFromRow(columnFamily, rowKey, fromUTF8(startBeyondName), reversed, count, cLevel, callback);
    }

    /**
     * Retrieve a page of columns composed from a segment of the sequence of columns in a row.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the columns
     * @param startBeyondName               The sequence of columns must begin with the smallest column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending column name order
     * @param count                         The maximum number of columns that can be retrieved by the scan
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A page of columns
     * @throws PelopsException if an error occurs
     */
    public List<Column> getPageOfColumnsFromRow(String columnFamily, Bytes rowKey, Bytes startBeyondName, boolean reversed, int count, ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<List<Column>> callback = new BlockingCallback<List<Column>>();
        getPageOfColumnsFromRow(columnFamily, rowKey, startBeyondName, reversed, count, cLevel, callback);
        return getResult(callback);
    }
    
    /**
     * Asynchronously retrieve a page of columns composed from a segment of the sequence of columns in a row.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the columns
     * @param startBeyondName               The sequence of columns must begin with the smallest column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending column name order
     * @param count                         The maximum number of columns that can be retrieved by the scan
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getPageOfColumnsFromRow(String columnFamily, Bytes rowKey, final Bytes startBeyondName, boolean reversed, int count, ConsistencyLevel cLevel, AsyncMethodCallback<List<Column>> callback) throws PelopsException {
        SlicePredicate predicate;
        if (Bytes.nullSafeGet(startBeyondName) == null) {
            predicate = Selector.newColumnsPredicateAll(reversed, count);
            getColumnsFromRow(columnFamily, rowKey, predicate, cLevel, callback);
        } else {
            final int incrementedCount = count + 1;  // cassandra will return the start row but the user is expecting a page of results beyond that point
            predicate = Selector.newColumnsPredicate(startBeyondName, Bytes.EMPTY, reversed, incrementedCount);
            AsyncMethodCallback<List<Column>> interimCallback = new AbstractConvertingCallback<List<Column>, List<Column>>(callback) {

                @Override
                protected List<Column> convert(List<Column> columns) throws Exception {
                    if (columns.size() > 0) {
                        Column first = columns.get(0);
                        if (first.name.equals(startBeyondName.getBytes()))
                            return columns.subList(1, columns.size());
                        else if (columns.size() == incrementedCount)
                            return columns.subList(0, columns.size()-1);
                    }
                    return columns;
                }
            };
            getColumnsFromRow(columnFamily, rowKey, predicate, cLevel, interimCallback);
        }
    }
    
    /**
     * Retrieve a page of column names composed from a segment of the sequence of columns in a row.
     * This method is handy for performing <a href="https://github.com/ericflo/twissandra/">Twissandra</a> style
     * one to many lookups.
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the columns
     * @param startBeyondName               The sequence of columns must begin with the smallest column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending column name order
     * @param count                         The maximum number of columns that can be retrieved by the scan
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A page of column names
     * @throws PelopsException if an error occurs
     */
    public List<Bytes> getPageOfColumnNamesFromRow(String columnFamily, Bytes rowKey, Bytes startBeyondName, boolean reversed, int count, ConsistencyLevel cLevel) throws PelopsException {
        List<Column> columns = getPageOfColumnsFromRow(columnFamily, rowKey, startBeyondName, reversed, count, cLevel);
        // transform to a list of column names
        List<Bytes> columnNames = new ArrayList<Bytes>(columns.size());
        for (Column column : columns) {
            columnNames.add(Bytes.fromByteArray(column.getName()));
        }

        return columnNames;
    }
    
    /**
     * Asynchronously retrieve a page of column names composed from a segment of the sequence of columns in a row.
     * This method is handy for performing <a href="https://github.com/ericflo/twissandra/">Twissandra</a> style
     * one to many lookups.
     * 
     * @param columnFamily                  The column family containing the row
     * @param rowKey                        The key of the row containing the columns
     * @param startBeyondName               The sequence of columns must begin with the smallest column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending column name order
     * @param count                         The maximum number of columns that can be retrieved by the scan
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getPageOfColumnNamesFromRow(String columnFamily, Bytes rowKey, Bytes startBeyondName, boolean reversed, int count, ConsistencyLevel cLevel, AsyncMethodCallback<List<Bytes>> callback) throws PelopsException {
        AsyncMethodCallback<List<Column>> getColumnsCallback = new AbstractConvertingCallback<List<Column>, List<Bytes>>(callback) {

            @Override
            protected List<Bytes> convert(List<Column> columns) throws Exception {
                // transform to a list of column names
                List<Bytes> columnNames = new ArrayList<Bytes>(columns.size());
                for (Column column : columns) {
                    columnNames.add(Bytes.fromByteArray(column.getName()));
                }
                return columnNames;
            }
        };
        getPageOfColumnsFromRow(columnFamily, rowKey, startBeyondName, reversed, count, cLevel, getColumnsCallback);
    }

    /**
     * Returns an iterator that can be used to iterate over columns.  The returned iterator delegates to
     * {@link #getPageOfColumnsFromRow(String, String, Bytes, boolean, int, org.apache.cassandra.thrift.ConsistencyLevel)}
     * to fetch batches of columns (based on the batchSize parameter).
     * @param columnFamily                  The name of the column family containing the columns
     * @param rowKey                        The key of the row
     * @param startBeyondName               The sequence of columns must begin with the smallest  column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending column name order
     * @param batchSize                     The maximum number of columns that can be retrieved per invocation to {@link #getPageOfColumnsFromRow(String, String, Bytes, boolean, int, org.apache.cassandra.thrift.ConsistencyLevel)} and dictates the number of columns to be held in memory at any one time
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              An iterator of columns
     * @throws PelopsException if an error occurs
     */
    public Iterator<Column> iterateColumnsFromRow(String columnFamily, Bytes rowKey, Bytes startBeyondName, boolean reversed, int batchSize, ConsistencyLevel cLevel) {
        return new ColumnIterator(this, columnFamily, rowKey, startBeyondName, reversed, batchSize, cLevel);
    }
    
    /**
     * Returns an iterator that can be used to iterate over columns.  The returned iterator delegates to
     * {@link #getPageOfColumnsFromRow(String, String, Bytes, boolean, int, org.apache.cassandra.thrift.ConsistencyLevel)}
     * to fetch batches of columns (based on the batchSize parameter).
     * @param columnFamily                  The name of the column family containing the columns
     * @param rowKey                        The key of the row
     * @param startBeyondName               The sequence of columns must begin with the smallest  column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending column name order
     * @param batchSize                     The maximum number of columns that can be retrieved per invocation to {@link #getPageOfColumnsFromRow(String, String, Bytes, boolean, int, org.apache.cassandra.thrift.ConsistencyLevel)} and dictates the number of columns to be held in memory at any one time
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              An iterator of columns
     * @throws PelopsException if an error occurs
     */
    public Iterator<Column> iterateColumnsFromRow(String columnFamily, String rowKey, String startBeyondName, boolean reversed, int batchSize, ConsistencyLevel cLevel) {
        return iterateColumnsFromRow(columnFamily, Bytes.fromUTF8(rowKey), Bytes.fromUTF8(startBeyondName), reversed, batchSize, cLevel);
    }

    /**
     * Returns an iterator that can be used to iterate over rows.  The returned iterator delegates to
     * {@link #getColumnsFromRows(String, org.apache.cassandra.thrift.KeyRange, boolean, org.apache.cassandra.thrift.ConsistencyLevel)}
     * to fetch batches of rows (based on the batchSize parameter).
     * @param columnFamily                  The name of the column family containing the columns
     * @param batchSize                     The maximum number of columns that can be retrieved per invocation to {@link #getColumnsFromRows(java.lang.String, java.util.List, boolean, org.apache.cassandra.thrift.ConsistencyLevel)} and dictates the number of rows to be held in memory at any one time
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              An iterator of columns
     * @throws PelopsException if an error occurs
     */
    public Iterator<Map.Entry<Bytes, List<Column>>> iterateColumnsFromRows(String columnFamily, int batchSize, final ConsistencyLevel cLevel) {
        return iterateColumnsFromRows(columnFamily, Bytes.EMPTY, batchSize, Selector.newColumnsPredicateAll(false), cLevel);
    }

    /**
     * Returns an iterator that can be used to iterate over rows.  The returned iterator delegates to
     * {@link #getColumnsFromRows(String, org.apache.cassandra.thrift.KeyRange, boolean, org.apache.cassandra.thrift.ConsistencyLevel)}
     * to fetch batches of rows (based on the batchSize parameter).
     * @param columnFamily                  The name of the column family containing the columns
     * @param startBeyondKey                The sequence of row keys must begin with the smallest row key greater than this value. Pass <code>{@link Bytes#EMPTY}</code> to start at the beginning of the sequence.  NOTE: this parameter only really makes sense when using an Order Preserving Partishioner.
     * @param batchSize                     The maximum number of columns that can be retrieved per invocation to {@link #getColumnsFromRows(java.lang.String, java.util.List, boolean, org.apache.cassandra.thrift.ConsistencyLevel)} and dictates the number of rows to be held in memory at any one time
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              An iterator of columns
     * @throws PelopsException if an error occurs
     */
    public Iterator<Map.Entry<Bytes, List<Column>>> iterateColumnsFromRows(String columnFamily, Bytes startBeyondKey, int batchSize, final ConsistencyLevel cLevel) {
        return iterateColumnsFromRows(columnFamily, startBeyondKey, batchSize, Selector.newColumnsPredicateAll(false), cLevel);
    }

    /**
     * Returns an iterator that can be used to iterate over rows.  The returned iterator delegates to
     * {@link #getColumnsFromRows(String, org.apache.cassandra.thrift.KeyRange, boolean, org.apache.cassandra.thrift.ConsistencyLevel)}
     * to fetch batches of rows (based on the batchSize parameter).
     * @param columnFamily                  The name of the column family containing the columns
     * @param startBeyondKey                The sequence of row keys must begin with the smallest row key greater than this value. Pass <code>{@link Bytes#EMPTY}</code> to start at the beginning of the sequence.  NOTE: this parameter only really makes sense when using an Order Preserving Partishioner.
     * @param batchSize                     The maximum number of columns that can be retrieved per invocation to {@link #getColumnsFromRows(java.lang.String, java.util.List, boolean, org.apache.cassandra.thrift.ConsistencyLevel)} and dictates the number of rows to be held in memory at any one time
     * @param colPredicate                  Dictates the columns to include
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              An iterator of columns
     * @throws PelopsException if an error occurs
     */
    public Iterator<Map.Entry<Bytes, List<Column>>> iterateColumnsFromRows(String columnFamily, Bytes startBeyondKey, int batchSize, final SlicePredicate colPredicate, final ConsistencyLevel cLevel) {
        return new ColumnRowIterator(this, columnFamily, startBeyondKey, batchSize, colPredicate, cLevel);
    }

    /**
     * Retrieve a page of super columns composed from a segment of the sequence of super columns in a row.
     * @param columnFamily                  The name of the column family containing the super columns
     * @param rowKey                        The key of the row
     * @param startBeyondName               The sequence of super columns must begin with the smallest super column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending super column name order
     * @param count                         The maximum number of super columns that can be retrieved by the scan
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A page of super columns
     * @throws PelopsException if an error occurs
     */
    public List<SuperColumn> getPageOfSuperColumnsFromRow(String columnFamily, String rowKey, Bytes startBeyondName, boolean reversed, int count, ConsistencyLevel cLevel) throws PelopsException {
    	return getPageOfSuperColumnsFromRow(columnFamily, fromUTF8(rowKey), startBeyondName, reversed, count, cLevel);
    }

    /**
     * Asynchronously retrieve a page of super columns composed from a segment of the sequence of super columns in a row.
     * 
     * @param columnFamily                  The name of the column family containing the super columns
     * @param rowKey                        The key of the row
     * @param startBeyondName               The sequence of super columns must begin with the smallest super column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending super column name order
     * @param count                         The maximum number of super columns that can be retrieved by the scan
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getPageOfSuperColumnsFromRow(String columnFamily, String rowKey, Bytes startBeyondName, boolean reversed, int count, ConsistencyLevel cLevel, AsyncMethodCallback<List<SuperColumn>> callback) throws PelopsException {
        getPageOfSuperColumnsFromRow(columnFamily, fromUTF8(rowKey), startBeyondName, reversed, count, cLevel, callback);
    }
    
    /**
     * Retrieve a page of super columns composed from a segment of the sequence of super columns in a row.
     * @param columnFamily                  The name of the column family containing the super columns
     * @param rowKey                        The key of the row
     * @param startBeyondName               The sequence of super columns must begin with the smallest super column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending super column name order
     * @param count                         The maximum number of super columns that can be retrieved by the scan
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A page of super columns
     * @throws PelopsException if an error occurs
     */
    public List<SuperColumn> getPageOfSuperColumnsFromRow(String columnFamily, String rowKey, String startBeyondName, boolean reversed, int count, ConsistencyLevel cLevel) throws PelopsException {
    	return getPageOfSuperColumnsFromRow(columnFamily, fromUTF8(rowKey), fromUTF8(startBeyondName), reversed, count, cLevel);
    }
    
    /**
     * Asynchronously retrieve a page of super columns composed from a segment of the sequence of super columns in a row.
     * 
     * @param columnFamily                  The name of the column family containing the super columns
     * @param rowKey                        The key of the row
     * @param startBeyondName               The sequence of super columns must begin with the smallest super column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending super column name order
     * @param count                         The maximum number of super columns that can be retrieved by the scan
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getPageOfSuperColumnsFromRow(String columnFamily, String rowKey, String startBeyondName, boolean reversed, int count, ConsistencyLevel cLevel, AsyncMethodCallback<List<SuperColumn>> callback) throws PelopsException {
        getPageOfSuperColumnsFromRow(columnFamily, fromUTF8(rowKey), fromUTF8(startBeyondName), reversed, count, cLevel, callback);
    }
    
    /**
     * Retrieve a page of super columns composed from a segment of the sequence of super columns in a row.
     * @param columnFamily                  The name of the column family containing the super columns
     * @param rowKey                        The key of the row
     * @param startBeyondName               The sequence of super columns must begin with the smallest super column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending super column name order
     * @param count                         The maximum number of super columns that can be retrieved by the scan
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A page of super columns
     * @throws PelopsException if an error occurs
     */
    public List<SuperColumn> getPageOfSuperColumnsFromRow(String columnFamily, Bytes rowKey, String startBeyondName, boolean reversed, int count, ConsistencyLevel cLevel) throws PelopsException {
    	return getPageOfSuperColumnsFromRow(columnFamily, rowKey, fromUTF8(startBeyondName), reversed, count, cLevel);
    }
    
    /**
     * Asynchronously retrieve a page of super columns composed from a segment of the sequence of super columns in a row.
     * 
     * @param columnFamily                  The name of the column family containing the super columns
     * @param rowKey                        The key of the row
     * @param startBeyondName               The sequence of super columns must begin with the smallest super column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending super column name order
     * @param count                         The maximum number of super columns that can be retrieved by the scan
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getPageOfSuperColumnsFromRow(String columnFamily, Bytes rowKey, String startBeyondName, boolean reversed, int count, ConsistencyLevel cLevel, AsyncMethodCallback<List<SuperColumn>> callback) throws PelopsException {
        getPageOfSuperColumnsFromRow(columnFamily, rowKey, fromUTF8(startBeyondName), reversed, count, cLevel, callback);
    }
    
    /**
     * Retrieve a page of super columns composed from a segment of the sequence of super columns in a row.
     * @param columnFamily                  The name of the column family containing the super columns
     * @param rowKey                        The key of the row
     * @param startBeyondName               The sequence of super columns must begin with the smallest super column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending super column name order
     * @param count                         The maximum number of super columns that can be retrieved by the scan
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A page of super columns
     * @throws PelopsException if an error occurs
     */
    public List<SuperColumn> getPageOfSuperColumnsFromRow(String columnFamily, Bytes rowKey, Bytes startBeyondName, boolean reversed, int count, ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<List<SuperColumn>> callback = new BlockingCallback<List<SuperColumn>>();
        getPageOfSuperColumnsFromRow(columnFamily, rowKey, startBeyondName, reversed, count, cLevel, callback);
        return getResult(callback);
    }
    
    /**
     * Asynchronously retrieve a page of super columns composed from a segment of the sequence of super columns in a row.
     * 
     * @param columnFamily                  The name of the column family containing the super columns
     * @param rowKey                        The key of the row
     * @param startBeyondName               The sequence of super columns must begin with the smallest super column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending super column name order
     * @param count                         The maximum number of super columns that can be retrieved by the scan
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getPageOfSuperColumnsFromRow(String columnFamily, Bytes rowKey, final Bytes startBeyondName, boolean reversed, int count, ConsistencyLevel cLevel, AsyncMethodCallback<List<SuperColumn>> callback) throws PelopsException {
        if (Bytes.nullSafeGet(startBeyondName) == null) {
            SlicePredicate predicate = Selector.newColumnsPredicateAll(reversed, count);
            getSuperColumnsFromRow(columnFamily, rowKey, predicate, cLevel, callback);
        } else {
            final int incrementedCount = count + 1;  // cassandra will return the start row but the user is expecting a page of results beyond that point
            SlicePredicate predicate = Selector.newColumnsPredicate(startBeyondName, Bytes.EMPTY, reversed, incrementedCount);
            AsyncMethodCallback<List<SuperColumn>> superColumnCallback = new AbstractConvertingCallback<List<SuperColumn>, List<SuperColumn>>(callback) {

                @Override
                protected List<SuperColumn> convert(List<SuperColumn> columns) throws Exception {
                    if (columns.size() > 0) {
                        SuperColumn first = columns.get(0);
                        if (first.name.equals(startBeyondName.getBytes()))
                            return columns.subList(1, columns.size());
                        else if (columns.size() == incrementedCount)
                            return columns.subList(0, columns.size()-1);
                    }
                    return columns;
                }
            };
            getSuperColumnsFromRow(columnFamily, rowKey, predicate, cLevel, superColumnCallback);
        }
    }

    /**
     * Returns an iterator that can be used to iterate over super columns.  The returned iterator delegates to
     * {@link #getPageOfSuperColumnsFromRow(String, String, Bytes, boolean, int, org.apache.cassandra.thrift.ConsistencyLevel)}
     * to fetch batches of super columns (based on the batchSize parameter).
     * @param columnFamily                  The name of the column family containing the super columns
     * @param rowKey                        The key of the row
     * @param startBeyondName               The sequence of super columns must begin with the smallest super column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending super column name order
     * @param batchSize                     The maximum number of super columns that can be retrieved per invocation to {@link #getPageOfSuperColumnsFromRow(String, String, Bytes, boolean, int, org.apache.cassandra.thrift.ConsistencyLevel)} and dictates the number of super columns to be held in memory at any one time
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A page of super columns
     * @throws PelopsException if an error occurs
     */
    public Iterator<SuperColumn> iterateSuperColumnsFromRow(String columnFamily, Bytes rowKey, Bytes startBeyondName, boolean reversed, int batchSize, ConsistencyLevel cLevel) {
        return new SuperColumnIterator(this, columnFamily, rowKey, startBeyondName, reversed, batchSize, cLevel);
    }

    /**
     * Returns an iterator that can be used to iterate over super columns.  The returned iterator delegates to
     * {@link #getPageOfSuperColumnsFromRow(String, String, Bytes, boolean, int, org.apache.cassandra.thrift.ConsistencyLevel)}
     * to fetch batches of super columns (based on the batchSize parameter).
     * @param columnFamily                  The name of the column family containing the super columns
     * @param rowKey                        The key of the row
     * @param startBeyondName               The sequence of super columns must begin with the smallest super column name greater than this value. Pass <code>null</code> to start at the beginning of the sequence.
     * @param reversed                      Whether the scan should proceed in descending super column name order
     * @param batchSize                     The maximum number of super columns that can be retrieved per invocation to {@link #getPageOfSuperColumnsFromRow(String, String, Bytes, boolean, int, org.apache.cassandra.thrift.ConsistencyLevel)} and dictates the number of super columns to be held in memory at any one time
     * @param cLevel                        The Cassandra consistency level with which to perform the operation
     * @return                              A page of super columns
     * @throws PelopsException if an error occurs
     */
    public Iterator<SuperColumn> iterateSuperColumnsFromRow(String columnFamily, String rowKey, String startBeyondName, boolean reversed, int batchSize, ConsistencyLevel cLevel) {
        return iterateSuperColumnsFromRow(columnFamily, Bytes.fromUTF8(rowKey), Bytes.fromUTF8(startBeyondName), reversed, batchSize, cLevel);
    }

    /**
     * Retrieve all columns from a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * @param columnFamily                  The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the columns
     * @param reversed                       Whether the results should be returned in descending column name order
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @return                               A map from row keys to the matching lists of columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<Column>> getColumnsFromRows(String columnFamily, List<Bytes> rowKeys, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRows(newColumnParent(columnFamily), rowKeys, columnsPredicateAll(reversed), cLevel);
    }

    /**
     * Asynchronously retrieve all columns from a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * 
     * @param columnFamily                  The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the columns
     * @param reversed                       Whether the results should be returned in descending column name order
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @param callback                      The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnsFromRows(String columnFamily, List<Bytes> rowKeys, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<Column>>> callback) throws PelopsException {
        getColumnsFromRows(newColumnParent(columnFamily), rowKeys, columnsPredicateAll(reversed), cLevel, callback);
    }
    
    /**
     * Retrieves all counter columns from a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the counter columns
     * @param reversed            Whether the results should be returned in descending counter column name order
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @return                    A map from row keys to the matching lists of counter columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException    if an error occurs
     */
    public LinkedHashMap<Bytes, List<CounterColumn>> getCounterColumnsFromRows(String columnFamily, List<Bytes> rowKeys, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getCounterColumnsFromRows(newColumnParent(columnFamily), rowKeys, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all counter columns from a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the counter columns
     * @param reversed            Whether the results should be returned in descending counter column name order
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @param callback            The callback to invoke with the result
     * @throws PelopsException    if an error occurs
     */
    public void getCounterColumnsFromRows(String columnFamily, List<Bytes> rowKeys, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<CounterColumn>>> callback) throws PelopsException {
        getCounterColumnsFromRows(newColumnParent(columnFamily), rowKeys, columnsPredicateAll(reversed), cLevel, callback);
    }
    
    /**
     * Retrieve columns from a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the columns
     * @param colPredicate                   The column selector predicate
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @return                               A map from row keys to the matching lists of columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<Column>> getColumnsFromRows(String columnFamily, List<Bytes> rowKeys, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRows(newColumnParent(columnFamily), rowKeys, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieve columns from a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * 
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the columns
     * @param colPredicate                   The column selector predicate
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @param callback                       The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnsFromRows(String columnFamily, List<Bytes> rowKeys, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<Column>>> callback) throws PelopsException {
        getColumnsFromRows(newColumnParent(columnFamily), rowKeys, colPredicate, cLevel, callback);
    }
    
    /**
     * Retrieves counter columns from a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the counter columns
     * @param colPredicate        The counter column selector predicate
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @return                    A map from row keys to the matching lists of counter columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException    if an error occurs
     */
    public LinkedHashMap<Bytes, List<CounterColumn>> getCounterColumnsFromRows(String columnFamily, List<Bytes> rowKeys, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getCounterColumnsFromRows(newColumnParent(columnFamily), rowKeys, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieves counter columns from a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the counter columns
     * @param colPredicate        The counter column selector predicate
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @param callback            The callback to invoke with the result
     * @throws PelopsException    if an error occurs
     */
    public void getCounterColumnsFromRows(String columnFamily, List<Bytes> rowKeys, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<CounterColumn>>> callback) throws PelopsException {
        getCounterColumnsFromRows(newColumnParent(columnFamily), rowKeys, colPredicate, cLevel, callback);
    }
    
    /**
     * Retrieve all columns from a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * @param columnFamily                  The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the columns
     * @param reversed                       Whether the results should be returned in descending column name order
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @return                               A map from row keys to the matching lists of columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<String, List<Column>> getColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily), rowKeys, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all columns from a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * 
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the columns
     * @param reversed                       Whether the results should be returned in descending column name order
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @param callback                       The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<Column>>> callback) throws PelopsException {
        getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily), rowKeys, columnsPredicateAll(reversed), cLevel, callback);
    }
    
    /**
     * Retrieve columns from a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * @param columnFamily                  The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the columns
     * @param colPredicate                   The column selector predicate
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @return                               A map from row keys to the matching lists of columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<String, List<Column>> getColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily), rowKeys, colPredicate, cLevel);
    }

    /**
     * Asynchronously retrieve columns from a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * 
     * @param columnFamily                  The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the columns
     * @param colPredicate                   The column selector predicate
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @param callback                       The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<Column>>> callback) throws PelopsException {
        getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily), rowKeys, colPredicate, cLevel, callback);
    }
    
    /**
     * Retrieves all counter columns from a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the counter columns
     * @param reversed            Whether the results should be returned in descending counter column name order
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @return                    A map from row keys to the matching lists of columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException    If an error occurs
     */
    public LinkedHashMap<String, List<CounterColumn>> getCounterColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getCounterColumnsFromRowsUtf8Keys(newColumnParent(columnFamily), rowKeys, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieves all counter columns from a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the counter columns
     * @param reversed            Whether the results should be returned in descending counter column name order
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @param callback            The callback to invoke with the result
     * @throws PelopsException    If an error occurs
     */
    public void getCounterColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<CounterColumn>>> callback) throws PelopsException {
        getCounterColumnsFromRowsUtf8Keys(newColumnParent(columnFamily), rowKeys, columnsPredicateAll(reversed), cLevel, callback);
    }
    
    /**
     * Retrieve counter columns from a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the counter columns
     * @param colPredicate        The counter column selector predicate
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @return                    A map from row keys to the matching lists of columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException    If an error occurs
     */
    public LinkedHashMap<String, List<CounterColumn>> getCounterColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getCounterColumnsFromRowsUtf8Keys(newColumnParent(columnFamily), rowKeys, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieve counter columns from a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the counter columns
     * @param colPredicate        The counter column selector predicate
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @param callback            The callback to invoke with the result
     * @throws PelopsException    If an error occurs
     */
    public void getCounterColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<CounterColumn>>> callback) throws PelopsException {
        getCounterColumnsFromRowsUtf8Keys(newColumnParent(columnFamily), rowKeys, colPredicate, cLevel, callback);
    }

    /**
     * Retrieve all sub-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param superColName                   The name of the super column
     * @param reversed                       Whether the results should be returned in descending sub-column name order
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @return                               A map (LinkedHashMap) from row keys to the matching lists of sub-columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<Column>> getSubColumnsFromRows(String columnFamily, List<Bytes> rowKeys, String superColName, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRows(newColumnParent(columnFamily, superColName), rowKeys, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all sub-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * 
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param superColName                   The name of the super column
     * @param reversed                       Whether the results should be returned in descending sub-column name order
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @param callback                       The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRows(String columnFamily, List<Bytes> rowKeys, String superColName, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<Column>>> callback) throws PelopsException {
        getColumnsFromRows(newColumnParent(columnFamily, superColName), rowKeys, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieves all sub-counter-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the super columns
     * @param superColName        The name of the super column
     * @param reversed            Whether the results should be returned in descending sub-counter-column name order
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @return                    A map (LinkedHashMap) from row keys to the matching lists of sub-counter-columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException    if an error occurs
     */
    public Map<Bytes, List<CounterColumn>> getSubCounterColumnsFromRows(String columnFamily, List<Bytes> rowKeys, String superColName, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getCounterColumnsFromRows(newColumnParent(columnFamily, superColName), rowKeys, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieves all sub-counter-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the super columns
     * @param superColName        The name of the super column
     * @param reversed            Whether the results should be returned in descending sub-counter-column name order
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @param callback            The callback to invoke with the result
     * @throws PelopsException    if an error occurs
     */
    public void getSubCounterColumnsFromRows(String columnFamily, List<Bytes> rowKeys, String superColName, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<CounterColumn>>> callback) throws PelopsException {
        getCounterColumnsFromRows(newColumnParent(columnFamily, superColName), rowKeys, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieve sub-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param superColName                   The name of the super column
     * @param colPredicate                   The sub-column selector predicate
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @return                               A map from row keys to the matching lists of sub-columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException if an error occurs
     */
    public Map<Bytes, List<Column>> getSubColumnsFromRows(String columnFamily, List<Bytes> rowKeys, String superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRows(newColumnParent(columnFamily, superColName), rowKeys, colPredicate, cLevel);
    }

    /**
     * Asynchronously retrieve sub-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * 
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param superColName                   The name of the super column
     * @param colPredicate                   The sub-column selector predicate
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @param callback                       The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRows(String columnFamily, List<Bytes> rowKeys, String superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<Column>>> callback) throws PelopsException {
        getColumnsFromRows(newColumnParent(columnFamily, superColName), rowKeys, colPredicate, cLevel, callback);
    }
    
    /**
     * Retrieves sub-counter-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the super columns
     * @param superColName        The name of the super column
     * @param colPredicate        The sub-column-column selector predicate
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @return                    A map from row keys to the matching lists of sub-counter-columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException    if an error occurs
     */
    public Map<Bytes, List<CounterColumn>> getSubCounterColumnsFromRows(String columnFamily, List<Bytes> rowKeys, String superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getCounterColumnsFromRows(newColumnParent(columnFamily, superColName), rowKeys, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieves sub-counter-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the super columns
     * @param superColName        The name of the super column
     * @param colPredicate        The sub-column-column selector predicate
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @param callback            The callback to invoke with the result
     * @throws PelopsException    if an error occurs
     */
    public void getSubCounterColumnsFromRows(String columnFamily, List<Bytes> rowKeys, String superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<CounterColumn>>> callback) throws PelopsException {
        getCounterColumnsFromRows(newColumnParent(columnFamily, superColName), rowKeys, colPredicate, cLevel, callback);
    }

    /**
     * Retrieve all sub-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param superColName                   The name of the super column
     * @param reversed                       Whether the results should be returned in descending sub-column name order
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @return                               A map from row keys to the matching lists of sub-columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException if an error occurs
     */
    public Map<String, List<Column>> getSubColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, String superColName, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
    	return getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), rowKeys, columnsPredicateAll(reversed), cLevel);
    }

    /**
     * Asynchronously retrieve all sub-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * 
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param superColName                   The name of the super column
     * @param reversed                       Whether the results should be returned in descending sub-column name order
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @param callback                       The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, String superColName, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<Column>>> callback) throws PelopsException {
        getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), rowKeys, columnsPredicateAll(reversed), cLevel, callback);
    }
    
    /**
     * Retrieves all sub-counter-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the super columns
     * @param superColName        The name of the super column
     * @param reversed            Whether the results should be returned in descending sub-counter-column name order
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @return                    A map from row keys to the matching lists of sub-counter-columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException    if an error occurs
     */
    public LinkedHashMap<String, List<CounterColumn>> getSubCounterColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, String superColName, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getCounterColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), rowKeys, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieves all sub-counter-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the super columns
     * @param superColName        The name of the super column
     * @param reversed            Whether the results should be returned in descending sub-counter-column name order
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @param callback            The callback to invoke with the result
     * @throws PelopsException    if an error occurs
     */
    public void getSubCounterColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, String superColName, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<CounterColumn>>> callback) throws PelopsException {
        getCounterColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), rowKeys, columnsPredicateAll(reversed), cLevel, callback);
    }
    
    /**
     * Retrieve sub-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param superColName                   The name of the super column
     * @param colPredicate                   The sub-column selector predicate
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @return                               A map from row keys to the matching lists of sub-columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<String, List<Column>> getSubColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, String superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), rowKeys, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieve sub-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * 
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param superColName                   The name of the super column
     * @param colPredicate                   The sub-column selector predicate
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @param callback                       The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, String superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<Column>>> callback) throws PelopsException {
    	getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), rowKeys, colPredicate, cLevel, callback);
    }

    /**
     * Retrieves sub-counter-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the super columns
     * @param superColName        The name of the super column
     * @param colPredicate        The sub-counter-column selector predicate
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @return                    A map from row keys to the matching lists of sub-counter-columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException    if an error occurs
     */
    public LinkedHashMap<String, List<CounterColumn>> getSubCounterColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, String superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getCounterColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), rowKeys, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieves sub-counter-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the super columns
     * @param superColName        The name of the super column
     * @param colPredicate        The sub-counter-column selector predicate
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @param callback            The callback to invoke with the result
     * @throws PelopsException    if an error occurs
     */
    public void getSubCounterColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, String superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<CounterColumn>>> callback) throws PelopsException {
        getCounterColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), rowKeys, colPredicate, cLevel, callback);
    }

    /**
     * Retrieve all sub-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param superColName                   The name of the super column
     * @param reversed                       Whether the results should be returned in descending sub-column name order
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @return                               A map from row keys to the matching lists of sub-columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<Column>> getSubColumnsFromRows(String columnFamily, List<Bytes> rowKeys, Bytes superColName, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRows(newColumnParent(columnFamily, superColName), rowKeys, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all sub-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * 
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param superColName                   The name of the super column
     * @param reversed                       Whether the results should be returned in descending sub-column name order
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @param callback                       The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRows(String columnFamily, List<Bytes> rowKeys, Bytes superColName, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<Column>>> callback) throws PelopsException {
        getColumnsFromRows(newColumnParent(columnFamily, superColName), rowKeys, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieves all sub-counter-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the super columns
     * @param superColName        The name of the super column
     * @param reversed            Whether the results should be returned in descending sub-counter-column name order
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @return                    A map from row keys to the matching lists of sub-counter-columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException    if an error occurs
     */
    public LinkedHashMap<Bytes, List<CounterColumn>> getSubCounterColumnsFromRows(String columnFamily, List<Bytes> rowKeys, Bytes superColName, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getCounterColumnsFromRows(newColumnParent(columnFamily, superColName), rowKeys, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieves all sub-counter-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the super columns
     * @param superColName        The name of the super column
     * @param reversed            Whether the results should be returned in descending sub-counter-column name order
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @param callback            The callback to invoke with the result
     * @throws PelopsException    if an error occurs
     */
    public void getSubCounterColumnsFromRows(String columnFamily, List<Bytes> rowKeys, Bytes superColName, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<CounterColumn>>> callback) throws PelopsException {
        getCounterColumnsFromRows(newColumnParent(columnFamily, superColName), rowKeys, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieve sub-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param superColName                   The name of the super column
     * @param colPredicate                   The sub-column selector predicate
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @return                               A map from row keys to the matching lists of sub-columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<Column>> getSubColumnsFromRows(String columnFamily, List<Bytes> rowKeys, Bytes superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRows(newColumnParent(columnFamily, superColName), rowKeys, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieve sub-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * 
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param superColName                   The name of the super column
     * @param colPredicate                   The sub-column selector predicate
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @param callback                       The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRows(String columnFamily, List<Bytes> rowKeys, Bytes superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<Column>>> callback) throws PelopsException {
        getColumnsFromRows(newColumnParent(columnFamily, superColName), rowKeys, colPredicate, cLevel, callback);
    }

    /**
     * Retrieves sub-counter-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the super columns
     * @param superColName        The name of the super column
     * @param colPredicate        The sub-counter-column selector predicate
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @return                    A map from row keys to the matching lists of sub-counter-columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException    if an error occurs
     */
    public LinkedHashMap<Bytes, List<CounterColumn>> getSubCounterColumnsFromRows(String columnFamily, List<Bytes> rowKeys, Bytes superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getCounterColumnsFromRows(newColumnParent(columnFamily, superColName), rowKeys, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieves sub-counter-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the super columns
     * @param superColName        The name of the super column
     * @param colPredicate        The sub-counter-column selector predicate
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @param callback            The callback to invoke with the result
     * @throws PelopsException    if an error occurs
     */
    public void getSubCounterColumnsFromRows(String columnFamily, List<Bytes> rowKeys, Bytes superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<CounterColumn>>> callback) throws PelopsException {
        getCounterColumnsFromRows(newColumnParent(columnFamily, superColName), rowKeys, colPredicate, cLevel, callback);
    }

    /**
     * Retrieve all sub-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param superColName                   The name of the super column
     * @param reversed                       Whether the results should be returned in descending sub-column name order
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @return                               A map from row keys to the matching lists of sub-columns.  If no value corresponding to a key is present, the key will still be in the map.
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<String, List<Column>> getSubColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, Bytes superColName, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), rowKeys, columnsPredicateAll(reversed), cLevel);
    }

    /**
     * Asynchronously retrieve all sub-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * 
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param superColName                   The name of the super column
     * @param reversed                       Whether the results should be returned in descending sub-column name order
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @param callback                       The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, Bytes superColName, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<Column>>> callback) throws PelopsException {
        getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), rowKeys, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieves all sub-counter-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the super columns
     * @param superColName        The name of the super column
     * @param reversed            Whether the results should be returned in descending sub-counter-column name order
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @return                    A map from row keys to the matching lists of sub-counter-columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException    if an error occurs
     */
    public LinkedHashMap<String, List<CounterColumn>> getSubCounterColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, Bytes superColName, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getCounterColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), rowKeys, columnsPredicateAll(reversed), cLevel);
    }

    /**
     * Asynchronously retrieves all sub-counter-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the super columns
     * @param superColName        The name of the super column
     * @param reversed            Whether the results should be returned in descending sub-counter-column name order
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @param callback            The callback to invoke with the result
     * @throws PelopsException    if an error occurs
     */
    public void getSubCounterColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, Bytes superColName, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<CounterColumn>>> callback) throws PelopsException {
        getCounterColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), rowKeys, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieve sub-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param superColName                   The name of the super column
     * @param colPredicate                   The sub-column selector predicate
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @return                               A map from row keys to the matching lists of sub-columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<String, List<Column>> getSubColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, Bytes superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), rowKeys, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieve sub-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     * 
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param superColName                   The name of the super column
     * @param colPredicate                   The sub-column selector predicate
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @param callback                       The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, Bytes superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<Column>>> callback) throws PelopsException {
        getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), rowKeys, colPredicate, cLevel, callback);
    }

    /**
     * Retrieves sub-counter-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the super columns
     * @param superColName        The name of the super column
     * @param colPredicate        The sub-counter-column selector predicate
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @return                    A map from row keys to the matching lists of sub-counter-columns.  If no value corresponding to a key is present, the key will still be in the map but with an empty list as it's value.
     * @throws PelopsException    if an error occurs
     */
    public LinkedHashMap<String, List<CounterColumn>> getSubCounterColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, Bytes superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getCounterColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), rowKeys, colPredicate, cLevel);
    }

    /**
     * Asynchronously Retrieves sub-counter-columns from a super column in a set of rows.
     * Note that the returned map is insertion-order-preserving and populated based on the provided list of rowKeys.
     *
     * @param columnFamily        The column family containing the rows
     * @param rowKeys             The keys of the rows containing the super columns
     * @param superColName        The name of the super column
     * @param colPredicate        The sub-counter-column selector predicate
     * @param cLevel              The Cassandra consistency level with which to perform the operation
     * @param callback            The callback to invoke with the result
     * @throws PelopsException    if an error occurs
     */
    public void getSubCounterColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, Bytes superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<CounterColumn>>> callback) throws PelopsException {
        getCounterColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), rowKeys, colPredicate, cLevel, callback);
    }

    /**
     * Retrieve all super columns from a set of rows.
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param reversed                       Whether the results should be returned in descending super column name order
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @return                               A map from row keys to the matching lists of super columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<SuperColumn>> getSuperColumnsFromRows(String columnFamily, List<Bytes> rowKeys, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getSuperColumnsFromRows(columnFamily, rowKeys, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all super columns from a set of rows.
     * 
     * @param columnFamily                   The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param reversed                       Whether the results should be returned in descending super column name order
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @param callback                       The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSuperColumnsFromRows(String columnFamily, List<Bytes> rowKeys, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<SuperColumn>>> callback) throws PelopsException {
        getSuperColumnsFromRows(columnFamily, rowKeys, columnsPredicateAll(reversed), cLevel, callback);
    }

    public Map<ByteBuffer, List<ColumnOrSuperColumn>> getColumnOrSuperColumnsFromRows(final ColumnParent columnParent, final List<ByteBuffer> rowKeys, final SlicePredicate colPredicate, final ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<Map<ByteBuffer, List<ColumnOrSuperColumn>>> callback = new BlockingCallback<Map<ByteBuffer, List<ColumnOrSuperColumn>>>();
        getColumnOrSuperColumnsFromRows(columnParent, rowKeys, colPredicate, cLevel, callback);
        return getResult(callback);
    }
    
    public void getColumnOrSuperColumnsFromRows(final ColumnParent columnParent, final List<ByteBuffer> rowKeys, final SlicePredicate colPredicate, final ConsistencyLevel cLevel, AsyncMethodCallback<Map<ByteBuffer, List<ColumnOrSuperColumn>>> callback) throws PelopsException {
        final AsyncMethodCallback<multiget_slice_call> multigetSliceCallback = new AbstractConvertingCallback<multiget_slice_call, Map<ByteBuffer, List<ColumnOrSuperColumn>>>(callback) {

            @Override
            protected Map<ByteBuffer, List<ColumnOrSuperColumn>> convert(multiget_slice_call multigetSliceCall) throws Exception {
                return multigetSliceCall.getResult();
            }
        };
        tryOperation(new IOperation<Void>() {
            @Override
            public Void execute(IPooledConnection conn) throws Exception {
                conn.getAPI().multiget_slice(rowKeys, columnParent, colPredicate, cLevel, multigetSliceCallback);
                return null;
            }
        });
    }

    /**
     * Retrieve super columns from a set of rows.
     * @param columnFamily                  The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param colPredicate                   The super column selector predicate
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @return                               A map from row keys to the matching lists of super columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<SuperColumn>> getSuperColumnsFromRows(String columnFamily, List<Bytes> rowKeys, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<LinkedHashMap<Bytes, List<SuperColumn>>> callback = new BlockingCallback<LinkedHashMap<Bytes,List<SuperColumn>>>();
        getSuperColumnsFromRows(columnFamily, rowKeys, colPredicate, cLevel, callback);
        return getResult(callback);
    }
    
    /**
     * Asynchronously retrieve super columns from a set of rows.
     * 
     * @param columnFamily                  The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param colPredicate                   The super column selector predicate
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @param callback                       The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSuperColumnsFromRows(String columnFamily, final List<Bytes> rowKeys, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<SuperColumn>>> callback) throws PelopsException {
        ColumnParent columnParent = newColumnParent(columnFamily);
        List<ByteBuffer> keys = Bytes.transformBytesToList(validateRowKeys(rowKeys));
        AsyncMethodCallback<Map<ByteBuffer, List<ColumnOrSuperColumn>>> innerCallback = new AbstractConvertingCallback<Map<ByteBuffer, List<ColumnOrSuperColumn>>, LinkedHashMap<Bytes,List<SuperColumn>>>(callback) {

            @Override
            protected LinkedHashMap<Bytes, List<SuperColumn>> convert(Map<ByteBuffer, List<ColumnOrSuperColumn>> inputMap) throws Exception {
                return transform(inputMap, rowKeys, SUPER_COLUMN);
            }
        };
        getColumnOrSuperColumnsFromRows(columnParent, keys, colPredicate, cLevel, innerCallback);
    }

    /**
     * Retrieve all super columns from a set of rows.
     * @param columnFamily                  The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param reversed                       Whether the results should be returned in descending super column name order
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @return                               A map from row keys to the matching lists of super columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<String, List<SuperColumn>> getSuperColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getSuperColumnsFromRowsUtf8Keys(columnFamily, rowKeys, columnsPredicateAll(reversed), cLevel);
    }

    /**
     * Asynchronously retrieve all super columns from a set of rows.
     * 
     * @param columnFamily                  The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param reversed                       Whether the results should be returned in descending super column name order
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @param callback                       The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSuperColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<SuperColumn>>> callback) throws PelopsException {
        getSuperColumnsFromRowsUtf8Keys(columnFamily, rowKeys, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieve super columns from a set of rows.
     * @param columnFamily                  The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param colPredicate                   The super column selector predicate
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @return                               A map from row keys to the matching lists of super columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<String, List<SuperColumn>> getSuperColumnsFromRowsUtf8Keys(String columnFamily, List<String> rowKeys, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<LinkedHashMap<String, List<SuperColumn>>> callback = new BlockingCallback<LinkedHashMap<String,List<SuperColumn>>>();
        getSuperColumnsFromRowsUtf8Keys(columnFamily, rowKeys, colPredicate, cLevel, callback);
        return getResult(callback);
    }
    
    /**
     * Asynchronously retrieve super columns from a set of rows.
     * 
     * @param columnFamily                  The column family containing the rows
     * @param rowKeys                        The keys of the rows containing the super columns
     * @param colPredicate                   The super column selector predicate
     * @param cLevel                         The Cassandra consistency level with which to perform the operation
     * @param callback                       The callback to invoke with the result
     * @throws PelopsException if an error occurs
     */
    public void getSuperColumnsFromRowsUtf8Keys(String columnFamily, final List<String> rowKeys, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<SuperColumn>>> callback) throws PelopsException {
        ColumnParent columnParent = newColumnParent(columnFamily);
        final List<ByteBuffer> keys = Bytes.transformUTF8ToList(validateRowKeysUtf8(rowKeys));
        AsyncMethodCallback<Map<ByteBuffer, List<ColumnOrSuperColumn>>> innerCallback = new AbstractConvertingCallback<Map<ByteBuffer,List<ColumnOrSuperColumn>>, LinkedHashMap<String, List<SuperColumn>>>(callback) {

            @Override
            protected LinkedHashMap<String, List<SuperColumn>> convert(Map<ByteBuffer, List<ColumnOrSuperColumn>> fromData) throws Exception {
                return transformUtf8(fromData, rowKeys, keys, SUPER_COLUMN);
            }
        };
        getColumnOrSuperColumnsFromRows(columnParent, keys, colPredicate, cLevel, innerCallback);
    }

    private LinkedHashMap<Bytes, List<Column>> getColumnsFromRows(final ColumnParent colParent, List<Bytes> rowKeys, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<LinkedHashMap<Bytes, List<Column>>> callback = new BlockingCallback<LinkedHashMap<Bytes,List<Column>>>();
        getColumnsFromRows(colParent, rowKeys, colPredicate, cLevel, callback);
        return getResult(callback);
    }

    private void getColumnsFromRows(final ColumnParent colParent, final List<Bytes> rowKeys, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<Column>>> callback) throws PelopsException {
        List<ByteBuffer> keys = Bytes.transformBytesToList(validateRowKeys(rowKeys));
        AsyncMethodCallback<Map<ByteBuffer, List<ColumnOrSuperColumn>>> innerCallback = new AbstractConvertingCallback<Map<ByteBuffer,List<ColumnOrSuperColumn>>, LinkedHashMap<Bytes, List<Column>>>(callback) {

            @Override
            protected LinkedHashMap<Bytes, List<Column>> convert(Map<ByteBuffer, List<ColumnOrSuperColumn>> fromData) throws Exception {
                return transform(fromData, rowKeys, COLUMN);
            }
        };
        getColumnOrSuperColumnsFromRows(colParent, keys, colPredicate, cLevel, innerCallback);
    }

    private LinkedHashMap<Bytes, List<CounterColumn>> getCounterColumnsFromRows(ColumnParent colParent, List<Bytes> rowKeys, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<LinkedHashMap<Bytes, List<CounterColumn>>> callback = new BlockingCallback<LinkedHashMap<Bytes,List<CounterColumn>>>();
        getCounterColumnsFromRows(colParent, rowKeys, colPredicate, cLevel, callback);
        return getResult(callback);
    }
    
    private void getCounterColumnsFromRows(ColumnParent colParent, final List<Bytes> rowKeys, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<CounterColumn>>> callback) throws PelopsException {
        List<ByteBuffer> keys = Bytes.transformBytesToList(validateRowKeys(rowKeys));
        AsyncMethodCallback<Map<ByteBuffer, List<ColumnOrSuperColumn>>> innerCallback = new AbstractConvertingCallback<Map<ByteBuffer,List<ColumnOrSuperColumn>>, LinkedHashMap<Bytes, List<CounterColumn>>>(callback) {

            @Override
            protected LinkedHashMap<Bytes, List<CounterColumn>> convert(Map<ByteBuffer, List<ColumnOrSuperColumn>> fromData) throws Exception {
                return transform(fromData, rowKeys, COUNTER_COLUMN);
            }
        };
        getColumnOrSuperColumnsFromRows(colParent, keys, colPredicate, cLevel, innerCallback);
    }

    private LinkedHashMap<String, List<Column>> getColumnsFromRowsUtf8Keys(ColumnParent colParent, List<String> rowKeys, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<LinkedHashMap<String, List<Column>>> callback = new BlockingCallback<LinkedHashMap<String,List<Column>>>();
        getColumnsFromRowsUtf8Keys(colParent, rowKeys, colPredicate, cLevel, callback);
        return getResult(callback);
    }
    
    private void getColumnsFromRowsUtf8Keys(ColumnParent colParent, final List<String> rowKeys, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<Column>>> callback) throws PelopsException {
        final List<ByteBuffer> keys = Bytes.transformUTF8ToList(validateRowKeysUtf8(rowKeys));
        AsyncMethodCallback<Map<ByteBuffer, List<ColumnOrSuperColumn>>> innerCallback = new AbstractConvertingCallback<Map<ByteBuffer, List<ColumnOrSuperColumn>>, LinkedHashMap<String, List<Column>>>(callback) {

            @Override
            protected LinkedHashMap<String, List<Column>> convert(Map<ByteBuffer, List<ColumnOrSuperColumn>> fromData) throws Exception {
                return transformUtf8(fromData, rowKeys, keys, COLUMN);
            }
        };
        getColumnOrSuperColumnsFromRows(colParent, keys, colPredicate, cLevel, innerCallback);
    }

    private LinkedHashMap<String, List<CounterColumn>> getCounterColumnsFromRowsUtf8Keys(ColumnParent colParent, List<String> rowKeys, SlicePredicate colPredicate,  ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<LinkedHashMap<String, List<CounterColumn>>> callback = new BlockingCallback<LinkedHashMap<String,List<CounterColumn>>>();
        getCounterColumnsFromRowsUtf8Keys(colParent, rowKeys, colPredicate, cLevel, callback);
        return getResult(callback);
    }
    
    private void getCounterColumnsFromRowsUtf8Keys(ColumnParent colParent, final List<String> rowKeys, SlicePredicate colPredicate,  ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<CounterColumn>>> callback) throws PelopsException {
        final List<ByteBuffer> keys = Bytes.transformUTF8ToList(validateRowKeysUtf8(rowKeys));
        AsyncMethodCallback<Map<ByteBuffer, List<ColumnOrSuperColumn>>> innerCallback = new AbstractConvertingCallback<Map<ByteBuffer,List<ColumnOrSuperColumn>>, LinkedHashMap<String, List<CounterColumn>>>(callback) {

            @Override
            protected LinkedHashMap<String, List<CounterColumn>> convert(Map<ByteBuffer, List<ColumnOrSuperColumn>> fromData) throws Exception {
                return transformUtf8(fromData, rowKeys, keys, COUNTER_COLUMN);
            }
        };
        getColumnOrSuperColumnsFromRows(colParent, keys, colPredicate, cLevel, innerCallback);
    }

    /**
     * Retrieve all columns from a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param reversed                        Whether the results should be returned in descending column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<Column>> getColumnsFromRows(String columnFamily, KeyRange keyRange, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRows(newColumnParent(columnFamily), keyRange, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all columns from a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param reversed                        Whether the results should be returned in descending column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnsFromRows(String columnFamily, KeyRange keyRange, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<Column>>> callback) throws PelopsException {
        getColumnsFromRows(newColumnParent(columnFamily), keyRange, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieve columns from a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param colPredicate                    The column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<Column>> getColumnsFromRows(String columnFamily, KeyRange keyRange, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRows(newColumnParent(columnFamily), keyRange, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieve columns from a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param colPredicate                    The column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnsFromRows(String columnFamily, KeyRange keyRange, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<Column>>> callback) throws PelopsException {
        getColumnsFromRows(newColumnParent(columnFamily), keyRange, colPredicate, cLevel, callback);
    }

    /**
     * Retrieve all columns from a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param reversed                        Whether the results should be returned in descending column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<String, List<Column>> getColumnsFromRowsUtf8Keys(String columnFamily, KeyRange keyRange, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily), keyRange, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all columns from a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param reversed                        Whether the results should be returned in descending column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnsFromRowsUtf8Keys(String columnFamily, KeyRange keyRange, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<Column>>> callback) throws PelopsException {
        getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily), keyRange, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieve columns from a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param colPredicate                    The column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<String, List<Column>> getColumnsFromRowsUtf8Keys(String columnFamily, KeyRange keyRange, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily), keyRange, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieve columns from a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param colPredicate                    The column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getColumnsFromRowsUtf8Keys(String columnFamily, KeyRange keyRange, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<Column>>> callback) throws PelopsException {
        getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily), keyRange, colPredicate, cLevel, callback);
    }

    /**
     * Retrieve all sub-columns from a super column in a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of sub-columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param superColName                    The name of the super column
     * @param reversed                        Whether the results should be returned in descending sub-column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of sub-columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<Column>> getSubColumnsFromRows(String columnFamily, KeyRange keyRange, Bytes superColName, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRows(newColumnParent(columnFamily, superColName), keyRange, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all sub-columns from a super column in a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of sub-columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param superColName                    The name of the super column
     * @param reversed                        Whether the results should be returned in descending sub-column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRows(String columnFamily, KeyRange keyRange, Bytes superColName, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<Column>>> callback) throws PelopsException {
        getColumnsFromRows(newColumnParent(columnFamily, superColName), keyRange, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieve sub-columns from a super column in a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of sub-columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param superColName                    The name of the super column
     * @param colPredicate                    The sub-column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of sub-columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<Column>> getSubColumnsFromRows(String columnFamily, KeyRange keyRange, Bytes superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRows(newColumnParent(columnFamily, superColName), keyRange, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieve sub-columns from a super column in a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of sub-columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param superColName                    The name of the super column
     * @param colPredicate                    The sub-column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRows(String columnFamily, KeyRange keyRange, Bytes superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<Column>>> callback) throws PelopsException {
        getColumnsFromRows(newColumnParent(columnFamily, superColName), keyRange, colPredicate, cLevel, callback);
    }

    /**
     * Retrieve all sub-columns from a super column in a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of sub-columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param superColName                    The name of the super column
     * @param reversed                        Whether the results should be returned in descending sub-column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of sub-columns
     * @throws PelopsException if an error occurs
     */
    public Map<String, List<Column>> getSubColumnsFromRowsUtf8Keys(String columnFamily, KeyRange keyRange, Bytes superColName, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), keyRange, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all sub-columns from a super column in a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of sub-columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param superColName                    The name of the super column
     * @param reversed                        Whether the results should be returned in descending sub-column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRowsUtf8Keys(String columnFamily, KeyRange keyRange, Bytes superColName, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<Column>>> callback) throws PelopsException {
        getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), keyRange, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieve sub-columns from a super column in a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of sub-columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param superColName                    The name of the super column
     * @param colPredicate                    The sub-column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of sub-columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<String, List<Column>> getSubColumnsFromRowsUtf8Keys(String columnFamily, KeyRange keyRange, Bytes superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), keyRange, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieve sub-columns from a super column in a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of sub-columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param superColName                    The name of the super column
     * @param colPredicate                    The sub-column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRowsUtf8Keys(String columnFamily, KeyRange keyRange, Bytes superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<Column>>> callback) throws PelopsException {
        getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), keyRange, colPredicate, cLevel, callback);
    }

    /**
     * Retrieve all sub-columns from a super column in a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of sub-columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param superColName                    The name of the super column
     * @param reversed                        Whether the results should be returned in descending sub-column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of sub-columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<Column>> getSubColumnsFromRows(String columnFamily, KeyRange keyRange, String superColName, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRows(newColumnParent(columnFamily, superColName), keyRange, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all sub-columns from a super column in a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of sub-columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param superColName                    The name of the super column
     * @param reversed                        Whether the results should be returned in descending sub-column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRows(String columnFamily, KeyRange keyRange, String superColName, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<Column>>> callback) throws PelopsException {
        getColumnsFromRows(newColumnParent(columnFamily, superColName), keyRange, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieve sub-columns from a super column in a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of sub-columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param superColName                    The name of the super column
     * @param colPredicate                    The sub-column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of sub-columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<Column>> getSubColumnsFromRows(String columnFamily, KeyRange keyRange, String superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRows(newColumnParent(columnFamily, superColName), keyRange, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieve sub-columns from a super column in a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of sub-columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param superColName                    The name of the super column
     * @param colPredicate                    The sub-column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRows(String columnFamily, KeyRange keyRange, String superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<Column>>> callback) throws PelopsException {
        getColumnsFromRows(newColumnParent(columnFamily, superColName), keyRange, colPredicate, cLevel, callback);
    }

    /**
     * Retrieve all sub-columns from a super column in a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of sub-columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param superColName                    The name of the super column
     * @param reversed                        Whether the results should be returned in descending sub-column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of sub-columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<String, List<Column>> getSubColumnsFromRowsUtf8Keys(String columnFamily, KeyRange keyRange, String superColName, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), keyRange, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all sub-columns from a super column in a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of sub-columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param superColName                    The name of the super column
     * @param reversed                        Whether the results should be returned in descending sub-column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRowsUtf8Keys(String columnFamily, KeyRange keyRange, String superColName, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<Column>>> callback) throws PelopsException {
        getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), keyRange, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieve sub-columns from a super column in a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of sub-columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param superColName                    The name of the super column
     * @param colPredicate                    The sub-column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of sub-columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<String, List<Column>> getSubColumnsFromRowsUtf8Keys(String columnFamily, KeyRange keyRange, String superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), keyRange, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieve sub-columns from a super column in a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of sub-columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param superColName                    The name of the super column
     * @param colPredicate                    The sub-column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getSubColumnsFromRowsUtf8Keys(String columnFamily, KeyRange keyRange, String superColName, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<Column>>> callback) throws PelopsException {
        getColumnsFromRowsUtf8Keys(newColumnParent(columnFamily, superColName), keyRange, colPredicate, cLevel, callback);
    }

    /**
     * Retrieve all super columns from a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of super columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param reversed                        Whether the results should be returned in descending super column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of super columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<SuperColumn>> getSuperColumnsFromRows(String columnFamily, KeyRange keyRange, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getSuperColumnsFromRows(columnFamily, keyRange, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all super columns from a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of super columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param reversed                        Whether the results should be returned in descending super column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getSuperColumnsFromRows(String columnFamily, KeyRange keyRange, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<SuperColumn>>> callback) throws PelopsException {
        getSuperColumnsFromRows(columnFamily, keyRange, columnsPredicateAll(reversed), cLevel, callback);
    }

    public List<KeySlice> getKeySlices(final ColumnParent columnParent, final KeyRange keyRange, final SlicePredicate colPredicate, final ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<List<KeySlice>> callback = new BlockingCallback<List<KeySlice>>();
        getKeySlices(columnParent, keyRange, colPredicate, cLevel, callback);
        return getResult(callback);
    }
    
    public void getKeySlices(final ColumnParent columnParent, final KeyRange keyRange, final SlicePredicate colPredicate, final ConsistencyLevel cLevel, AsyncMethodCallback<List<KeySlice>> callback) throws PelopsException {
        final AsyncMethodCallback<get_range_slices_call> innerCallback = new AbstractConvertingCallback<get_range_slices_call, List<KeySlice>>(callback) {

            @Override
            protected List<KeySlice> convert(get_range_slices_call fromData) throws Exception {
                return fromData.getResult();
            }
        };
        tryOperation(new IOperation<Void>() {
            @Override
            public Void execute(IPooledConnection conn) throws Exception {
                conn.getAPI().get_range_slices(columnParent, colPredicate, keyRange, cLevel, innerCallback);
                return null;
            }
        });
    }

    /**
     * Retrieve super columns from a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of super columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param colPredicate                    The super column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of super columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<SuperColumn>> getSuperColumnsFromRows(String columnFamily, KeyRange keyRange, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<LinkedHashMap<Bytes, List<SuperColumn>>> callback = new BlockingCallback<LinkedHashMap<Bytes,List<SuperColumn>>>();
        getSuperColumnsFromRows(columnFamily, keyRange, colPredicate, cLevel, callback);
        return getResult(callback);
    }

    /**
     * Asynchronously retrieve super columns from a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of super columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param colPredicate                    The super column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result 
     * @throws PelopsException if an error occurs
     */
    public void getSuperColumnsFromRows(String columnFamily, KeyRange keyRange, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<SuperColumn>>> callback) throws PelopsException {
        AsyncMethodCallback<List<KeySlice>> innerCallback = new AbstractConvertingCallback<List<KeySlice>, LinkedHashMap<Bytes, List<SuperColumn>>>(callback) {

            @Override
            protected LinkedHashMap<Bytes, List<SuperColumn>> convert(List<KeySlice> fromData) throws Exception {
                return transformKeySlices(fromData, SUPER_COLUMN);
            }
        };
        getKeySlices(newColumnParent(columnFamily), keyRange, colPredicate, cLevel, innerCallback);
    }

    /**
     * Retrieve all super columns from a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of super columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param reversed                        Whether the results should be returned in descending super column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of super columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<String, List<SuperColumn>> getSuperColumnsFromRowsUtf8Keys(String columnFamily, KeyRange keyRange, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getSuperColumnsFromRowsUtf8Keys(columnFamily, keyRange, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all super columns from a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of super columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param reversed                        Whether the results should be returned in descending super column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getSuperColumnsFromRowsUtf8Keys(String columnFamily, KeyRange keyRange, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<SuperColumn>>> callback) throws PelopsException {
        getSuperColumnsFromRowsUtf8Keys(columnFamily, keyRange, columnsPredicateAll(reversed), cLevel, callback);
    }

    /**
     * Retrieve super columns from a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of super columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param colPredicate                    The super column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of super columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<String, List<SuperColumn>> getSuperColumnsFromRowsUtf8Keys(String columnFamily, final KeyRange keyRange, final SlicePredicate colPredicate, final ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<LinkedHashMap<String, List<SuperColumn>>> callback = new BlockingCallback<LinkedHashMap<String,List<SuperColumn>>>();
        getSuperColumnsFromRowsUtf8Keys(columnFamily, keyRange, colPredicate, cLevel, callback);
        return getResult(callback);
    }
    
    /**
     * Asynchronously retrieve super columns from a range of rows.
     * The method returns a map from the keys of rows in the specified range to lists of super columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param columnFamily                    The column family containing the rows
     * @param keyRange                        A key range selecting the rows
     * @param colPredicate                    The super column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getSuperColumnsFromRowsUtf8Keys(String columnFamily, final KeyRange keyRange, final SlicePredicate colPredicate, final ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<SuperColumn>>> callback) throws PelopsException {
        AsyncMethodCallback<List<KeySlice>> innerCallback = new AbstractConvertingCallback<List<KeySlice>, LinkedHashMap<String, List<SuperColumn>>>(callback) {

            @Override
            protected LinkedHashMap<String, List<SuperColumn>> convert(List<KeySlice> fromData) throws Exception {
                return transformKeySlicesUtf8(fromData, SUPER_COLUMN);
            }
        };
        getKeySlices(newColumnParent(columnFamily), keyRange, colPredicate, cLevel, innerCallback);
    }

    private LinkedHashMap<Bytes, List<Column>> getColumnsFromRows(final ColumnParent colParent, final KeyRange keyRange, final SlicePredicate colPredicate, final ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<LinkedHashMap<Bytes, List<Column>>> callback = new BlockingCallback<LinkedHashMap<Bytes,List<Column>>>();
        getColumnsFromRows(colParent, keyRange, colPredicate, cLevel, callback);
        return getResult(callback);
    }

    private void getColumnsFromRows(final ColumnParent colParent, final KeyRange keyRange, final SlicePredicate colPredicate, final ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<Column>>> callback) throws PelopsException {
        AsyncMethodCallback<List<KeySlice>> innerCallback = new AbstractConvertingCallback<List<KeySlice>, LinkedHashMap<Bytes, List<Column>>>(callback) {

            @Override
            protected LinkedHashMap<Bytes, List<Column>> convert(List<KeySlice> fromData) throws Exception {
                return transformKeySlices(fromData, COLUMN);
            }
        };
        getKeySlices(colParent, keyRange, colPredicate, cLevel, innerCallback);
    }
    
    private LinkedHashMap<String, List<Column>> getColumnsFromRowsUtf8Keys(final ColumnParent colParent, final KeyRange keyRange, final SlicePredicate colPredicate, final ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<LinkedHashMap<String, List<Column>>> callback = new BlockingCallback<LinkedHashMap<String,List<Column>>>();
        getColumnsFromRowsUtf8Keys(colParent, keyRange, colPredicate, cLevel, callback);
        return getResult(callback);
    }

    private void getColumnsFromRowsUtf8Keys(final ColumnParent colParent, final KeyRange keyRange, final SlicePredicate colPredicate, final ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<String, List<Column>>> callback) throws PelopsException {
        AsyncMethodCallback<List<KeySlice>> innerCallback = new AbstractConvertingCallback<List<KeySlice>, LinkedHashMap<String, List<Column>>>(callback) {

            @Override
            protected LinkedHashMap<String, List<Column>> convert(List<KeySlice> fromData) throws Exception {
                return transformKeySlicesUtf8(fromData, COLUMN);
            }
        };
        getKeySlices(colParent, keyRange, colPredicate, cLevel, innerCallback);
    }

    /**
     * Retrieve all columns from a range of indexed rows using its secondary index.
     * The method returns a map from the keys of indexed rows in the specified range to lists of columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param colParent                    The column parent containing the rows
     * @param indexClause                        A index clause
     * @param reversed                        Whether the results should be returned in descending column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<Column>> getIndexedColumns(String colParent, IndexClause indexClause,boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
    	return getIndexedColumns(newColumnParent(colParent), indexClause, columnsPredicateAll(reversed), cLevel);
    }

    /**
     * Asynchronously retrieve all columns from a range of indexed rows using its secondary index.
     * The method returns a map from the keys of indexed rows in the specified range to lists of columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param colParent                    The column parent containing the rows
     * @param indexClause                        A index clause
     * @param reversed                        Whether the results should be returned in descending column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getIndexedColumns(String colParent, IndexClause indexClause,boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<Column>>> callback) throws PelopsException {
        getIndexedColumns(newColumnParent(colParent), indexClause, columnsPredicateAll(reversed), cLevel, callback);
    }
    
    /**
     * Retrieve columns from a range of indexed rows using its secondary index.
     * The method returns a map from the keys of indexed rows in the specified range to lists of columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param colParent                    The column parent containing the rows
     * @param indexClause                        A index clause
     * @param colPredicate                    The column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<Column>> getIndexedColumns(String colParent, IndexClause indexClause, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        return getIndexedColumns(newColumnParent(colParent), indexClause, colPredicate, cLevel);
    }
    
    /**
     * Asynchronously retrieve columns from a range of indexed rows using its secondary index.
     * The method returns a map from the keys of indexed rows in the specified range to lists of columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param colParent                    The column parent containing the rows
     * @param indexClause                        A index clause
     * @param colPredicate                    The column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getIndexedColumns(String colParent, IndexClause indexClause, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<Column>>> callback) throws PelopsException {
    	getIndexedColumns(newColumnParent(colParent), indexClause, colPredicate, cLevel, callback);
    }

    /**
     * Retrieve all columns from a range of indexed rows using its secondary index.
     * The method returns a map from the keys of indexed rows in the specified range to lists of columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param colParent                    The column parent
     * @param indexClause                        A index key range selecting the rows
     * @param reversed                        Whether the results should be returned in descending column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<Column>> getIndexedColumns(ColumnParent colParent, IndexClause indexClause, boolean reversed, ConsistencyLevel cLevel) throws PelopsException {
        return getIndexedColumns(colParent, indexClause, columnsPredicateAll(reversed), cLevel);
    }
    
    /**
     * Asynchronously retrieve all columns from a range of indexed rows using its secondary index.
     * The method returns a map from the keys of indexed rows in the specified range to lists of columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param colParent                    The column parent
     * @param indexClause                        A index key range selecting the rows
     * @param reversed                        Whether the results should be returned in descending column name order
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getIndexedColumns(ColumnParent colParent, IndexClause indexClause, boolean reversed, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<Column>>> callback) throws PelopsException {
        getIndexedColumns(colParent, indexClause, columnsPredicateAll(reversed), cLevel, callback);
    }

    public List<KeySlice> getKeySlices(final ColumnParent colParent, final IndexClause indexClause, final SlicePredicate colPredicate, final ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<List<KeySlice>> callback = new BlockingCallback<List<KeySlice>>();
        getKeySlices(colParent, indexClause, colPredicate, cLevel, callback);
        return getResult(callback);
    }

    public void getKeySlices(final ColumnParent colParent, final IndexClause indexClause, final SlicePredicate colPredicate, final ConsistencyLevel cLevel, final AsyncMethodCallback<List<KeySlice>> callback) throws PelopsException {
        tryOperation(new IOperation<Void>() {
            @Override
            public Void execute(IThriftPool.IPooledConnection conn) throws Exception {
                AsyncMethodCallback<get_indexed_slices_call> getIndexedSlicesCallback = new AbstractConvertingCallback<get_indexed_slices_call, List<KeySlice>>(callback) {

                    @Override
                    protected List<KeySlice> convert(get_indexed_slices_call getIndexedSlicesCall) throws Exception {
                        return getIndexedSlicesCall.getResult();
                    }
                };
                conn.getAPI().get_indexed_slices(colParent, indexClause, colPredicate, cLevel, getIndexedSlicesCallback);
                return null;
            }
        });
    }
    
    /**
     * Retrieve columns from a range of indexed rows using its secondary index.
     * The method returns a map from the keys of indexed rows in the specified range to lists of columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * @param colParent                    The column parent
     * @param indexClause                        A index key range selecting the rows
     * @param colPredicate                    The column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @return                                A map from row keys to the matching lists of columns
     * @throws PelopsException if an error occurs
     */
    public LinkedHashMap<Bytes, List<Column>> getIndexedColumns(ColumnParent colParent, IndexClause indexClause, SlicePredicate colPredicate, ConsistencyLevel cLevel) throws PelopsException {
        BlockingCallback<LinkedHashMap<Bytes, List<Column>>> callback = new BlockingCallback<LinkedHashMap<Bytes,List<Column>>>();
        getIndexedColumns(colParent, indexClause, colPredicate, cLevel, callback);
        return getResult(callback);
    }
    
    /**
     * Asynchronously retrieve columns from a range of indexed rows using its secondary index.
     * The method returns a map from the keys of indexed rows in the specified range to lists of columns from the rows. The map
     * returned is a <code>LinkedHashMap</code> and its key iterator proceeds in the order that the key data was returned by
     * Cassandra. If the cluster uses the RandomPartitioner, this order appears random.
     * 
     * @param colParent                    The column parent
     * @param indexClause                        A index key range selecting the rows
     * @param colPredicate                    The column selector predicate
     * @param cLevel                          The Cassandra consistency level with which to perform the operation
     * @param callback                        The callback to which to return the result
     * @throws PelopsException if an error occurs
     */
    public void getIndexedColumns(ColumnParent colParent, IndexClause indexClause, SlicePredicate colPredicate, ConsistencyLevel cLevel, AsyncMethodCallback<LinkedHashMap<Bytes, List<Column>>> callback) throws PelopsException {
        AsyncMethodCallback<List<KeySlice>> innerCallback = new AbstractConvertingCallback<List<KeySlice>, LinkedHashMap<Bytes, List<Column>>>(callback) {

            @Override
            protected LinkedHashMap<Bytes, List<Column>> convert(List<KeySlice> fromData) throws Exception {
                return transformKeySlices(fromData, COLUMN);
            }
        };
        getKeySlices(colParent, indexClause, colPredicate, cLevel, innerCallback);
    }

    /**
     * Create a new <code>IndexExpression</code> instance.
     * @param colName						The name of the column
     * @param op							The index expression operator (for now only EQ works)
     * @param value		 					Lookup value
     * @return								The new <code>IndexExpression</code>
     */
    public static IndexExpression newIndexExpression(Bytes colName, IndexOperator op, Bytes value) {
    	return new IndexExpression(colName.getBytes(), op, value.getBytes());
    }

    /**
     * Create a new <code>IndexExpression</code> instance.
     * @param colName						The name of the column
     * @param op							The index expression operator (for now only EQ works)
     * @param value		 					Lookup value
     * @return								The new <code>IndexExpression</code>
     */
    public static IndexExpression newIndexExpression(String colName, IndexOperator op, Bytes value) {
    	return newIndexExpression(fromUTF8(colName), op, value);
    }

    /**
     * Create a new <code>IndexClause</code> instance.
     * @param startName						The inclusive column start name of the index range to select in the slice
     * @param count							The maximum number of rows to return
     * @param expressions 					Index value lookup expressions
     * @return								The new <code>IndexClause</code>
     */
    public static IndexClause newIndexClause(String startName, int count, IndexExpression... expressions) {
    	return newIndexClause(fromUTF8(startName), count, expressions);
    }

    /**
     * Create a new <code>IndexClause</code> instance.
     * @param startName						The inclusive column start name of the index range to select in the slice
     * @param count							The maximum number of rows to return
     * @param expressions 					Index value lookup expressions
     * @return								The new <code>IndexClause</code>
     */
    public static IndexClause newIndexClause(Bytes startName, int count, IndexExpression... expressions) {
    	return new IndexClause(Arrays.asList(expressions), startName.getBytes(), count);
    }

    /**
     * Create the internal <code>SlicePredicate</code> instance that selects "all" columns with no imposed limit.
     * Note: these instances should be handled carefully, as they are mutable.
     * @param reversed                        Whether the results should be returned in reverse order
     * @return                                The new <code>SlicePredicate</code>
     */
    private static SlicePredicate columnsPredicateAll(boolean reversed) {
        return reversed ? COLUMNS_PREDICATE_ALL_REVERSED : COLUMNS_PREDICATE_ALL;
    }

    /**
     * Create a new <code>SlicePredicate</code> instance that selects "all" columns with no imposed limit
     * @param reversed                        Whether the results should be returned in reverse order
     * @return                                The new <code>SlicePredicate</code>
     */
    public static SlicePredicate newColumnsPredicateAll(boolean reversed) {
        return newColumnsPredicateAll(reversed, Integer.MAX_VALUE);
    }

    /**
     * Create a new <code>SlicePredicate</code> instance that selects "all" columns
     * @param reversed                        Whether the results should be returned in reverse order
     * @param maxColCount                     The maximum number of columns to return
     * @return                                The new <code>SlicePredicate</code>
     */
    public static SlicePredicate newColumnsPredicateAll(boolean reversed, int maxColCount) {
        SlicePredicate predicate = new SlicePredicate();
        predicate.setSlice_range(new SliceRange(Bytes.EMPTY.getBytes(), Bytes.EMPTY.getBytes(), reversed, maxColCount));
        return predicate;
    }

    /**
     * Create a new <code>SlicePredicate</code> instance.
     * @param startName                       The inclusive column start name of the range to select in the slice
     * @param finishName                      The inclusive column end name of the range to select in the slice
     * @param reversed                        Whether the results should be returned in reverse order
     * @param maxColCount                     The maximum number of columns to return
     * @return                                The new <code>SlicePredicate</code>
     */
    public static SlicePredicate newColumnsPredicate(Bytes startName, Bytes finishName, boolean reversed, int maxColCount) {
        SlicePredicate predicate = new SlicePredicate();
        predicate.setSlice_range(new SliceRange(nullSafeGet(startName), nullSafeGet(finishName), reversed, maxColCount));
        return predicate;
    }

    /**
     * Create a new <code>SlicePredicate</code> instance.
     * @param startName                       The inclusive column start name of the range to select in the slice
     * @param finishName                      The inclusive column end name of the range to select in the slice
     * @param reversed                        Whether the results should be returned in reverse order
     * @param maxColCount                     The maximum number of columns to return
     * @return                                The new <code>SlicePredicate</code>
     */
    public static SlicePredicate newColumnsPredicate(String startName, String finishName, boolean reversed, int maxColCount) {
        return newColumnsPredicate(fromUTF8(startName), fromUTF8(finishName), reversed, maxColCount);
    }

    /**
     * Create a new <code>SlicePredicate</code> instance.
     * @param colNames                        The specific columns names to select in the slice
     * @return                                The new <code>SlicePredicate</code>
     */
    public static SlicePredicate newColumnsPredicate(String... colNames) {
        List<ByteBuffer> asList = new ArrayList<ByteBuffer>(32);
        for (String colName : colNames)
            asList.add(fromUTF8(colName).getBytes());
        SlicePredicate predicate = new SlicePredicate();
        predicate.setColumn_names(asList);
        return predicate;
    }

    /**
     * Create a new <code>SlicePredicate</code> instance.
     * @param colNames                        The specific columns names to select in the slice
     * @return                                The new <code>SlicePredicate</code>
     */
    public static SlicePredicate newColumnsPredicate(Bytes... colNames) {
        List<ByteBuffer> asList = new ArrayList<ByteBuffer>(32);
        for (Bytes colName : colNames)
            asList.add(nullSafeGet(colName));
        SlicePredicate predicate = new SlicePredicate();
        predicate.setColumn_names(asList);
        return predicate;
    }

    /**
     * Create a new <code>KeyRange</code> instance.
     * @param startKey                        The inclusive start key of the range
     * @param finishKey                       The inclusive finish key of the range
     * @param maxKeyCount                     The maximum number of keys to be scanned
     * @return                                The new <code>KeyRange</code> instance
     */
    public static KeyRange newKeyRange(String startKey, String finishKey, int maxKeyCount) {
        return newKeyRange(fromUTF8(startKey), fromUTF8(finishKey), maxKeyCount);
    }

    /**
     * Create a new <code>KeyRange</code> instance.
     * @param startKey                        The inclusive start key of the range
     * @param finishKey                       The inclusive finish key of the range
     * @param maxKeyCount                     The maximum number of keys to be scanned
     * @return                                The new <code>KeyRange</code> instance
     */
    public static KeyRange newKeyRange(Bytes startKey, Bytes finishKey, int maxKeyCount) {
        KeyRange keyRange = new KeyRange(maxKeyCount);
        keyRange.setStart_key(nullSafeGet(startKey));
        keyRange.setEnd_key(nullSafeGet(finishKey));
        return keyRange;
    }

    /**
     * Create a new <code>KeyRange</code> instance.
     * @param startFollowingKey                The exclusive start key of the ring range
     * @param finishKey                        The inclusive finish key of the range (can be less than <code>startFollowing</code>)
     * @param maxKeyCount                      The maximum number of keys to be scanned
     * @return                                 The new <code>KeyRange</code> instance
     */
    public static KeyRange newKeyRingRange(String startFollowingKey, String finishKey, int maxKeyCount) {
        KeyRange keyRange = new KeyRange(maxKeyCount);
        keyRange.setStart_token(startFollowingKey);
        keyRange.setEnd_token(finishKey);
        return keyRange;
    }

    /**
     * Determines if a super column with a particular name exist in the list of super columns.
     * @param superColumns                    The list of super columns
     * @param superColName                    The name of the super column
     * @return                                Whether the super column is present
     */
    public static boolean superColumnExists(List<SuperColumn> superColumns, String superColName) {
        return superColumnExists(superColumns, fromUTF8(superColName));
    }

    /**
     * Determines if a super column with a particular name exist in the list of super columns.
     * @param superColumns                    The list of super columns
     * @param superColName                    The name of the super column
     * @return                                Whether the super column is present
     */
    public static boolean superColumnExists(List<SuperColumn> superColumns, Bytes superColName) {
        for (SuperColumn superColumn : superColumns)
            if (superColumn.name.equals(nullSafeGet(superColName)))
                return true;
        return false;
    }

    /**
     * Get a super column by name from a list of super columns
     * @param superColumns                    The list of super columns
     * @param superColName                    The name of the super column
     * @return                                The super column
     * @throws ArrayIndexOutOfBoundsException    Thrown if the list does not contain a super column with the specified name
     */
    public static SuperColumn getSuperColumn(List<SuperColumn> superColumns, Bytes superColName) throws ArrayIndexOutOfBoundsException {
        for (SuperColumn superColumn : superColumns)
            if (superColumn.name.equals(nullSafeGet(superColName)))
                return superColumn;
        throw new ArrayIndexOutOfBoundsException();
    }

    /**
     * Get a super column by name from a list of super columns
     * @param superColumns                    The list of super columns
     * @param superColName                    The name of the super column
     * @return                                The super column
     * @throws ArrayIndexOutOfBoundsException    Thrown if the list does not contain a super column with the specified name
     */
    public static SuperColumn getSuperColumn(List<SuperColumn> superColumns, String superColName) throws ArrayIndexOutOfBoundsException {
        return getSuperColumn(superColumns, fromUTF8(superColName));
    }

    /**
     * Get the name of a column as a UTF8 string
     * @param column							The column
     * @return									The <code>byte[]</code> name as a UTF8 string
     */
    public static String getColumnStringName(Column column) {
    	return toUTF8(column.getName());
    }

    /**
     * Get the value of a column as a UTF8 string
     * @param column							The column containing the value
     * @return									The <code>byte[]</code> value as a UTF8 string
     */
    public static String getColumnStringValue(Column column) {
    	return toUTF8(column.getValue());
    }

    /**
     * Get the value of a column in a list of columns
     * @param columns                         The list of columns
     * @param colName                         The name of the column from which to retrieve the value
     * @param defaultValue                    A default value to return if a column with the specified name is not present in the list
     * @return                                The column value
     */
    public static String getColumnValue(List<Column> columns, String colName, String defaultValue) {
        return getColumnValue(columns, fromUTF8(colName), defaultValue);
    }

    /**
     * Get the value of a column in a list of columns
     * @param columns                         The list of columns
     * @param colName                         The name of the column from which to retrieve the value
     * @param defaultValue                    A default value to return if a column with the specified name is not present in the list
     * @return                                The column value
     */
    public static Bytes getColumnValue(List<Column> columns, String colName, Bytes defaultValue) {
        return getColumnValue(columns, fromUTF8(colName), defaultValue);
    }

    /**
     * Get the value of a column in a list of columns
     * @param columns                        The list of columns
     * @param colName                        The name of the column from which to retrieve the value
     * @param defaultValue                   A default value to return if a column with the specified name is not present in the list
     * @return                                The column value
     */
    public static Bytes getColumnValue(List<Column> columns, Bytes colName, Bytes defaultValue) {
        for (Column column : columns)
            if (column.name.equals(nullSafeGet(colName)))
                return fromByteBuffer(column.value);
        return defaultValue;
    }

    /**
     * Get the value of a counter column in a list of columns
     * @param columns                        The list of columns
     * @param colName                        The name of the column from which to retrieve the value
     * @param defaultValue                   A default value to return if a column with the specified name is not present in the list
     * @return                                The column value
     */
    public static long getCountColumnValue(List<CounterColumn> columns, Bytes colName, Long defaultValue) {
        for (CounterColumn column : columns)
            if (column.name.equals(nullSafeGet(colName)))
                return column.value;
        
        return defaultValue;
    }

    /**
     * Get the value of a counter column in a list of columns
     * @param columns                        The list of columns
     * @param colName                        The name of the column from which to retrieve the value
     * @param defaultValue                   A default value to return if a column with the specified name is not present in the list
     * @return                                The column value
     */
    public static long getCountColumnValue(List<CounterColumn> columns, String colName, Long defaultValue) {
        return getCountColumnValue(columns, fromUTF8(colName), defaultValue);
    }

    /**
     * Get the value of a column in a list of columns
     * @param columns                        The list of columns
     * @param colName                        The name of the column from which to retrieve the value
     * @param defaultValue                   A default value to return if a column with the specified name is not present in the list
     * @return                               The column value
     */
    public static String getColumnValue(List<Column> columns, Bytes colName, String defaultValue) {
        for (Column column : columns)
            if (column.name.equals(nullSafeGet(colName)))
                return toUTF8(column.value);
        return defaultValue;
    }

    /**
     * Determines if a column with a particular name exist in the list of columns.
     * @param columns                    The list of columns
     * @param colName                    The name of the column
     * @return                                Whether the column is present
     */
    public static boolean columnExists(List<Column> columns, Bytes colName) {
        for (Column column : columns)
            if (column.name.equals(nullSafeGet(colName)))
                return true;
        return false;
    }

    /**
     * Determines if a column with a particular name exist in the list of columns.
     * @param columns                    The list of columns
     * @param colName                    The name of the column
     * @return                                Whether the column is present
     */
    public static boolean columnExists(List<Column> columns, String colName) {
        return columnExists(columns, fromUTF8(colName));
    }

    /**
     * Get the value of a column in a list of columns
     * @param columns                        The list of columns
     * @param colName                        The name of the column from which to retrieve the value
     * @return                               The column value
     * @throws ArrayIndexOutOfBoundsException    Thrown if the specified column was not found
     */
    public static Bytes getColumnValue(List<Column> columns, Bytes colName) throws ArrayIndexOutOfBoundsException {
        for (Column column : columns)
            if (column.name.equals(nullSafeGet(colName)))
                return fromByteBuffer(column.value);
        throw new ArrayIndexOutOfBoundsException();
    }

    /**
     * Get the value of a column in a list of columns
     * @param columns                        The list of columns
     * @param colName                        The name of the column from which to retrieve the value
     * @return                               The column value
     * @throws ArrayIndexOutOfBoundsException    Thrown if the specified column was not found
     */
    public static Bytes getColumnValue(List<Column> columns, String colName) throws ArrayIndexOutOfBoundsException {
        return getColumnValue(columns, fromUTF8(colName));
    }

    /**
     * Get the value of a column in a list of columns
     * @param columns                        The list of columns
     * @param colName                        The name of the column from which to retrieve the value
     * @return                               The column value as a <code>String</code>
     * @throws ArrayIndexOutOfBoundsException    Thrown if the specified column was not found
     */
    public static String getColumnStringValue(List<Column> columns, String colName) throws ArrayIndexOutOfBoundsException {
        return getColumnStringValue(columns, fromUTF8(colName));
    }

    /**
     * Get the value of a column in a list of columns
     * @param columns                        The list of columns
     * @param colName                        The name of the column from which to retrieve the value
     * @return                               The column value as a <code>String</code>
     * @throws ArrayIndexOutOfBoundsException    Thrown if the specified column was not found
     */
    public static String getColumnStringValue(List<Column> columns, Bytes colName) throws ArrayIndexOutOfBoundsException {
        for (Column column : columns)
            if (column.name.equals(nullSafeGet(colName)))
                return toUTF8(column.value);
        throw new ArrayIndexOutOfBoundsException();
    }

    /**
     * Get the time stamp of a column in a list of columns.
     * @param columns                        The list of columns
     * @param colName                        The name of the column from which to retrieve the timestamp
     * @return                               The time stamp (the <code>Mutator</code> object uses time stamps as microseconds)
     * @throws ArrayIndexOutOfBoundsException    Thrown if the list does not contain a column with the specified name
     */
    public static long getColumnTimestamp(List<Column> columns, Bytes colName) throws ArrayIndexOutOfBoundsException {
        for (Column column : columns)
            if (column.name.equals(nullSafeGet(colName)))
                return column.getTimestamp();
        throw new ArrayIndexOutOfBoundsException();
    }

    /**
     * Get the time stamp of a column in a list of columns.
     * @param columns                        The list of columns
     * @param colName                        The name of the column from which to retrieve the timestamp
     * @return                               The time stamp (the <code>Mutator</code> object uses time stamps as microseconds)
     * @throws ArrayIndexOutOfBoundsException    Thrown if the list does not contain a column with the specified name
     */
    public static long getColumnTimestamp(List<Column> columns, String colName) throws ArrayIndexOutOfBoundsException {
        return getColumnTimestamp(columns, fromUTF8(colName));
    }

    /**
     * Create a batch mutation operation.
     */
    public Selector(IThriftPool thrift) {
        super(thrift);
    }

    private static ColumnPath newColumnPath(String columnFamily, Bytes superColName, Bytes colName) {
        ColumnPath path = new ColumnPath(columnFamily);
        path.setSuper_column(nullSafeGet(superColName));
        path.setColumn(nullSafeGet(colName));
        return path;
    }

    private static ColumnParent newColumnParent(String columnFamily, String superColName) {
        return newColumnParent(columnFamily, Bytes.fromUTF8(superColName));
    }

    private static ColumnParent newColumnParent(String columnFamily, Bytes superColName) {
        ColumnParent parent = new ColumnParent(columnFamily);
        parent.setSuper_column(nullSafeGet(superColName));
        return parent;
    }

    public static ColumnParent newColumnParent(String columnFamily) {
        return new ColumnParent(columnFamily);
    }
}
