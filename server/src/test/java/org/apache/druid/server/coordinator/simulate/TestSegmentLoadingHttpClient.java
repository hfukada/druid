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

package org.apache.druid.server.coordinator.simulate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import org.apache.druid.java.util.http.client.HttpClient;
import org.apache.druid.java.util.http.client.Request;
import org.apache.druid.java.util.http.client.response.HttpResponseHandler;
import org.apache.druid.server.coordination.DataSegmentChangeCallback;
import org.apache.druid.server.coordination.DataSegmentChangeHandler;
import org.apache.druid.server.coordination.DataSegmentChangeRequest;
import org.apache.druid.server.coordination.DataSegmentChangeResponse;
import org.apache.druid.server.coordination.SegmentChangeStatus;
import org.apache.druid.server.http.SegmentLoadingCapabilities;
import org.apache.druid.server.http.SegmentLoadingMode;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.joda.time.Duration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TestSegmentLoadingHttpClient implements HttpClient
{
  private static final HttpResponseHandler.TrafficCop NOOP_TRAFFIC_COP = checkNum -> 0L;
  private static final DataSegmentChangeCallback NOOP_CALLBACK = () -> {
  };

  private final ObjectMapper objectMapper;
  private final Function<String, DataSegmentChangeHandler> hostToHandler;

  private final ListeningScheduledExecutorService executorService;

  public TestSegmentLoadingHttpClient(
      ObjectMapper objectMapper,
      Function<String, DataSegmentChangeHandler> hostToHandler,
      ScheduledExecutorService executorService
  )
  {
    this.objectMapper = objectMapper;
    this.hostToHandler = hostToHandler;
    this.executorService = MoreExecutors.listeningDecorator(executorService);
  }

  @Override
  public <Intermediate, Final> ListenableFuture<Final> go(
      Request request,
      HttpResponseHandler<Intermediate, Final> handler
  )
  {
    return go(request, handler, null);
  }

  @Override
  public <Intermediate, Final> ListenableFuture<Final> go(
      Request request,
      HttpResponseHandler<Intermediate, Final> handler,
      Duration readTimeout
  )
  {
    if (request.getUrl().toString().contains("/loadCapabilities")) {
      return getCapabilities(handler);
    }
    return executorService.submit(() -> processRequest(request, handler));
  }

  private <Intermediate, Final> Final processRequest(
      Request request,
      HttpResponseHandler<Intermediate, Final> handler
  )
  {
    try {
      // Fail the request if there is no handler for this host
      final DataSegmentChangeHandler changeHandler = hostToHandler
          .apply(request.getUrl().getHost());
      if (changeHandler == null) {
        final HttpResponse failureResponse =
            new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
        failureResponse.setContent(ChannelBuffers.EMPTY_BUFFER);
        handler.handleResponse(failureResponse, NOOP_TRAFFIC_COP);
        return (Final) new ByteArrayInputStream(new byte[0]);
      }

      // Handle change requests and serialize
      final byte[] serializedContent;
      try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
        objectMapper.writeValue(baos, processRequest(request, changeHandler));
        serializedContent = baos.toByteArray();
      }

      // Set response content and status
      final HttpResponse response =
          new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
      response.setContent(ChannelBuffers.EMPTY_BUFFER);
      handler.handleResponse(response, NOOP_TRAFFIC_COP);
      return (Final) new ByteArrayInputStream(serializedContent);
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Processes all the changes in the request.
   */
  private List<DataSegmentChangeResponse> processRequest(
      Request request,
      DataSegmentChangeHandler changeHandler
  ) throws IOException
  {
    final List<DataSegmentChangeRequest> changeRequests = objectMapper.readValue(
        request.getContent().array(),
        new TypeReference<>() {}
    );

    final SegmentLoadingMode loadingMode = getLoadingMode(request);

    return changeRequests
        .stream()
        .map(changeRequest -> processRequest(changeRequest, loadingMode, changeHandler))
        .collect(Collectors.toList());
  }

  private <Intermediate, Final> ListenableFuture<Final> getCapabilities(HttpResponseHandler<Intermediate, Final> handler)
  {
    try {
      // Set response content and status
      final HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
      response.setContent(ChannelBuffers.EMPTY_BUFFER);
      handler.handleResponse(response, NOOP_TRAFFIC_COP);

      // Serialize
      SettableFuture future = SettableFuture.create();
      try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
        objectMapper.writeValue(baos, new SegmentLoadingCapabilities(1, 1));
        future.set(new ByteArrayInputStream(baos.toByteArray()));
      }
      return future;
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Processes each DataSegmentChangeRequest using the handler.
   */
  private DataSegmentChangeResponse processRequest(
      DataSegmentChangeRequest request,
      SegmentLoadingMode loadingMode,
      DataSegmentChangeHandler handler
  )
  {
    SegmentChangeStatus status;
    try {
      request.go(handler, NOOP_CALLBACK);
      status = SegmentChangeStatus.success(loadingMode);
    }
    catch (Exception e) {
      status = SegmentChangeStatus.failed(e.getMessage());
    }

    return new DataSegmentChangeResponse(request, status);
  }

  private static SegmentLoadingMode getLoadingMode(Request request)
  {
    String url = request.getUrl().toString();
    String[] splits = url.split("loadingMode=");

    if (splits.length > 1) {
      return SegmentLoadingMode.valueOf(splits[1]);
    } else {
      return SegmentLoadingMode.NORMAL;
    }
  }
}
