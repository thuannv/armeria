/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.docs;

import static com.linecorp.armeria.common.SerializationFormat.THRIFT_BINARY;
import static com.linecorp.armeria.common.SerializationFormat.THRIFT_COMPACT;
import static com.linecorp.armeria.common.SerializationFormat.THRIFT_TEXT;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.server.AbstractServerTest;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.thrift.ThriftService;
import com.linecorp.armeria.service.test.thrift.cassandra.Cassandra;
import com.linecorp.armeria.service.test.thrift.hbase.Hbase;
import com.linecorp.armeria.service.test.thrift.main.FooService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;
import com.linecorp.armeria.service.test.thrift.main.HelloService.hello_args;

public class DocServiceTest extends AbstractServerTest {

    private static final HelloService.AsyncIface HELLO_SERVICE_HANDLER =
            (name, resultHandler) -> resultHandler.onComplete("Hello " + name);

    private static final hello_args SAMPLE_HELLO = new hello_args().setName("sample user");

    @Override
    protected void configureServer(ServerBuilder sb) {
        final ThriftService helloService = ThriftService.of(HELLO_SERVICE_HANDLER);
        final ThriftService fooService = ThriftService.ofFormats(mock(FooService.AsyncIface.class),
                                                                 THRIFT_COMPACT);
        final ThriftService cassandraService = ThriftService.ofFormats(mock(Cassandra.AsyncIface.class),
                                                                       THRIFT_BINARY);
        final ThriftService cassandraServiceDebug =
                ThriftService.ofFormats(mock(Cassandra.AsyncIface.class), THRIFT_TEXT);
        final ThriftService hbaseService = ThriftService.of(mock(Hbase.AsyncIface.class));

        final VirtualHostBuilder defaultVirtualHost = new VirtualHostBuilder();

        defaultVirtualHost.serviceAt("/hello", helloService);
        defaultVirtualHost.serviceAt("/foo", fooService);
        defaultVirtualHost.serviceAt("/cassandra", cassandraService);
        defaultVirtualHost.serviceAt("/cassandra/debug", cassandraServiceDebug);
        defaultVirtualHost.serviceAt("/hbase", hbaseService);

        defaultVirtualHost.serviceUnder("/docs/", new DocService(Collections.singletonList(SAMPLE_HELLO))
                .decorate(LoggingService::new));

        sb.defaultVirtualHost(defaultVirtualHost.build());
    }

    @Test
    public void testOk() throws Exception {
        final Map<Class<?>, Iterable<EndpointInfo>> serviceMap = new LinkedHashMap<>();
        serviceMap.put(HelloService.class, Collections.singletonList(
                EndpointInfo.of("*", "/hello", THRIFT_BINARY, SerializationFormat.ofThrift())));
        serviceMap.put(FooService.class, Collections.singletonList(
                EndpointInfo.of("*", "/foo", THRIFT_COMPACT, EnumSet.of(THRIFT_COMPACT))));
        serviceMap.put(Cassandra.class, Arrays.asList(
                EndpointInfo.of("*", "/cassandra", THRIFT_BINARY, EnumSet.of(THRIFT_BINARY)),
                EndpointInfo.of("*", "/cassandra/debug", THRIFT_TEXT, EnumSet.of(THRIFT_TEXT))));
        serviceMap.put(Hbase.class, Collections.singletonList(
                EndpointInfo.of("*", "/hbase", THRIFT_BINARY, SerializationFormat.ofThrift())));

        final String expected = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
                Specification.forServiceClasses(serviceMap, ImmutableMap.of(hello_args.class, SAMPLE_HELLO)));

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpGet req = new HttpGet(specificationUri());

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 200 OK"));
                String responseJson = EntityUtils.toString(res.getEntity());
                assertThat(responseJson, is(expected));
            }
        }
    }

    @Test
    public void testMethodNotAllowed() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpPost req = new HttpPost(specificationUri());

            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString(), is("HTTP/1.1 405 Method Not Allowed"));
            }
        }
    }

    private static String specificationUri() {
        return uri("/docs/specification.json");
    }
}
