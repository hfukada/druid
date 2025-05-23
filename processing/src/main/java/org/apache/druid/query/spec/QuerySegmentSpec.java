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

package org.apache.druid.query.spec;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QuerySegmentWalker;
import org.joda.time.Interval;

import java.util.List;

/**
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = LegacySegmentSpec.class)
@JsonSubTypes(value = {
    @JsonSubTypes.Type(name = "intervals", value = MultipleIntervalSegmentSpec.class),
    @JsonSubTypes.Type(name = "segments", value = MultipleSpecificSegmentSpec.class)
})
public interface QuerySegmentSpec
{
  public static QuerySegmentSpec ETERNITY = new MultipleIntervalSegmentSpec(Intervals.ONLY_ETERNITY);

  List<Interval> getIntervals();

  <T> QueryRunner<T> lookup(Query<T> query, QuerySegmentWalker walker);
}
