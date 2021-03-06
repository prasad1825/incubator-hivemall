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
package hivemall.ftvec.selection;

import hivemall.utils.hadoop.HiveUtils;
import hivemall.utils.hadoop.WritableUtils;
import hivemall.utils.lang.Preconditions;
import hivemall.utils.math.StatsUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.UDFArgumentException;
import org.apache.hadoop.hive.ql.exec.UDFArgumentLengthException;
import org.apache.hadoop.hive.ql.exec.UDFArgumentTypeException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.udf.UDFType;
import org.apache.hadoop.hive.ql.udf.generic.GenericUDF;
import org.apache.hadoop.hive.serde2.io.DoubleWritable;
import org.apache.hadoop.hive.serde2.objectinspector.ListObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;

@Description(name = "chi2",
        value = "_FUNC_(array<array<number>> observed, array<array<number>> expected)"
                + " - Returns chi2_val and p_val of each columns as <array<double>, array<double>>")
@UDFType(deterministic = true, stateful = false)
public final class ChiSquareUDF extends GenericUDF {

    private ListObjectInspector observedOI;
    private ListObjectInspector observedRowOI;
    private PrimitiveObjectInspector observedElOI;
    private ListObjectInspector expectedOI;
    private ListObjectInspector expectedRowOI;
    private PrimitiveObjectInspector expectedElOI;

    private int nFeatures = -1;
    private double[] observedRow = null; // to reuse
    private double[] expectedRow = null; // to reuse
    private double[][] observed = null; // shape = (#features, #classes)
    private double[][] expected = null; // shape = (#features, #classes)

    private List<DoubleWritable>[] result;

    @SuppressWarnings("unchecked")
    @Override
    public ObjectInspector initialize(ObjectInspector[] OIs) throws UDFArgumentException {
        if (OIs.length != 2) {
            throw new UDFArgumentLengthException("Specify two arguments: " + OIs.length);
        }
        if (!HiveUtils.isNumberListListOI(OIs[0])) {
            throw new UDFArgumentTypeException(0,
                "Only array<array<number>> type argument is acceptable but " + OIs[0].getTypeName()
                        + " was passed as `observed`");
        }
        if (!HiveUtils.isNumberListListOI(OIs[1])) {
            throw new UDFArgumentTypeException(1,
                "Only array<array<number>> type argument is acceptable but " + OIs[1].getTypeName()
                        + " was passed as `expected`");
        }

        this.observedOI = HiveUtils.asListOI(OIs[1]);
        this.observedRowOI = HiveUtils.asListOI(observedOI.getListElementObjectInspector());
        this.observedElOI =
                HiveUtils.asDoubleCompatibleOI(observedRowOI.getListElementObjectInspector());
        this.expectedOI = HiveUtils.asListOI(OIs[0]);
        this.expectedRowOI = HiveUtils.asListOI(expectedOI.getListElementObjectInspector());
        this.expectedElOI =
                HiveUtils.asDoubleCompatibleOI(expectedRowOI.getListElementObjectInspector());
        this.result = new List[2];

        List<ObjectInspector> fieldOIs = new ArrayList<ObjectInspector>();
        fieldOIs.add(ObjectInspectorFactory.getStandardListObjectInspector(
            PrimitiveObjectInspectorFactory.writableDoubleObjectInspector));
        fieldOIs.add(ObjectInspectorFactory.getStandardListObjectInspector(
            PrimitiveObjectInspectorFactory.writableDoubleObjectInspector));

        return ObjectInspectorFactory.getStandardStructObjectInspector(
            Arrays.asList("chi2", "pvalue"), fieldOIs);
    }

    @Override
    public List<DoubleWritable>[] evaluate(DeferredObject[] dObj) throws HiveException {
        List<?> observedObj = observedOI.getList(dObj[0].get()); // shape = (#classes, #features)
        List<?> expectedObj = expectedOI.getList(dObj[1].get()); // shape = (#classes, #features)

        if (observedObj == null || expectedObj == null) {
            return null;
        }

        final int nClasses = observedObj.size();
        Preconditions.checkArgument(nClasses == expectedObj.size(), UDFArgumentException.class);

        // explode and transpose matrix
        for (int i = 0; i < nClasses; i++) {
            Object observedObjRow = observedObj.get(i);
            Object expectedObjRow = expectedObj.get(i);

            Preconditions.checkNotNull(observedObjRow, UDFArgumentException.class);
            Preconditions.checkNotNull(expectedObjRow, UDFArgumentException.class);

            if (observedRow == null) {
                observedRow =
                        HiveUtils.asDoubleArray(observedObjRow, observedRowOI, observedElOI, false);
                expectedRow =
                        HiveUtils.asDoubleArray(expectedObjRow, expectedRowOI, expectedElOI, false);
                nFeatures = observedRow.length;
                observed = new double[nFeatures][nClasses];
                expected = new double[nFeatures][nClasses];
            } else {
                HiveUtils.toDoubleArray(observedObjRow, observedRowOI, observedElOI, observedRow,
                    false);
                HiveUtils.toDoubleArray(expectedObjRow, expectedRowOI, expectedElOI, expectedRow,
                    false);
            }

            for (int j = 0; j < nFeatures; j++) {
                observed[j][i] = observedRow[j];
                expected[j][i] = expectedRow[j];
            }
        }

        Map.Entry<double[], double[]> chi2 = StatsUtils.chiSquare(observed, expected);

        result[0] = WritableUtils.toWritableList(chi2.getKey(), result[0]);
        result[1] = WritableUtils.toWritableList(chi2.getValue(), result[1]);
        return result;
    }

    @Override
    public void close() throws IOException {
        // help GC
        this.observedRow = null;
        this.expectedRow = null;
        this.observed = null;
        this.expected = null;
        this.result = null;
    }

    @Override
    public String getDisplayString(String[] children) {
        final StringBuilder sb = new StringBuilder();
        sb.append("chi2");
        sb.append("(");
        if (children.length > 0) {
            sb.append(children[0]);
            for (int i = 1; i < children.length; i++) {
                sb.append(", ");
                sb.append(children[i]);
            }
        }
        sb.append(")");
        return sb.toString();
    }
}
