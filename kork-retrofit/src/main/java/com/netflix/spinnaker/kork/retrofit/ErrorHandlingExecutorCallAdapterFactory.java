/*
 * Copyright 2023 OpsMx, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.kork.retrofit;

import com.netflix.spinnaker.kork.retrofit.exceptions.RetrofitException;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerHttpException;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerNetworkException;
import com.netflix.spinnaker.kork.retrofit.exceptions.SpinnakerServerException;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.concurrent.Executor;
import okhttp3.Request;
import okio.Timeout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpStatus;
import retrofit2.Call;
import retrofit2.CallAdapter;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;

/*
 * As part retrofit2 {@link retrofit.RetrofitError} and {@link retrofit.ErrorHandler} are removed.
 * So this class helps to achieve similar logic as retrofit and handle exception globally in retrofit2.
 * This can be achieved by setting this class as CallAdapterFactory at the time of {@link Retrofit} client creation.
 *  */
public class ErrorHandlingExecutorCallAdapterFactory extends CallAdapter.Factory {

  private final @Nullable Executor callbackExecutor;

  ErrorHandlingExecutorCallAdapterFactory(@Nullable Executor callbackExecutor) {
    this.callbackExecutor = callbackExecutor;
  }

  public static ErrorHandlingExecutorCallAdapterFactory getInstance(
      @Nullable Executor callbackExecutor) {
    return new ErrorHandlingExecutorCallAdapterFactory(callbackExecutor);
  }

  @Nullable
  @Override
  public CallAdapter<?, ?> get(Type returnType, Annotation[] annotations, Retrofit retrofit) {

    if (getRawType(returnType) != Call.class) {
      return null;
    }
    final Type responseType = getCallResponseType(returnType);
    return new CallAdapter<Object, Call<?>>() {
      @Override
      public Type responseType() {
        return responseType;
      }

      @Override
      public Call<Object> adapt(Call<Object> call) {
        return new ExecutorCallbackCall<>(callbackExecutor, call, retrofit);
      }
    };
  }

  static final class ExecutorCallbackCall<T> implements Call<T> {

    final Executor callbackExecutor;
    final Call<T> delegate;
    private final Retrofit retrofit;

    ExecutorCallbackCall(Executor callbackExecutor, Call<T> delegate, Retrofit retrofit) {
      this.callbackExecutor = callbackExecutor;
      this.delegate = delegate;
      this.retrofit = retrofit;
    }

    @Override
    public Response<T> execute() {
      try {
        Response<T> syncResp = delegate.execute();
        if (syncResp.isSuccessful()) {
          return syncResp;
        }
        SpinnakerHttpException retval =
            new SpinnakerHttpException(
                RetrofitException.httpError(
                    syncResp.raw().request().url().toString(), syncResp, retrofit));
        if ((syncResp.code() == HttpStatus.NOT_FOUND.value())
            || (syncResp.code() == HttpStatus.BAD_REQUEST.value())) {
          retval.setRetryable(false);
        }
        throw retval;
      } catch (SpinnakerHttpException e) {
        throw e;
      } catch (Exception e) {
        if (e instanceof IOException) {
          throw new SpinnakerNetworkException(RetrofitException.networkError((IOException) e));
        } else {
          throw new SpinnakerServerException(RetrofitException.unexpectedError(e));
        }
      }
    }

    @Override
    public void enqueue(Callback<T> callback) {
      checkNotNull(callback, "callback == null");
      delegate.enqueue(
          new MyExecutorCallback<>(callbackExecutor, delegate, callback, this, retrofit));
    }

    @Override
    public boolean isExecuted() {
      return delegate.isExecuted();
    }

    @Override
    public void cancel() {
      delegate.cancel();
    }

    @Override
    public boolean isCanceled() {
      return delegate.isCanceled();
    }

    @Override
    public Call<T> clone() {
      return new ExecutorCallbackCall<>(callbackExecutor, delegate.clone(), retrofit);
    }

    @Override
    public Request request() {
      return delegate.request();
    }

    @Override
    public Timeout timeout() {
      return delegate.timeout();
    }
  }

  static class MyExecutorCallback<T> implements Callback<T> {
    final Executor callbackExecutor;
    final Call<T> delegate;
    final Callback<T> callback;
    final ExecutorCallbackCall<T> executorCallbackCall;
    private Retrofit retrofit;

    public MyExecutorCallback(
        Executor callbackExecutor,
        Call<T> delegate,
        Callback<T> callback,
        ExecutorCallbackCall<T> executorCallbackCall,
        Retrofit retrofit) {
      this.callbackExecutor = callbackExecutor;
      this.delegate = delegate;
      this.callback = callback;
      this.executorCallbackCall = executorCallbackCall;
      this.retrofit = retrofit;
    }

    @Override
    public void onResponse(final Call<T> call, final Response<T> response) {
      if (response.isSuccessful()) {
        callbackExecutor.execute(
            new Runnable() {
              @Override
              public void run() {
                callback.onResponse(executorCallbackCall, response);
              }
            });
      } else {
        callbackExecutor.execute(
            new Runnable() {
              @Override
              public void run() {

                SpinnakerHttpException retval =
                    new SpinnakerHttpException(
                        RetrofitException.httpError(
                            response.raw().request().url().toString(), response, retrofit));
                if ((response.code() == HttpStatus.NOT_FOUND.value())
                    || (response.code() == HttpStatus.BAD_REQUEST.value())) {
                  retval.setRetryable(false);
                }

                callback.onFailure(executorCallbackCall, retval);
              }
            });
      }
    }

    @Override
    public void onFailure(Call<T> call, final Throwable t) {

      SpinnakerServerException exception;
      if (t instanceof IOException) {
        exception = new SpinnakerNetworkException(RetrofitException.networkError((IOException) t));
      } else if (t instanceof SpinnakerHttpException) {
        exception = (SpinnakerHttpException) t;
      } else {
        exception = new SpinnakerServerException(RetrofitException.unexpectedError(t));
      }
      final SpinnakerServerException finalException = exception;
      callbackExecutor.execute(
          new Runnable() {
            @Override
            public void run() {
              callback.onFailure(executorCallbackCall, finalException);
            }
          });
    }
  }

  public static class MainThreadExecutor implements Executor {

    @Override
    public void execute(@NotNull Runnable runnable) {
      new Thread(runnable).start();
    }
  }

  static Type getCallResponseType(Type returnType) {
    if (!(returnType instanceof ParameterizedType)) {
      throw new IllegalArgumentException(
          "Call return type must be parameterized as Call<Foo> or Call<? extends Foo>");
    }
    return getParameterUpperBound(0, (ParameterizedType) returnType);
  }

  static <T> T checkNotNull(@Nullable T object, String message) {
    if (object == null) {
      throw new NullPointerException(message);
    }
    return object;
  }
}
