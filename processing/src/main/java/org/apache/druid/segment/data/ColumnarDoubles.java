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

package org.apache.druid.segment.data;

import org.apache.druid.collections.bitmap.ImmutableBitmap;
import org.apache.druid.query.monomorphicprocessing.RuntimeShapeInspector;
import org.apache.druid.segment.ColumnValueSelector;
import org.apache.druid.segment.DoubleColumnSelector;
import org.apache.druid.segment.historical.HistoricalColumnSelector;
import org.apache.druid.segment.vector.BaseDoubleVectorValueSelector;
import org.apache.druid.segment.vector.ReadableVectorInspector;
import org.apache.druid.segment.vector.ReadableVectorOffset;
import org.apache.druid.segment.vector.VectorSelectorUtils;
import org.apache.druid.segment.vector.VectorValueSelector;
import org.roaringbitmap.PeekableIntIterator;

import javax.annotation.Nullable;
import java.io.Closeable;

/**
 * Resource that provides random access to a packed array of primitive doubles. Backs up {@link
 * org.apache.druid.segment.column.DoublesColumn}.
 */
public interface ColumnarDoubles extends Closeable
{
  int size();

  double get(int index);

  default void get(double[] out, int start, int length)
  {
    get(out, 0, start, length);
  }

  default void get(double[] out, int offset, int start, int length)
  {
    for (int i = 0; i < length; i++) {
      out[offset + i] = get(i + start);
    }
  }

  default void get(double[] out, int[] indexes, int length)
  {
    for (int i = 0; i < length; i++) {
      out[i] = get(indexes[i]);
    }
  }

  @Override
  void close();

  default ColumnValueSelector<Double> makeColumnValueSelector(ReadableOffset offset, ImmutableBitmap nullValueBitmap)
  {
    if (nullValueBitmap.isEmpty()) {
      class HistoricalDoubleColumnSelector implements DoubleColumnSelector, HistoricalColumnSelector<Double>
      {
        @Override
        public boolean isNull()
        {
          return false;
        }

        @Override
        public double getDouble()
        {
          return ColumnarDoubles.this.get(offset.getOffset());
        }

        @Override
        public double getDouble(int offset)
        {
          return ColumnarDoubles.this.get(offset);
        }

        @Override
        public void inspectRuntimeShape(RuntimeShapeInspector inspector)
        {
          inspector.visit("columnar", ColumnarDoubles.this);
          inspector.visit("offset", offset);
        }
      }
      return new HistoricalDoubleColumnSelector();
    } else {
      class HistoricalDoubleColumnSelectorWithNulls implements DoubleColumnSelector, HistoricalColumnSelector<Double>
      {
        private PeekableIntIterator nullIterator = nullValueBitmap.peekableIterator();
        private int nullMark = -1;
        private int offsetMark = -1;

        @Override
        public boolean isNull()
        {
          final int i = offset.getOffset();
          if (i < offsetMark) {
            // offset was reset, reset iterator state
            nullMark = -1;
            nullIterator = nullValueBitmap.peekableIterator();
          }
          offsetMark = i;
          if (nullMark < i) {
            nullIterator.advanceIfNeeded(offsetMark);
            if (nullIterator.hasNext()) {
              nullMark = nullIterator.next();
            }
          }
          return nullMark == offsetMark;
        }

        @Override
        public double getDouble()
        {
          //noinspection AssertWithSideEffects (ignore null handling test initialization check side effect)
          assert !isNull();
          return ColumnarDoubles.this.get(offset.getOffset());
        }

        @Override
        public double getDouble(int offset)
        {
          assert !nullValueBitmap.get(offset);
          return ColumnarDoubles.this.get(offset);
        }

        @Override
        public void inspectRuntimeShape(RuntimeShapeInspector inspector)
        {
          inspector.visit("columnar", ColumnarDoubles.this);
          inspector.visit("offset", offset);
          inspector.visit("nullValueBitmap", nullValueBitmap);
        }
      }
      return new HistoricalDoubleColumnSelectorWithNulls();
    }
  }

  default VectorValueSelector makeVectorValueSelector(
      final ReadableVectorOffset theOffset,
      final ImmutableBitmap nullValueBitmap
  )
  {
    class ColumnarDoublesVectorValueSelector extends BaseDoubleVectorValueSelector
    {
      private final double[] doubleVector;

      private int id = ReadableVectorInspector.NULL_ID;

      private PeekableIntIterator nullIterator = nullValueBitmap.peekableIterator();
      private int offsetMark = -1;

      @Nullable
      private boolean[] nullVector = null;

      private ColumnarDoublesVectorValueSelector()
      {
        super(theOffset);
        this.doubleVector = new double[offset.getMaxVectorSize()];
      }

      @Nullable
      @Override
      public boolean[] getNullVector()
      {
        computeVectorsIfNeeded();
        return nullVector;
      }

      @Override
      public double[] getDoubleVector()
      {
        computeVectorsIfNeeded();
        return doubleVector;
      }

      private void computeVectorsIfNeeded()
      {
        if (id == offset.getId()) {
          return;
        }

        if (offset.isContiguous()) {
          if (offset.getStartOffset() < offsetMark) {
            nullIterator = nullValueBitmap.peekableIterator();
          }
          offsetMark = offset.getStartOffset() + offset.getCurrentVectorSize();
          ColumnarDoubles.this.get(doubleVector, offset.getStartOffset(), offset.getCurrentVectorSize());
        } else {
          final int[] offsets = offset.getOffsets();
          if (offsets[offsets.length - 1] < offsetMark) {
            nullIterator = nullValueBitmap.peekableIterator();
          }
          offsetMark = offsets[offsets.length - 1];
          ColumnarDoubles.this.get(doubleVector, offsets, offset.getCurrentVectorSize());
        }

        nullVector = VectorSelectorUtils.populateNullVector(nullVector, offset, nullIterator);

        id = offset.getId();
      }
    }

    return new ColumnarDoublesVectorValueSelector();
  }
}
