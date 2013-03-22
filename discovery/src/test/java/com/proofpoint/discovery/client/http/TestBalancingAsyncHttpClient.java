package com.proofpoint.discovery.client.http;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AbstractFuture;
import com.proofpoint.discovery.client.HttpServiceSelector;
import com.proofpoint.discovery.client.ServiceUnavailableException;
import com.proofpoint.http.client.AsyncHttpClient;
import com.proofpoint.http.client.BodyGenerator;
import com.proofpoint.http.client.Request;
import com.proofpoint.http.client.RequestStats;
import com.proofpoint.http.client.Response;
import com.proofpoint.http.client.ResponseHandler;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.ConnectException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.google.common.base.Preconditions.checkArgument;
import static com.proofpoint.http.client.Request.Builder.preparePut;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class TestBalancingAsyncHttpClient
{
    private HttpServiceSelector serviceSelector;
    private BalancingAsyncHttpClient balancingAsyncHttpClient;
    private BodyGenerator bodyGenerator;
    private Request request;
    private TestingAsyncHttpClient httpClient;
    private Response response;

    @BeforeMethod
    protected void setUp()
            throws Exception
    {
        serviceSelector = mock(HttpServiceSelector.class);
        when(serviceSelector.selectHttpService()).thenReturn(ImmutableList.of(
                URI.create("http://s1.example.com"),
                URI.create("http://s2.example.com")
        ));
        httpClient = new TestingAsyncHttpClient("PUT");
        balancingAsyncHttpClient = new BalancingAsyncHttpClient(serviceSelector, httpClient,
                new BalancingHttpClientConfig().setMaxRetries(2));
        bodyGenerator = mock(BodyGenerator.class);
        request = preparePut().setUri(URI.create("/v1/service")).setBodyGenerator(bodyGenerator).build();
        response = mock(Response.class);
        when(response.getStatusCode()).thenReturn(204);
    }

    @Test
    public void testSuccessfulQuery()
            throws Exception
    {
        httpClient.expectCall("http://s1.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response))).thenReturn("test response");

        String returnValue = balancingAsyncHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceSelector).selectHttpService();
        verify(response).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceSelector, bodyGenerator, response, responseHandler);
    }

    @Test
    public void testHandlerException()
            throws Exception
    {
        httpClient.expectCall("http://s1.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        Exception testException = new Exception("test exception");
        when(responseHandler.handle(any(Request.class), same(response))).thenThrow(testException);

        try {
            String returnValue = balancingAsyncHttpClient.execute(request, responseHandler);
            fail("expected exception, got " + returnValue);
        }
        catch (Exception e) {
            assertSame(e, testException);
        }

        httpClient.assertDone();

        verify(serviceSelector).selectHttpService();
        verify(response).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceSelector, bodyGenerator, response, responseHandler);
    }

    @Test
    public void testRetryOnHttpClientException()
            throws Exception
    {
        httpClient.expectCall("http://s1.example.com/v1/service", new ConnectException());
        httpClient.expectCall("http://s2.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response))).thenReturn("test response");

        String returnValue = balancingAsyncHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceSelector).selectHttpService();
        verify(response).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceSelector, bodyGenerator, response, responseHandler);
    }

    @Test
    public void testRetryOn408Status()
            throws Exception
    {
        Response response408 = mock(Response.class);
        when(response408.getStatusCode()).thenReturn(408);

        httpClient.expectCall("http://s1.example.com/v1/service", response408);
        httpClient.expectCall("http://s2.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response))).thenReturn("test response");

        String returnValue = balancingAsyncHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceSelector).selectHttpService();
        verify(response408).getStatusCode();
        verify(response).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceSelector, bodyGenerator, response, responseHandler, response408);
    }

    @Test
    public void testRetryOn500Status()
            throws Exception
    {
        Response response500 = mock(Response.class);
        when(response500.getStatusCode()).thenReturn(500);

        httpClient.expectCall("http://s1.example.com/v1/service", response500);
        httpClient.expectCall("http://s2.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response))).thenReturn("test response");

        String returnValue = balancingAsyncHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceSelector).selectHttpService();
        verify(response500).getStatusCode();
        verify(response).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceSelector, bodyGenerator, response, responseHandler, response500);
    }

    @Test
    public void testRetryOn502Status()
            throws Exception
    {
        Response response502 = mock(Response.class);
        when(response502.getStatusCode()).thenReturn(502);

        httpClient.expectCall("http://s1.example.com/v1/service", response502);
        httpClient.expectCall("http://s2.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response))).thenReturn("test response");

        String returnValue = balancingAsyncHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceSelector).selectHttpService();
        verify(response502).getStatusCode();
        verify(response).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceSelector, bodyGenerator, response, responseHandler, response502);
    }

    @Test
    public void testRetryOn503Status()
            throws Exception
    {
        Response response503 = mock(Response.class);
        when(response503.getStatusCode()).thenReturn(503);

        httpClient.expectCall("http://s1.example.com/v1/service", response503);
        httpClient.expectCall("http://s2.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response))).thenReturn("test response");

        String returnValue = balancingAsyncHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceSelector).selectHttpService();
        verify(response503).getStatusCode();
        verify(response).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceSelector, bodyGenerator, response, responseHandler, response503);
    }

    @Test
    public void testRetryOn504Status()
            throws Exception
    {
        Response response504 = mock(Response.class);
        when(response504.getStatusCode()).thenReturn(504);

        httpClient.expectCall("http://s1.example.com/v1/service", response504);
        httpClient.expectCall("http://s2.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response))).thenReturn("test response");

        String returnValue = balancingAsyncHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceSelector).selectHttpService();
        verify(response504).getStatusCode();
        verify(response).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceSelector, bodyGenerator, response, responseHandler, response504);
    }

    @Test
    public void testRetryWraparound503()
            throws Exception
    {
        Response response503 = mock(Response.class);
        when(response503.getStatusCode()).thenReturn(503);

        httpClient.expectCall("http://s1.example.com/v1/service", new ConnectException());
        httpClient.expectCall("http://s2.example.com/v1/service", response503);
        httpClient.expectCall("http://s1.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response))).thenReturn("test response");

        String returnValue = balancingAsyncHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceSelector).selectHttpService();
        verify(response503).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceSelector, bodyGenerator, response, responseHandler, response503);
    }

    @Test
    public void testRetryWraparoundException()
            throws Exception
    {
        Response response503 = mock(Response.class);
        when(response503.getStatusCode()).thenReturn(503);

        httpClient.expectCall("http://s1.example.com/v1/service", response503);
        httpClient.expectCall("http://s2.example.com/v1/service", new ConnectException());
        httpClient.expectCall("http://s1.example.com/v1/service", response);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response))).thenReturn("test response");

        String returnValue = balancingAsyncHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceSelector).selectHttpService();
        verify(response503).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response));
        verifyNoMoreInteractions(serviceSelector, bodyGenerator, response, responseHandler, response503);
    }

    @Test
    public void testGiveUpOnHttpClientException()
            throws Exception
    {
        Response response503 = mock(Response.class);
        when(response503.getStatusCode()).thenReturn(503);
        ConnectException connectException = new ConnectException();

        httpClient.expectCall("http://s1.example.com/v1/service", new ConnectException());
        httpClient.expectCall("http://s2.example.com/v1/service", response503);
        httpClient.expectCall("http://s1.example.com/v1/service", connectException);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        Exception testException = new Exception("test exception");
        when(responseHandler.handleException(any(Request.class), same(connectException))).thenReturn(testException);

        try {
            String returnValue = balancingAsyncHttpClient.execute(request, responseHandler);
            fail("expected exception, got " + returnValue);
        }
        catch (Exception e) {
            assertSame(e, testException);
        }

        httpClient.assertDone();

        verify(serviceSelector).selectHttpService();
        verify(response503).getStatusCode();
        verify(responseHandler).handleException(any(Request.class), same(connectException));
        verifyNoMoreInteractions(serviceSelector, bodyGenerator, response, responseHandler, response503);
    }

    @Test
    public void testGiveUpOn408Status()
            throws Exception
    {
        Response response503 = mock(Response.class);
        when(response503.getStatusCode()).thenReturn(503);
        Response response408 = mock(Response.class);
        when(response408.getStatusCode()).thenReturn(408);

        httpClient.expectCall("http://s1.example.com/v1/service", new ConnectException());
        httpClient.expectCall("http://s2.example.com/v1/service", response503);
        httpClient.expectCall("http://s1.example.com/v1/service", response408);

        ResponseHandler<String, Exception> responseHandler = mock(ResponseHandler.class);
        when(responseHandler.handle(any(Request.class), same(response408))).thenReturn("test response");

        String returnValue = balancingAsyncHttpClient.execute(request, responseHandler);
        assertEquals(returnValue, "test response", "return value from .execute()");

        httpClient.assertDone();

        verify(serviceSelector).selectHttpService();
        verify(response503).getStatusCode();
        verify(responseHandler).handle(any(Request.class), same(response408));
        verifyNoMoreInteractions(serviceSelector, bodyGenerator, response, responseHandler, response503, response408);
    }

    @Test(expectedExceptions = ServiceUnavailableException.class,
            expectedExceptionsMessageRegExp = "Service type=\\[test-type], pool=\\[test-pool] is not available")
    public void testNoServers()
            throws Exception
    {
        serviceSelector = mock(HttpServiceSelector.class);
        when(serviceSelector.selectHttpService()).thenReturn(ImmutableList.<URI>of());
        when(serviceSelector.getType()).thenReturn("test-type");
        when(serviceSelector.getPool()).thenReturn("test-pool");

        balancingAsyncHttpClient = new BalancingAsyncHttpClient(serviceSelector, httpClient,
                new BalancingHttpClientConfig().setMaxRetries(2));

        balancingAsyncHttpClient.execute(request, mock(ResponseHandler.class));
    }

    @Test
    public void testGetStats()
    {
        RequestStats requestStats = new RequestStats();
        AsyncHttpClient mockClient = mock(AsyncHttpClient.class);
        when(mockClient.getStats()).thenReturn(requestStats);

        balancingAsyncHttpClient = new BalancingAsyncHttpClient(serviceSelector, mockClient, new BalancingHttpClientConfig());
        assertSame(balancingAsyncHttpClient.getStats(), requestStats);

        verify(mockClient).getStats();
        verifyNoMoreInteractions(mockClient);
    }

    @Test
    public void testClose()
    {
        AsyncHttpClient mockClient = mock(AsyncHttpClient.class);

        balancingAsyncHttpClient = new BalancingAsyncHttpClient(serviceSelector, mockClient, new BalancingHttpClientConfig());
        balancingAsyncHttpClient.close();

        verify(mockClient).close();
        verifyNoMoreInteractions(mockClient);
    }

    // TODO tests for interruption and cancellation

    class TestingAsyncHttpClient implements AsyncHttpClient
    {

        private String method;
        private List<URI> uris = new ArrayList<>();
        private List<Object> responses = new ArrayList<>();

        TestingAsyncHttpClient(String method)
        {
            this.method = method;
            checkArgument(uris.size() == responses.size(), "uris same size as responses");
        }

        TestingAsyncHttpClient expectCall(String uri, Response response)
        {
            return expectCall(URI.create(uri), response);
        }

        TestingAsyncHttpClient expectCall(String uri, Exception exception)
        {
            return expectCall(URI.create(uri), exception);
        }

        private TestingAsyncHttpClient expectCall(URI uri, Object response)
        {
            uris.add(uri);
            responses.add(response);
            return this;
        }

        void assertDone()
        {
            assertEquals(uris.size(), 0, "all expected calls made");
        }

        @Override
        public <T, E extends Exception> AsyncHttpResponseFuture<T, E> executeAsync(Request request, ResponseHandler<T, E> responseHandler)
        {
            assertTrue(uris.size() > 0, "call was expected");
            assertEquals(request.getMethod(), method, "request method");
            assertEquals(request.getUri(), uris.remove(0), "request uri");
            assertEquals(request.getBodyGenerator(), bodyGenerator, "request body generator");

            Object response = responses.remove(0);
            // TODO: defer availability of return values ?
            if (response instanceof Exception) {
                return new ImmediateFailedAsyncHttpFuture<>(responseHandler.handleException(request, (Exception) response));
            }
            try {
                return new ImmediateAsyncHttpFuture<>(responseHandler.handle(request, (Response) response));
            }
            catch (Exception e) {
                return new ImmediateFailedAsyncHttpFuture<>((E) e);
            }
        }

        @Override
        public <T, E extends Exception> T execute(Request request, ResponseHandler<T, E> responseHandler)
                throws E
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public RequestStats getStats()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close()
        {
            throw new UnsupportedOperationException();
        }

        private class ImmediateAsyncHttpFuture<T, E extends Exception>
                extends AbstractFuture<T>
                implements AsyncHttpResponseFuture<T, E>
        {
            public ImmediateAsyncHttpFuture(T value)
            {
                set(value);
            }

            @Override
            public String getState()
            {
                throw new UnsupportedOperationException();
            }

            @Override
            public T checkedGet()
                    throws E
            {
                try {
                    return get();
                }
                catch (InterruptedException | ExecutionException ignored) {
                    throw new UnsupportedOperationException();
                }
            }

            @Override
            public T checkedGet(long timeout, TimeUnit unit)
                    throws TimeoutException, E
            {
                return checkedGet();
            }
        }

        private class ImmediateFailedAsyncHttpFuture<T, E extends Exception>
                extends AbstractFuture<T>
                implements AsyncHttpResponseFuture<T, E>
        {

            private final E exception;

            public ImmediateFailedAsyncHttpFuture(E exception)
            {
                this.exception = exception;
                setException(exception);
            }

            @Override
            public String getState()
            {
                throw new UnsupportedOperationException();
            }

            @Override
            public T checkedGet()
                    throws E
            {
                throw exception;
            }

            @Override
            public T checkedGet(long timeout, TimeUnit unit)
                    throws TimeoutException, E
            {
                throw exception;
            }
        }
    }
}