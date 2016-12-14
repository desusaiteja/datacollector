/**
 * Copyright 2016 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.processor.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.Processor;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.config.DataFormat;
import com.streamsets.pipeline.lib.http.HttpMethod;
import com.streamsets.pipeline.sdk.ProcessorRunner;
import com.streamsets.pipeline.sdk.RecordCreator;
import com.streamsets.pipeline.sdk.StageRunner;
import com.streamsets.pipeline.stage.util.http.HttpStageTestUtil;
import com.streamsets.pipeline.stage.util.http.HttpStageUtil;
import com.streamsets.testing.SingleForkNoReuseTest;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.glassfish.jersey.test.TestProperties;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@Category(SingleForkNoReuseTest.class)
public class HttpProcessorIT extends JerseyTest {

  private static String getBody(String path) {
    try {
      return Resources.toString(Resources.getResource(path), Charsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException("Failed to read test resource: " + path);
    }
  }

  @Path("/test/get")
  @Produces(MediaType.APPLICATION_JSON)
  public static class TestGet {
    @GET
    public Response get() {
      return Response.ok(getBody("http/get_response.json"))
          .header("x-test-header", "StreamSets")
          .header("x-list-header", ImmutableList.of("a", "b"))
          .build();
    }
  }

  public static class TestInput {
    public TestInput() {};

    public TestInput(String hello) {
      this.hello = hello;
    }

    @JsonProperty("hello")
    public String hello;
  }

  @Path("/test/put")
  @Consumes(MediaType.APPLICATION_JSON)
  public static class TestPut {
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response put(TestInput input) {
      return Response.ok(
          "{\"hello\":\"" + input.hello + "\"}"
      ).build();
    }
  }

  @Path("/test/xml/get")
  @Produces(MediaType.APPLICATION_XML)
  public static class TestXmlGet {
    @GET
    public Response get() {
      return Response.ok("<r><e>Hello</e><e>Bye</e></r>").build();
    }
  }

  @Override
  protected Application configure() {
    forceSet(TestProperties.CONTAINER_PORT, "0");
    return new ResourceConfig(
        Sets.newHashSet(
            TestGet.class,
            TestPut.class,
            HttpStageTestUtil.TestPostCustomType.class,
            TestXmlGet.class
        )
    );
  }

  @Test
  public void testHttpGetDefaultHeaderOutput() throws Exception {
    HttpProcessorConfig conf = new HttpProcessorConfig();
    conf.httpMethod = HttpMethod.GET;
    conf.outputField = "/output";
    conf.dataFormat = DataFormat.TEXT;
    conf.resourceUrl = getBaseUri() + "test/get";
    conf.headerOutputLocation = HeaderOutputLocation.HEADER;

    Record record = RecordCreator.create();
    record.set("/", Field.create(new HashMap<String, Field>()));

    List<Record> records = ImmutableList.of(record);
    Processor processor = new HttpProcessor(conf);
    ProcessorRunner runner = new ProcessorRunner.Builder(HttpDProcessor.class, processor)
        .addOutputLane("lane")
        .build();
    runner.runInit();
    try {
      StageRunner.Output output = runner.runProcess(records);
      List<Record> outputRecords = output.getRecords().get("lane");
      assertTrue(runner.getErrorRecords().isEmpty());
      assertEquals(1, outputRecords.size());
      assertTrue(outputRecords.get(0).has("/output"));
      assertEquals("{\"hello\":\"world!\"}", outputRecords.get(0).get("/output").getValueAsString());
      assertEquals("StreamSets", outputRecords.get(0).getHeader().getAttribute("x-test-header"));
      assertEquals("[a, b]", outputRecords.get(0).getHeader().getAttribute("x-list-header"));
    } finally {
      runner.runDestroy();
    }
  }

  @Test
  public void testHttpGetPrefixedHeaderOutput() throws Exception {
    HttpProcessorConfig conf = new HttpProcessorConfig();
    conf.httpMethod = HttpMethod.GET;
    conf.outputField = "/output";
    conf.dataFormat = DataFormat.TEXT;
    conf.resourceUrl = getBaseUri() + "test/get";
    conf.headerOutputLocation = HeaderOutputLocation.HEADER;
    conf.headerAttributePrefix = "test-prefix-";

    Record record = RecordCreator.create();
    record.set("/", Field.create(new HashMap<String, Field>()));

    List<Record> records = ImmutableList.of(record);
    Processor processor = new HttpProcessor(conf);
    ProcessorRunner runner = new ProcessorRunner.Builder(HttpDProcessor.class, processor)
        .addOutputLane("lane")
        .build();
    runner.runInit();
    try {
      StageRunner.Output output = runner.runProcess(records);
      List<Record> outputRecords = output.getRecords().get("lane");
      assertTrue(runner.getErrorRecords().isEmpty());
      assertEquals(1, outputRecords.size());
      assertTrue(outputRecords.get(0).has("/output"));
      assertEquals("{\"hello\":\"world!\"}", outputRecords.get(0).get("/output").getValueAsString());
      assertEquals(
          "StreamSets",
          outputRecords.get(0).getHeader().getAttribute(conf.headerAttributePrefix + "x-test-header")
      );
      assertEquals(
          "[a, b]",
          outputRecords.get(0).getHeader().getAttribute(conf.headerAttributePrefix + "x-list-header")
      );
    } finally {
      runner.runDestroy();
    }
  }

  @Test
  public void testHttpGetHeaderFieldOutput() throws Exception {
    HttpProcessorConfig conf = new HttpProcessorConfig();
    conf.httpMethod = HttpMethod.GET;
    conf.outputField = "/output";
    conf.dataFormat = DataFormat.TEXT;
    conf.resourceUrl = getBaseUri() + "test/get";
    conf.headerOutputLocation = HeaderOutputLocation.FIELD;
    conf.headerOutputField = "/headers";

    Record record = RecordCreator.create();
    record.set("/", Field.create(new HashMap<String, Field>()));

    List<Record> records = ImmutableList.of(record);
    Processor processor = new HttpProcessor(conf);
    ProcessorRunner runner = new ProcessorRunner.Builder(HttpDProcessor.class, processor)
        .addOutputLane("lane")
        .build();
    runner.runInit();
    try {
      StageRunner.Output output = runner.runProcess(records);
      List<Record> outputRecords = output.getRecords().get("lane");
      assertTrue(runner.getErrorRecords().isEmpty());
      assertEquals(1, outputRecords.size());
      assertTrue(outputRecords.get(0).has("/output"));
      assertEquals("{\"hello\":\"world!\"}", outputRecords.get(0).get("/output").getValueAsString());
      assertEquals(
          "StreamSets",
          outputRecords.get(0).get(conf.headerOutputField).getValueAsMap().get("x-test-header").getValueAsString()
      );
      assertEquals(
          "[a, b]",
          outputRecords.get(0).get(conf.headerOutputField).getValueAsMap().get("x-list-header").getValueAsString()
      );
    } finally {
      runner.runDestroy();
    }
  }

  @Test
  public void testHttpGetJson() throws Exception {
    HttpProcessorConfig conf = new HttpProcessorConfig();
    conf.httpMethod = HttpMethod.GET;
    conf.outputField = "/output";
    conf.dataFormat = DataFormat.JSON;
    conf.resourceUrl = getBaseUri() + "test/get";

    Record record = RecordCreator.create();
    record.set("/", Field.create(new HashMap<String, Field>()));

    List<Record> records = ImmutableList.of(record);
    Processor processor = new HttpProcessor(conf);
    ProcessorRunner runner = new ProcessorRunner.Builder(HttpDProcessor.class, processor)
        .addOutputLane("lane")
        .build();
    runner.runInit();
    try {
      StageRunner.Output output = runner.runProcess(records);
      List<Record> outputRecords = output.getRecords().get("lane");
      assertTrue(runner.getErrorRecords().isEmpty());
      assertEquals(1, outputRecords.size());
      assertTrue(outputRecords.get(0).has("/output"));
      Map<String, Field> outputMap = outputRecords.get(0).get("/output").getValueAsMap();
      assertTrue(!outputMap.isEmpty());
      assertTrue(outputMap.containsKey("hello"));
      assertEquals("world!", outputMap.get("hello").getValueAsString());

    } finally {
      runner.runDestroy();
    }
  }

  @Test
  public void testHttpPutJson() throws Exception {
    HttpProcessorConfig conf = new HttpProcessorConfig();
    conf.httpMethod = HttpMethod.POST;
    conf.outputField = "/output";
    conf.dataFormat = DataFormat.JSON;
    conf.resourceUrl = getBaseUri() + "test/put";
    conf.headers.put(HttpStageUtil.CONTENT_TYPE_HEADER, "application/json");
    conf.requestBody = "{\"hello\":\"world!\"}";

    Record record = RecordCreator.create();
    record.set("/", Field.create(new HashMap<String, Field>()));

    List<Record> records = ImmutableList.of(record);
    Processor processor = new HttpProcessor(conf);
    ProcessorRunner runner = new ProcessorRunner.Builder(HttpDProcessor.class, processor)
        .addOutputLane("lane")
        .build();
    runner.runInit();
    try {
      StageRunner.Output output = runner.runProcess(records);
      List<Record> outputRecords = output.getRecords().get("lane");
      assertTrue(runner.getErrorRecords().isEmpty());
      assertEquals(1, outputRecords.size());
      assertTrue(outputRecords.get(0).has("/output"));
      Map<String, Field> outputMap = outputRecords.get(0).get("/output").getValueAsMap();
      assertTrue(!outputMap.isEmpty());
      assertTrue(outputMap.containsKey("hello"));
      assertEquals("world!", outputMap.get("hello").getValueAsString());

    } finally {
      runner.runDestroy();
    }
  }

  @Test
  public void testHttpPostDifferentTypes() throws Exception {
    final Random random = new Random();

    String fallbackContentType = "application/default";

    for (Map.Entry<String, String> requestEntry : HttpStageTestUtil.CONTENT_TYPE_TO_BODY.entrySet()) {

      String expectedContentType = requestEntry.getKey();

      HttpProcessorConfig conf = new HttpProcessorConfig();
      conf.httpMethod = HttpMethod.POST;
      conf.outputField = "/output";
      conf.dataFormat = DataFormat.JSON;
      conf.resourceUrl = getBaseUri() + "test/postCustomType";
      conf.defaultRequestContentType = fallbackContentType;

      if (StringUtils.isBlank(expectedContentType)) {
        expectedContentType = fallbackContentType;
      } else {
        String contentTypeHeader = HttpStageUtil.CONTENT_TYPE_HEADER;
        String header = HttpStageTestUtil.randomizeCapitalization(random, contentTypeHeader);
        conf.headers.put(header.toString(), expectedContentType);
      }

      conf.requestBody = requestEntry.getValue();

      Record record = RecordCreator.create();
      record.set("/", Field.create(new HashMap<String, Field>()));

      List<Record> records = ImmutableList.of(record);
      Processor processor = new HttpProcessor(conf);
      ProcessorRunner runner = new ProcessorRunner.Builder(HttpDProcessor.class, processor)
          .addOutputLane("lane")
          .build();
      runner.runInit();
      try {
        StageRunner.Output output = runner.runProcess(records);
        List<Record> outputRecords = output.getRecords().get("lane");
        assertTrue(runner.getErrorRecords().isEmpty());
        assertEquals(1, outputRecords.size());
        assertTrue(outputRecords.get(0).has("/output"));
        Map<String, Field> outputMap = outputRecords.get(0).get("/output").getValueAsMap();
        assertTrue(!outputMap.isEmpty());
        assertTrue(outputMap.containsKey(HttpStageUtil.CONTENT_TYPE_HEADER));
        assertEquals(expectedContentType, outputMap.get(HttpStageUtil.CONTENT_TYPE_HEADER).getValueAsString());
        assertTrue(outputMap.containsKey("Content"));
        assertEquals(requestEntry.getValue(), outputMap.get("Content").getValueAsString());

      } finally {
        runner.runDestroy();
      }

    }
  }

  @Test
  public void testHttpGetXml() throws Exception {
    HttpProcessorConfig conf = new HttpProcessorConfig();
    conf.httpMethod = HttpMethod.GET;
    conf.outputField = "/output";
    conf.dataFormat = DataFormat.XML;
    conf.resourceUrl = getBaseUri() + "test/xml/get";

    Record record = RecordCreator.create();
    record.set("/", Field.create(new HashMap<String, Field>()));

    List<Record> records = ImmutableList.of(record);
    Processor processor = new HttpProcessor(conf);
    ProcessorRunner runner = new ProcessorRunner.Builder(HttpDProcessor.class, processor)
        .addOutputLane("lane")
        .build();
    runner.runInit();
    try {
      StageRunner.Output output = runner.runProcess(records);
      List<Record> outputRecords = output.getRecords().get("lane");
      assertTrue(runner.getErrorRecords().isEmpty());
      assertEquals(1, outputRecords.size());
      assertTrue(outputRecords.get(0).has("/output"));
      Map<String, Field> outputField = outputRecords.get(0).get("/output").getValueAsMap();
      List<Field> xmlFields = outputField.get("e").getValueAsList();
      assertEquals("Hello", xmlFields.get(0).getValueAsMap().get("value").getValueAsString());
      assertEquals("Bye", xmlFields.get(1).getValueAsMap().get("value").getValueAsString());
    } finally {
      runner.runDestroy();
    }
  }
}
