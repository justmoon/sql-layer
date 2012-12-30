/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.server.service.externaldata;

import com.akiban.ais.model.Column;
import com.akiban.ais.model.UserTable;
import com.akiban.qp.operator.QueryContext;
import com.akiban.qp.persistitadapter.PValueRowDataCreator;
import com.akiban.server.api.dml.scan.NewRow;
import com.akiban.server.api.dml.scan.NiceRow;
import com.akiban.server.rowdata.RowDef;
import com.akiban.server.types.AkType;
import com.akiban.server.types.FromObjectValueSource;
import com.akiban.server.types.ToObjectValueTarget;
import com.akiban.server.types.conversion.Converters;
import com.akiban.server.types.util.ValueHolder;
import com.akiban.server.types3.ErrorHandlingMode;
import com.akiban.server.types3.TExecutionContext;
import com.akiban.server.types3.TInstance;
import com.akiban.server.types3.Types3Switch;
import com.akiban.server.types3.mcompat.mtypes.MString;
import com.akiban.server.types3.pvalue.PValue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;


/** Read rows from an external source. */
public abstract class RowReader
{
    protected final RowDef rowDef;
    protected final int[] fieldMap;
    protected final boolean[] nullable;
    protected final byte[] nullBytes;
    protected final int tableId;
    protected final boolean usePValues;
    protected final PValue pstring;
    protected final PValue[] pvalues;
    protected final PValueRowDataCreator pvalueCreator;
    protected final TExecutionContext[] executionContexts;
    protected final FromObjectValueSource fromObject;
    protected final ValueHolder holder;
    protected final ToObjectValueTarget toObject;
    protected AkType[] aktypes;
    protected NewRow row;
    protected byte[] buffer = new byte[128];
    protected int fieldIndex, fieldLength;
    protected String encoding;

    protected RowReader(UserTable table, List<Column> columns, 
                        String encoding, byte[] nullBytes,
                        QueryContext queryContext) {
        this.tableId = table.getTableId();
        this.rowDef = table.rowDef();
        this.fieldMap = new int[columns.size()];
        this.nullable = new boolean[fieldMap.length];
        for (int i = 0; i < fieldMap.length; i++) {
            Column column = columns.get(i);
            fieldMap[i] = column.getPosition();
            nullable[i] = column.getNullable();
        }
        this.usePValues = Types3Switch.ON;
        if (usePValues) {
            pstring = new PValue(MString.VARCHAR.instance(Integer.MAX_VALUE, false));
            pvalues = new PValue[columns.size()];
            executionContexts = new TExecutionContext[pvalues.length];
            List<TInstance> inputs = Collections.singletonList(pstring.tInstance());
            for (int i = 0; i < pvalues.length; i++) {
                TInstance output = columns.get(i).tInstance();
                pvalues[i] = new PValue(output);
                // TODO: Only needed until every place gets type from
                // PValueTarget, when there can just be one
                // TExecutionContext wrapping the QueryContext.
                executionContexts[i] = new TExecutionContext(null, 
                                                             inputs, output, queryContext,
                                                             ErrorHandlingMode.WARN,
                                                             ErrorHandlingMode.WARN,
                                                             ErrorHandlingMode.WARN);
            }
            pvalueCreator = new PValueRowDataCreator();
            fromObject = null;
            holder = null;
            toObject = null;
            aktypes = null;
        }
        else {
            fromObject = new FromObjectValueSource();
            holder = new ValueHolder();
            toObject = new ToObjectValueTarget();
            aktypes = new AkType[columns.size()];
            for (int i = 0; i < aktypes.length; i++) {
                aktypes[i] = columns.get(i).getType().akType();
            }
            pstring = null;
            pvalues = null;
            pvalueCreator = null;
            executionContexts = null;
        }
        this.encoding = encoding;
        this.nullBytes = nullBytes;
    }

    protected NewRow newRow() {
        row = new NiceRow(tableId, rowDef);
        fieldIndex = fieldLength = 0;
        return row;
    }

    public abstract NewRow nextRow(InputStream inputStream) throws IOException;

    protected void addToField(int b) {
        if (fieldLength + 1 > buffer.length) {
            buffer = Arrays.copyOf(buffer, (buffer.length * 3) / 2);
        }
        buffer[fieldLength++] = (byte)b;
    }

    protected void addField(boolean quoted) {
        if (!quoted && nullable[fieldIndex]) {
            // Check whether unquoted value matches the representation
            // of null, normally the empty string.
            if (fieldLength == nullBytes.length) {
                boolean match = true;
                for (int i = 0; i < fieldLength; i++) {
                    if (buffer[i] != nullBytes[i]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    row.put(fieldMap[fieldIndex++], null);
                    fieldLength = 0;
                    return;
                }
            }
        }
        int columnIndex = fieldMap[fieldIndex];
        // bytes -> string -> parsed typed value -> Java object.
        String string;
        try {
            string = new String(buffer, 0, fieldLength, encoding);
        }
        catch (UnsupportedEncodingException ex) {
            UnsupportedCharsetException nex = new UnsupportedCharsetException(encoding);
            nex.initCause(ex);
            throw nex;
        }
        if (usePValues) {
            pstring.putString(string, null);
            PValue pvalue = pvalues[fieldIndex];
            pvalue.tInstance().typeClass()
                .fromObject(executionContexts[fieldIndex], pstring, pvalue);
            pvalueCreator.put(pvalue, row, rowDef.getFieldDef(columnIndex), columnIndex);
        }
        else {
            fromObject.setExplicitly(string, AkType.VARCHAR);
            holder.expectType(aktypes[fieldIndex]);
            Converters.convert(fromObject, holder);
            row.put(columnIndex, toObject.convertFromSource(holder));
        }
        fieldIndex++;
        fieldLength = 0;
    }

}
