/**
 * Licensed to the Apache Software Foundation (ASF) under one
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
package org.apache.avro.ipc.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.util.List;

import org.apache.avro.Protocol;
import org.apache.avro.Protocol.Message;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRequestor;
import org.apache.avro.generic.GenericResponder;
import org.apache.avro.ipc.AvroRemoteException;
import org.apache.avro.ipc.HttpServer;
import org.apache.avro.ipc.HttpTransceiver;
import org.apache.avro.ipc.RPCPlugin;
import org.apache.avro.ipc.Responder;
import org.apache.hadoop.conf.Configuration;
import org.junit.Test;

public class TestBasicTracing {
  Protocol protocol = Protocol.parse("" + "{\"protocol\": \"Minimal\", "
      + "\"messages\": { \"m\": {"
      + "   \"request\": [{\"name\": \"x\", \"type\": \"int\"}], "
      + "   \"response\": \"int\"} } }");
  Message message = protocol.getMessages().get("m");

  /** Expects 0 and returns 1. */
  static class TestResponder extends GenericResponder {
    public TestResponder(Protocol local) {
      super(local);
    }

    @Override
    public Object respond(Message message, Object request)
        throws AvroRemoteException {
      assertEquals(0, ((GenericRecord) request).get("x"));
      return 1;
    }
  }

  @Test
  public void testBasicTrace() throws IOException {
    Configuration conf = new Configuration();
    conf.setFloat("traceProbability", (float) 1.0);
    TracePlugin responderPlugin = new TracePlugin(conf);
    TracePlugin requestorPlugin = new TracePlugin(conf);
    
    Responder res = new TestResponder(protocol);
    res.addRPCPlugin(responderPlugin);
    
    HttpServer server = new HttpServer(res, 50000);
    
    HttpTransceiver trans = new HttpTransceiver(
        new URL("http://localhost:50000"));
    
    GenericRequestor r = new GenericRequestor(protocol, trans);
    r.addRPCPlugin(requestorPlugin);
    
    GenericRecord params = new GenericData.Record(protocol.getMessages().get(
    "m").getRequest());
    params.put("x", 0);
    r.request("m", params);
    
    List<Span> responderSpans = responderPlugin.storage.getAllSpans();
    assertEquals(1, responderSpans.size());
    
    List<Span> requestorSpans = requestorPlugin.storage.getAllSpans();
    assertEquals(1, requestorSpans.size());
    
    if ((responderSpans.size() == 1 && requestorSpans.size() == 1)) {
      Span responderSpan = responderSpans.get(0);
      Span requestorSpan = requestorSpans.get(0);
      
      // Check meta propagation     
      assertEquals(requestorSpan.parentSpanID, -1);
      assertEquals(responderSpan.parentSpanID, requestorSpan.spanID);
      assertEquals(responderSpan.traceID, requestorSpan.traceID);
      
      // Check other data
      assertEquals(2, requestorSpan.events.size());
      assertEquals(2, responderSpan.events.size());
      assertTrue("m".equals(requestorSpan.messageName.toString()));
      assertTrue("m".equals(responderSpan.messageName.toString()));
    }
  }
  
  /*
   * Test a more complicated, recursive trace involving four agents and three
   * spans.
   * 
   * Messages are x, y, z which request/return 
   * incrementing int values (shown below).
   * 
   *   |-w-(1)-> |         |
   *   |         |-x-(2)-> | C
   *   |         | <-x-(3)-|        
   *   |         |    
   * A |       B |    
   *   |         |         |
   *   |         |-x-(4)-> | 
   *   |         | <-x-(5)-| D   
   *   |         |         | 
   *   |<-w-(6)- |         |
   *   
   *   Listening ports are B: 21005
   *                       C: 21006  
   *                       D: 21007
   */
  
  Protocol advancedProtocol = Protocol.parse("{\"protocol\": \"Advanced\", "
      + "\"messages\": { " 
      +	"\"w\": { \"request\": [{\"name\": \"req\", \"type\": \"int\"}], "
      + "   \"response\": \"int\"},"
      + "\"x\": { \"request\": [{\"name\": \"req\", \"type\": \"int\"}], "
      + "   \"response\": \"int\"},"
      + "\"y\": { \"request\": [{\"name\": \"req\", \"type\": \"int\"}], "
      + "   \"response\": \"int\"}"
      + " } }");

  /** Middle Responder */
  static class RecursingResponder extends GenericResponder {
    HttpTransceiver transC;
    HttpTransceiver transD;
    GenericRequestor reqC;
    GenericRequestor reqD;
    Protocol protocol;
    
    public RecursingResponder(Protocol local, RPCPlugin plugin) 
    throws Exception {
      super(local);
      transC = new HttpTransceiver(
          new URL("http://localhost:21006"));
      transD = new HttpTransceiver(
          new URL("http://localhost:21007"));
      reqC = new GenericRequestor(local, transC);
      reqC.addRPCPlugin(plugin);
      reqD = new GenericRequestor(local, transD);
      reqD.addRPCPlugin(plugin);
      
      protocol = local; 
    }

    @Override
    public Object respond(Message message, Object request)
        throws IOException {
       assertTrue("w".equals(message.getName()));
       GenericRecord inParams = (GenericRecord)request;
       Integer currentCount = (Integer) inParams.get("req");
       assertTrue(currentCount.equals(1));
       
       GenericRecord paramsC = new GenericData.Record(
           protocol.getMessages().get("x").getRequest());
       paramsC.put("req", currentCount + 1);
       Integer returnC = (Integer) reqC.request("x", paramsC);
       assertTrue(returnC.equals(currentCount + 2));
       
       GenericRecord paramsD = new GenericData.Record(
           protocol.getMessages().get("x").getRequest());
       paramsD.put("req", currentCount + 3);
       Integer returnD = (Integer) reqD.request("x", paramsD);
       assertTrue(returnD.equals(currentCount + 4));
       
       return currentCount + 5;
    }
  }
  
  /** Endpoint responder */
  static class EndpointResponder extends GenericResponder {
    public EndpointResponder(Protocol local) {
      super(local);
    }

    @Override
    public Object respond(Message message, Object request)
        throws AvroRemoteException {
      GenericRecord inParams = (GenericRecord)request;
      Integer currentCount = (Integer) inParams.get("req");
      
      return currentCount + 1;
    }
  }

  @Test
  public void testRecursingTrace() throws Exception {
    Configuration conf = new Configuration();
    conf.setFloat("traceProbability", (float) 1.0);
    TracePlugin aPlugin = new TracePlugin(conf);
    TracePlugin bPlugin = new TracePlugin(new Configuration());
    TracePlugin cPlugin = new TracePlugin(new Configuration());    
    TracePlugin dPlugin = new TracePlugin(new Configuration());
    
    // Responders
    Responder bRes = new RecursingResponder(advancedProtocol, bPlugin);
    bRes.addRPCPlugin(bPlugin);
    HttpServer server1 = new HttpServer(bRes, 21005);

    Responder cRes = new EndpointResponder(advancedProtocol);
    cRes.addRPCPlugin(cPlugin);
    HttpServer server2 = new HttpServer(cRes, 21006);
    
    Responder dRes = new EndpointResponder(advancedProtocol);
    dRes.addRPCPlugin(dPlugin);
    HttpServer server3 = new HttpServer(dRes, 21007);
    
    // Root requestor
    HttpTransceiver trans = new HttpTransceiver(
        new URL("http://localhost:21005"));
    
    GenericRequestor r = new GenericRequestor(advancedProtocol, trans);
    r.addRPCPlugin(aPlugin);
    
    GenericRecord params = new GenericData.Record(
        advancedProtocol.getMessages().get("w").getRequest());
    params.put("req", 1);
    r.request("w", params);
    
    // Verify counts
    assertEquals(1, aPlugin.storage.getAllSpans().size());
    assertEquals(3, bPlugin.storage.getAllSpans().size());
    assertEquals(1, cPlugin.storage.getAllSpans().size());
    assertEquals(1, dPlugin.storage.getAllSpans().size());
    
    Long traceID = aPlugin.storage.getAllSpans().get(0).traceID;
    
    // Verify event counts and trace ID propagation
    for (Span s: aPlugin.storage.getAllSpans()) {
      assertEquals(2, s.events.size());
      assertEquals(traceID.longValue(), s.traceID);
    }
    for (Span s: bPlugin.storage.getAllSpans()) {
      assertEquals(2, s.events.size());
      assertEquals(traceID.longValue(), s.traceID);
    }
    for (Span s: cPlugin.storage.getAllSpans()) {
      assertEquals(2, s.events.size());
      assertEquals(traceID.longValue(), s.traceID);
    }
    for (Span s: dPlugin.storage.getAllSpans()) {
      assertEquals(2, s.events.size());
      assertEquals(traceID.longValue(), s.traceID);
    }
    
    // Verify span propagation.
    Long firstSpanID = aPlugin.storage.getAllSpans().get(0).spanID;
    Long secondSpanID = cPlugin.storage.getAllSpans().get(0).spanID;
    Long thirdSpanID = dPlugin.storage.getAllSpans().get(0).spanID;
    
    boolean firstFound = false, secondFound = false, thirdFound = false;
    for (Span s: bPlugin.storage.getAllSpans()) {
      if (s.spanID == firstSpanID) {
        firstFound = true;
      }
      else if (s.spanID == secondSpanID) {
        secondFound = true;
      }
      else if (s.spanID == thirdSpanID) {
        thirdFound = true;
      }
    }
    assertTrue(firstFound);
    assertTrue(secondFound);
    assertTrue(thirdFound);
  }
}
