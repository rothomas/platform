/*
 * Copyright 2013 Proofpoint, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.proofpoint.http.client.balancing;

import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.proofpoint.http.client.AsyncHttpClient;
import com.proofpoint.http.client.Request;
import com.proofpoint.http.client.RequestStats;
import com.proofpoint.http.client.ResponseHandler;
import org.weakref.jmx.Flatten;

import javax.annotation.concurrent.GuardedBy;
import javax.inject.Inject;
import java.net.URI;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;

public final class BalancingAsyncHttpClient
        extends BalancingHttpClient
        implements AsyncHttpClient
{
    private final AsyncHttpClient httpClient;

    @Inject
    public BalancingAsyncHttpClient(@ForBalancingHttpClient HttpServiceBalancer pool, @ForBalancingHttpClient AsyncHttpClient httpClient, BalancingHttpClientConfig config)
    {
        super(pool, httpClient, config);
        this.httpClient = checkNotNull(httpClient, "httpClient is null");
    }

    @Override
    public <T, E extends Exception> AsyncHttpResponseFuture<T> executeAsync(Request request, ResponseHandler<T, E> responseHandler)
    {
        checkArgument(!request.getUri().isAbsolute(), request.getUri() + " is not a relative URI");
        checkArgument(request.getUri().getHost() == null, request.getUri() + " has a host component");
        String path = request.getUri().getPath();
        checkArgument(path == null || !path.startsWith("/"), request.getUri() + " path starts with '/'");

        HttpServiceAttempt attempt;
        try {
            attempt = pool.createAttempt();
        }
        catch (RuntimeException e) {
            try {
                return new ImmediateAsyncHttpResponseFuture<>(responseHandler.handleException(request, e));
            }
            catch (Exception e1) {
                return new ImmediateFailedAsyncHttpResponseFuture<>((E) e1);
            }
        }
        RetryFuture<T, E> retryFuture = new RetryFuture<>(request, responseHandler);
        attemptQuery(retryFuture, request, responseHandler, attempt, maxAttempts);
        return retryFuture;
    }

    private <T, E extends Exception> void attemptQuery(RetryFuture<T, E> retryFuture, Request request, ResponseHandler<T, E> responseHandler, HttpServiceAttempt attempt, int attemptsLeft)
    {
        RetryingResponseHandler<T, E> retryingResponseHandler = new RetryingResponseHandler<>(request, responseHandler, attemptsLeft <= 1);

        URI uri = attempt.getUri();
        if (!uri.toString().endsWith("/")) {
            uri = URI.create(uri.toString() + '/');
        }
        uri = uri.resolve(request.getUri());

        Request subRequest = Request.Builder.fromRequest(request)
                .setUri(uri)
                .build();

        --attemptsLeft;
        AsyncHttpResponseFuture<T> future = httpClient.executeAsync(subRequest, retryingResponseHandler);
        retryFuture.newAttempt(future, attempt, uri, attemptsLeft);
    }

    @Flatten
    @Override
    public RequestStats getStats()
    {
        return httpClient.getStats();
    }

    @Override
    public void close()
    {
        httpClient.close();
    }

    private class RetryFuture<T, E extends Exception>
            extends AbstractFuture<T>
            implements AsyncHttpResponseFuture<T>
    {

        private final Request request;
        private final ResponseHandler<T,E> responseHandler;
        private final Object subFutureLock = new Object();
        @GuardedBy("subFutureLock")
        private HttpServiceAttempt attempt = null;
        @GuardedBy("subFutureLock")
        private URI uri = null;
        @GuardedBy("subFutureLock")
        private AsyncHttpResponseFuture<T> subFuture = null;

        public RetryFuture(Request request, ResponseHandler<T, E> responseHandler)
        {
            this.request = request;
            this.responseHandler = responseHandler;
        }

        void newAttempt(final AsyncHttpResponseFuture<T> future, final HttpServiceAttempt attempt, URI uri, final int attemptsLeft)
        {
            synchronized (subFutureLock) {
                this.attempt = attempt;
                this.subFuture = future;
                this.uri = uri;
            }
            final RetryFuture<T, E> retryFuture = this;
            final Request request = this.request;
            final ResponseHandler<T, E> responseHandler = this.responseHandler;
            Futures.addCallback(future, new FutureCallback<T>()
            {
                @Override
                public void onSuccess(T result)
                {
                    attempt.markGood();
                    set(result);
                }

                @Override
                public void onFailure(Throwable t)
                {
                    if (t instanceof InnerHandlerException) {
                        attempt.markBad(((InnerHandlerException) t).getFailureCategory());
                        setException(t.getCause());
                    }
                    else if (t instanceof FailureStatusException) {
                        attempt.markBad(((FailureStatusException) t).getFailureCategory());
                        //noinspection unchecked
                        set((T) ((FailureStatusException)t).result);
                    }
                    else if (t instanceof RetryException) {
                        attempt.markBad(((RetryException) t).getFailureCategory());
                        synchronized (subFutureLock) {
                            HttpServiceAttempt nextAttempt;
                            try {
                                nextAttempt = attempt.next();
                            }
                            catch (RuntimeException e1) {
                                try {
                                    set(responseHandler.handleException(request, e1));
                                }
                                catch (Exception e2) {
                                    setException(e2);
                                }
                                return;
                            }
                            try {
                                attemptQuery(retryFuture, request, responseHandler, nextAttempt, attemptsLeft);
                            }
                            catch (RuntimeException e1) {
                                setException(e1);
                            }
                        }
                    }
                }
            });
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning)
        {

            if (super.cancel(mayInterruptIfRunning)) {
                // todo attempt.cancel() ?
                synchronized (subFutureLock) {
                    subFuture.cancel(mayInterruptIfRunning);
                }
                return true;
            }
            return false;
        }

        @Override
        public String getState()
        {
            synchronized (subFutureLock) {
                return format("Attempt %s to %s: %s", attempt, uri, subFuture.getState());
            }
        }
    }

    private static class ImmediateAsyncHttpResponseFuture<T, E extends Exception>
            extends AbstractFuture<T>
            implements AsyncHttpResponseFuture<T>
    {

        private final T result;

        public ImmediateAsyncHttpResponseFuture(T result)
        {
            this.result = result;
            set(result);
        }

        @Override
        public String getState()
        {
            return "Succeeded with result " + result;
        }
    }

    private static class ImmediateFailedAsyncHttpResponseFuture<T, E extends Exception>
            extends AbstractFuture<T>
            implements AsyncHttpResponseFuture<T>
    {

        private final E exception;

        public ImmediateFailedAsyncHttpResponseFuture(E exception)
        {
            this.exception = exception;
            setException(exception);
        }

        @Override
        public String getState()
        {
            return "Failed with exception " + exception;
        }
    }
}
