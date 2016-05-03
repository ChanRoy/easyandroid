/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cc.easyandroid.easyhttp.core.retrofit;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.TextUtils;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import cc.easyandroid.easycache.volleycache.Cache;
import cc.easyandroid.easycache.volleycache.Cache.Entry;
import cc.easyandroid.easycore.EasyCall;
import cc.easyandroid.easycore.EasyHttpStateCallback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

public class OkHttpEasyCall<T> implements EasyCall<T> {
    protected final OkHttpClient client;
    protected final Converter<T> responseConverter;
    private Request request;
    private volatile okhttp3.Call rawCall;
    private boolean executed; // Guarded by this.
    private volatile boolean canceled;

    public OkHttpEasyCall(OkHttpClient client, Converter<T> responseConverter, Request request) {
        this.client = client;
        this.request = request;
        this.responseConverter = responseConverter;
    }


    static final String THREAD_PREFIX = "EasyAndroid-";
    static final String IDLE_THREAD_NAME = THREAD_PREFIX + "Idle";

    static Executor defaultHttpExecutor() {
        return Executors.newCachedThreadPool(new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable r) {
                return new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Process.setThreadPriority(THREAD_PRIORITY_BACKGROUND);
                        r.run();
                    }
                }, IDLE_THREAD_NAME);
            }
        });
    }

    public static final Executor threadExecutor = defaultHttpExecutor();
    public static final Executor mainCallbackExecutor = new MainThreadExecutor();

    static class MainThreadExecutor implements Executor {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(Runnable r) {
            handler.post(r);
        }
    }

    public Request createRequest() {
        return request;
    }

    // We are a final type & this saves clearing state.
    public OkHttpEasyCall<T> clone() {
        return new OkHttpEasyCall<T>(client, responseConverter, request);
    }

    private EasyResponse<T> execCacheRequest(Request request) {
        if (responseConverter instanceof GsonConverter || responseConverter instanceof StringConverter) {
            Cache cache = responseConverter.getCache();
            if (cache == null) {
                return null;
            }
            Entry entry = cache.get(request.url().toString());// 充缓存中获取entry
            if (entry == null) {
                return null;
            }
            if (entry.isExpired()) {// 缓存过期了
                return null;
            }
            if (entry.data != null) {// 如果有数据就使用缓存
                MediaType contentType = MediaType.parse(entry.mimeType);
                byte[] bytes = entry.data;
                try {
                    okhttp3.Response rawResponse = new okhttp3.Response.Builder()//
                            .code(200).request(request).protocol(Protocol.HTTP_1_1).body(ResponseBody.create(contentType, bytes)).build();
                    return parseResponse(rawResponse, request, false);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    @Override
    public void enqueue(final EasyHttpStateCallback<T> callback) {
        synchronized (this) {
            if (executed)
                throw new IllegalStateException("Already executed");
            executed = true;
        }
        final Request request = createRequest();
        String cacheMode = getCacheMode(request);
        // ----------------------------------------------------------------------cgp
        if (!TextUtils.isEmpty(cacheMode)) {
            switch (cacheMode) {
                case CacheMode.LOAD_NETWORK_ELSE_CACHE:// 先网络然后再缓存
                    exeRequest(callback, request, true);
                    return;
                case CacheMode.LOAD_CACHE_ELSE_NETWORK:// 先缓存再网络
                    // ---------------------从缓存中取
                    EasyResponse<T> easyResponse = execCacheRequest(request);
                    if (easyResponse != null) {
                        callback.onResponse(easyResponse);
                        return;
                    }
                    // ---------------------从缓存中取
                    // 如果缓存没有就跳出，执行网络请求
                case CacheMode.LOAD_DEFAULT:
                case CacheMode.LOAD_NETWORK_ONLY:
                default:
                    break;// 直接跳出
            }
        }
        // ----------------------------------------------------------------------cgp
        exeRequest(callback, request, false);
    }

    private void exeRequest(final EasyHttpStateCallback<T> callback, final Request request, final boolean loadnetElseCache) {
        okhttp3.Call rawCall;
        try {
            rawCall = client.newCall(request);

        } catch (Throwable t) {
            t.printStackTrace();
            callback.onFailure(t);
            return;
        }
        if (canceled) {
            rawCall.cancel();
            return;
        }
        this.rawCall = rawCall;
        callback.start();
        rawCall.enqueue(new okhttp3.Callback() {
            private void callFailure(final Throwable e) {
                mainCallbackExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        e.printStackTrace();
                        callback.onFailure(e);
                    }
                });
            }

            private void callSuccess(final EasyResponse<T> easyResponse) {
                mainCallbackExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        callback.onResponse(easyResponse);
                    }
                });
            }

            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                e.printStackTrace();
                if (canceled) {
                    return;
                }
                //没有网络会进入onFailure
                if (loadnetElseCache) {
                    threadExecutor.execute(new CallbackRunnable<T>(callback, mainCallbackExecutor) {
                        @Override
                        public EasyResponse<T> obtainResponse() {//这里走缓存
                            return execCacheRequest(request);
                        }
                    });
                    return;
                }
                callFailure(e);
            }

            @Override
            public void onResponse(okhttp3.Call rawCall, okhttp3.Response rawResponse) {

                if (canceled) {
                    return;
                }
                EasyResponse<T> easyResponse;
                try {
//                    rawResponse.isSuccessful().
                    easyResponse = parseResponse(rawResponse, request, loadnetElseCache);
                } catch (Throwable e) {
                    if (loadnetElseCache) {
                        e.printStackTrace();
                        onFailure(rawCall, new IOException("解析结果错误"));
                        return;
                    }
                    callFailure(e);
                    return;
                }
                callSuccess(easyResponse);
            }
        });
    }

    public EasyResponse<T> execute() throws IOException {
        synchronized (this) {
            if (executed)
                throw new IllegalStateException("Already executed");
            executed = true;
        }
        final Request request = createRequest();

        okhttp3.Call rawCall = client.newCall(request);
        if (canceled) {
            rawCall.cancel();
        }

        this.rawCall = rawCall;
        String cacheMode = getCacheMode(request);
        if (!TextUtils.isEmpty(cacheMode)) {
            cacheMode = cacheMode.trim().toLowerCase(Locale.CHINA);
            switch (cacheMode) {
                case CacheMode.LOAD_NETWORK_ELSE_CACHE:// 先网络然后再缓存
                    EasyResponse<T> easyResponse;
                    try {
                        easyResponse = parseResponse(rawCall.execute(), request, true);//如果失败去加载缓存，true 会拋异常
                    } catch (Exception e) {
                        easyResponse = execCacheRequest(request);
                    }
                    return easyResponse;
                case CacheMode.LOAD_CACHE_ELSE_NETWORK:// 先缓存再网络
                    // ---------------------充缓存中取
                    easyResponse = execCacheRequest(request);
                    if (easyResponse != null) {
                        return easyResponse;
                    }
                    // ---------------------充缓存中取
                    // 如果缓存没有就跳出，执行网络请求
                case CacheMode.LOAD_DEFAULT:
                case CacheMode.LOAD_NETWORK_ONLY:
                default:
                    break;// 直接跳出
            }
        }
        return parseResponse(rawCall.execute(), request, false);
    }

    private String getCacheMode(Request request) {
        return request.header("Cache-Mode");
    }

    /**
     * @param rawResponse
     * @param request
     * @param ifFailedToLoadTheCache 如果失败去加载缓存,这里注意返回码的位置
     * @return
     * @throws IOException
     */
    private EasyResponse<T> parseResponse(okhttp3.Response rawResponse, Request request, boolean ifFailedToLoadTheCache) throws IOException {
        ResponseBody rawBody = rawResponse.body();
        // rawResponse.r
        // Remove the body's source (the only stateful object) so we can pass
        // the response along.
        rawResponse = rawResponse.newBuilder().body(new NoContentResponseBody(rawBody.contentType(), rawBody.contentLength())).build();

        int code = rawResponse.code();
        if (code < 200 || code >= 300) {
            if (!ifFailedToLoadTheCache) {
                try {
                    return EasyResponse.error(code, rawResponse.message());
                } finally {
                    Utils.closeQuietly(rawBody);
                }
            } else {
                throw new IOException("code < 200 || code >= 300  code=" + code);
            }
        }
        if (code == 204 || code == 205) {
            if (!ifFailedToLoadTheCache) {
                return EasyResponse.success(null);
            } else {
                throw new IOException("code == 204 || code == 205");
            }
        }

        ExceptionCatchingRequestBody catchingBody = new ExceptionCatchingRequestBody(rawBody);
        try {
            T body = responseConverter.fromBody(catchingBody, request);
            return EasyResponse.success(body);
        } catch (RuntimeException e) {
            // If the underlying source threw an exception, propagate that
            // rather than indicating it was
            // a runtime exception.
            catchingBody.throwIfCaught();
            throw e;
        }
    }

    @Override
    public void cancel() {
        canceled = true;
        okhttp3.Call rawCall = this.rawCall;
        if (rawCall != null) {
            rawCall.cancel();
        }
    }

    @Override
    public boolean isCancel() {
        return canceled;
    }

    static final class ExceptionCatchingRequestBody extends ResponseBody {
        private final ResponseBody delegate;
        IOException thrownException;

        ExceptionCatchingRequestBody(ResponseBody delegate) {
            this.delegate = delegate;
        }

        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() {
            return delegate.contentLength();
        }

        @Override
        public BufferedSource source() {
            return Okio.buffer(new ForwardingSource(delegate.source()) {
                @Override
                public long read(Buffer sink, long byteCount) throws IOException {
                    try {
                        return super.read(sink, byteCount);
                    } catch (IOException e) {
                        thrownException = e;
                        throw e;
                    }
                }
            });
        }

        @Override
        public void close() {
            delegate.close();
        }

        void throwIfCaught() throws IOException {
            if (thrownException != null) {
                throw thrownException;
            }
        }
    }
}
