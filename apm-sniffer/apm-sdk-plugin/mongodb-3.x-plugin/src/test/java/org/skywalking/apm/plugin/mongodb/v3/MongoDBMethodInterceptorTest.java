package org.skywalking.apm.plugin.mongodb.v3;

import com.mongodb.MongoNamespace;
import com.mongodb.operation.FindOperation;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.codecs.Decoder;
import org.hamcrest.CoreMatchers;
import org.hamcrest.MatcherAssert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.powermock.api.mockito.PowerMockito;
import org.skywalking.apm.agent.core.boot.ServiceManager;
import org.skywalking.apm.agent.core.conf.Config;
import org.skywalking.apm.agent.core.context.TracingContext;
import org.skywalking.apm.agent.core.plugin.interceptor.EnhancedClassInstanceContext;
import org.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodInvokeContext;
import org.skywalking.apm.sniffer.mock.context.MockTracerContextListener;
import org.skywalking.apm.sniffer.mock.context.SegmentAssert;
import org.skywalking.apm.trace.LogData;
import org.skywalking.apm.trace.Span;
import org.skywalking.apm.trace.TraceSegment;
import org.skywalking.apm.trace.tag.Tags;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class MongoDBMethodInterceptorTest {

    private MongoDBMethodInterceptor interceptor;
    private MockTracerContextListener mockTracerContextListener;

    @Mock
    private EnhancedClassInstanceContext classInstanceContext;
    @Mock
    private InstanceMethodInvokeContext methodInvokeContext;

    @SuppressWarnings( {"rawtypes", "unchecked"})
    @Before
    public void setUp() throws Exception {
        ServiceManager.INSTANCE.boot();

        interceptor = new MongoDBMethodInterceptor();
        mockTracerContextListener = new MockTracerContextListener();

        TracingContext.ListenerManager.add(mockTracerContextListener);

        Config.Plugin.MongoDB.TRACE_PARAM = true;

        when(classInstanceContext.get(MongoDBMethodInterceptor.MONGODB_HOST, String.class)).thenReturn("127.0.0.1");
        when(classInstanceContext.get(MongoDBMethodInterceptor.MONGODB_PORT)).thenReturn(27017);
        when(methodInvokeContext.methodName()).thenReturn("find");

        BsonDocument document = new BsonDocument();
        document.append("name", new BsonString("by"));
        MongoNamespace mongoNamespace = new MongoNamespace("test.user");
        Decoder decoder = PowerMockito.mock(Decoder.class);
        FindOperation findOperation = new FindOperation(mongoNamespace, decoder);
        findOperation.filter(document);

        when(methodInvokeContext.allArguments()).thenReturn(new Object[] {findOperation});
    }

    @Test
    public void testIntercept() {
        interceptor.beforeMethod(classInstanceContext, methodInvokeContext, null);
        interceptor.afterMethod(classInstanceContext, methodInvokeContext, null);

        mockTracerContextListener.assertSize(1);
        mockTracerContextListener.assertTraceSegment(0, new SegmentAssert() {
            @Override
            public void call(TraceSegment traceSegment) {
                assertThat(traceSegment.getSpans().size(), is(1));
                Span span = traceSegment.getSpans().get(0);
                assertRedisSpan(span);
            }
        });
    }

    @Test
    public void testInterceptWithException() {
        interceptor.beforeMethod(classInstanceContext, methodInvokeContext, null);
        interceptor.handleMethodException(new RuntimeException(), classInstanceContext, methodInvokeContext);
        interceptor.afterMethod(classInstanceContext, methodInvokeContext, null);

        mockTracerContextListener.assertSize(1);
        mockTracerContextListener.assertTraceSegment(0, new SegmentAssert() {
            @Override
            public void call(TraceSegment traceSegment) {
                assertThat(traceSegment.getSpans().size(), is(1));
                Span span = traceSegment.getSpans().get(0);
                assertRedisSpan(span);
                assertThat(span.getLogs().size(), is(1));
                assertLogData(span.getLogs().get(0));
            }
        });
    }

    private void assertLogData(LogData logData) {
        MatcherAssert.assertThat(logData.getFields().size(), is(4));
        MatcherAssert.assertThat(logData.getFields().get("event"), CoreMatchers.<Object>is("error"));
        assertEquals(logData.getFields().get("error.kind"), RuntimeException.class.getName());
        assertNull(logData.getFields().get("message"));
    }

    private void assertRedisSpan(Span span) {
        assertThat(span.getOperationName(), is("MongoDB/FindOperation"));
        assertThat(Tags.PEER_HOST.get(span), is("127.0.0.1"));
        assertThat(Tags.PEER_PORT.get(span), is(27017));
        assertThat(Tags.COMPONENT.get(span), is("MongoDB"));
        assertThat(Tags.DB_STATEMENT.get(span), is("FindOperation { \"name\" : \"by\" }"));
        assertThat(Tags.DB_TYPE.get(span), is("MongoDB"));
        assertTrue(Tags.SPAN_LAYER.isDB(span));
    }

    @After
    public void tearDown() throws Exception {
        TracingContext.ListenerManager.remove(mockTracerContextListener);
    }

}
