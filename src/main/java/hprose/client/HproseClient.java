/**********************************************************\
|                                                          |
|                          hprose                          |
|                                                          |
| Official WebSite: http://www.hprose.com/                 |
|                   http://www.hprose.org/                 |
|                                                          |
\**********************************************************/
/**********************************************************\
 *                                                        *
 * HproseClient.java                                      *
 *                                                        *
 * hprose client class for Java.                          *
 *                                                        *
 * LastModified: Apr 25, 2016                             *
 * Author: Ma Bingyao <andot@hprose.com>                  *
 *                                                        *
\**********************************************************/
package hprose.client;

import hprose.common.AsyncFilterHandler;
import hprose.common.AsyncInvokeHandler;
import hprose.common.FilterHandler;
import hprose.common.HproseCallback;
import hprose.common.HproseCallback1;
import hprose.common.HproseContext;
import hprose.common.HproseErrorEvent;
import hprose.common.HproseException;
import hprose.common.HproseFilter;
import hprose.common.HproseInvocationHandler;
import hprose.common.HproseInvoker;
import hprose.common.HproseResultMode;
import hprose.common.InvokeHandler;
import hprose.common.InvokeSettings;
import hprose.common.NextAsyncFilterHandler;
import hprose.common.NextAsyncInvokeHandler;
import hprose.common.NextFilterHandler;
import hprose.common.NextInvokeHandler;
import hprose.io.ByteBufferStream;
import hprose.io.HproseMode;
import static hprose.io.HproseTags.TagArgument;
import static hprose.io.HproseTags.TagCall;
import static hprose.io.HproseTags.TagEnd;
import static hprose.io.HproseTags.TagError;
import static hprose.io.HproseTags.TagResult;
import hprose.io.serialize.Writer;
import hprose.io.unserialize.Reader;
import hprose.net.ReceiveCallback;
import hprose.util.ClassUtil;
import hprose.util.StrUtil;
import hprose.util.concurrent.Action;
import hprose.util.concurrent.Func;
import hprose.util.concurrent.Promise;
import hprose.util.concurrent.Threads;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class HproseClient implements HproseInvoker {
    protected final static ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final static Object[] nullArgs = new Object[0];
    private final ArrayList<InvokeHandler> invokeHandlers = new ArrayList<InvokeHandler>();
    private final ArrayList<FilterHandler> beforeFilterHandlers = new ArrayList<FilterHandler>();
    private final ArrayList<FilterHandler> afterFilterHandlers = new ArrayList<FilterHandler>();
    private final ArrayList<AsyncInvokeHandler> asyncInvokeHandlers = new ArrayList<AsyncInvokeHandler>();
    private final ArrayList<AsyncFilterHandler> asyncBeforeFilterHandlers = new ArrayList<AsyncFilterHandler>();
    private final ArrayList<AsyncFilterHandler> asyncAfterFilterHandlers = new ArrayList<AsyncFilterHandler>();
    private final ArrayList<HproseFilter> filters = new ArrayList<HproseFilter>();
    private final ArrayList<String> uris = new ArrayList<String>();
    private final AtomicInteger index = new AtomicInteger(-1);
    private HproseMode mode;
    private int timeout = 30000;
    private int retry = 10;
    private boolean idempontent = false;
    private boolean failswitch = false;
    private boolean byref = false;
    private boolean simple = false;
    private final NextInvokeHandler defaultInvokeHandler = new NextInvokeHandler() {
        public Object handle(String name, Object[] args, HproseContext context) throws IOException {
            return syncInvokeHandler(name, args, (ClientContext)context);
        }
    };
    private final NextAsyncInvokeHandler defaultAsyncInvokeHandler = new NextAsyncInvokeHandler() {
        public Promise<Object> handle(String name, Object[] args, HproseContext context) {
            return asyncInvokeHandler(name, args, (ClientContext)context);
        }
    };
    private final NextFilterHandler defaultBeforeFilterHandler = new NextFilterHandler() {
        public ByteBuffer handle(ByteBuffer request, HproseContext context) throws IOException {
            return syncBeforeFilterHandler(request, (ClientContext)context);
        }
    };
    private final NextAsyncFilterHandler defaultAsyncBeforeFilterHandler = new NextAsyncFilterHandler() {
        public Promise<ByteBuffer> handle(ByteBuffer request, HproseContext context) {
            return asyncBeforeFilterHandler(request, (ClientContext)context);
        }
    };
    private final NextFilterHandler defaultAfterFilterHandler = new NextFilterHandler() {
        public ByteBuffer handle(ByteBuffer request, HproseContext context) throws IOException {
            return syncAfterFilterHandler(request, (ClientContext)context);
        }
    };
    private final NextAsyncFilterHandler defaultAsyncAfterFilterHandler = new NextAsyncFilterHandler() {
        public Promise<ByteBuffer> handle(ByteBuffer request, HproseContext context) {
            return asyncAfterFilterHandler(request, (ClientContext)context);
        }
    };
    private NextInvokeHandler invokeHandler = defaultInvokeHandler;
    private NextFilterHandler beforeFilterHandler = defaultBeforeFilterHandler;
    private NextFilterHandler afterFilterHandler = defaultAfterFilterHandler;
    private NextAsyncInvokeHandler asyncInvokeHandler = defaultAsyncInvokeHandler;
    private NextAsyncFilterHandler asyncBeforeFilterHandler = defaultAsyncBeforeFilterHandler;
    private NextAsyncFilterHandler asyncAfterFilterHandler = defaultAsyncAfterFilterHandler;
    protected String uri;
    public HproseErrorEvent onError = null;

    static {
        Threads.registerShutdownHandler(new Runnable() {
            public void run() {
                if (!executor.isShutdown()) {
                    executor.shutdown();
                }
            }
        });
    }

    protected HproseClient() {
        this((String[])null, HproseMode.MemberMode);
    }

    protected HproseClient(HproseMode mode) {
        this((String[])null, mode);
    }

    protected HproseClient(String uri) {
        this(uri, HproseMode.MemberMode);
    }

    protected HproseClient(String uri, HproseMode mode) {
        this(uri == null ? (String[])null : new String[] {uri}, mode);
    }

    protected HproseClient(String[] uris) {
        this(uris, HproseMode.MemberMode);
    }

    protected HproseClient(String[] uris, HproseMode mode) {
        this.mode = mode;
        if (uris != null) {
            useService(uris);
        }
    }

    public void close() {}

    private final static HashMap<String, Class<? extends HproseClient>> clientFactories = new HashMap<String, Class<? extends HproseClient>>();

    public static void registerClientFactory(String scheme, Class<? extends HproseClient> clientClass) {
        synchronized (clientFactories) {
            clientFactories.put(scheme, clientClass);
        }
    }

    static {
        registerClientFactory("tcp", HproseTcpClient.class);
        registerClientFactory("tcp4", HproseTcpClient.class);
        registerClientFactory("tcp6", HproseTcpClient.class);
        registerClientFactory("http", HproseHttpClient.class);
        registerClientFactory("https", HproseHttpClient.class);
    }

    public static HproseClient create(String uri) throws IOException, URISyntaxException {
        return create(new String[] { uri }, HproseMode.MemberMode);
    }

    public static HproseClient create(String uri, HproseMode mode) throws IOException, URISyntaxException {
        return create(new String[] { uri }, mode);
    }

    public static HproseClient create(String[] uris, HproseMode mode) throws IOException, URISyntaxException {
        String scheme = (new URI(uris[0])).getScheme().toLowerCase();
        for (int i = 1, n = uris.length; i < n; ++i) {
            if (!(new URI(uris[i])).getScheme().toLowerCase().equalsIgnoreCase(scheme)) {
                throw new HproseException("Not support multiple protocol.");
            }
        }
        Class<? extends HproseClient> clientClass = clientFactories.get(scheme);
        if (clientClass != null) {
            try {
                HproseClient client = clientClass.newInstance();
                client.mode = mode;
                client.useService(uris);
                return client;
            }
            catch (Exception ex) {
                throw new HproseException("This client doesn't support " + scheme + " scheme.");
            }
        }
        throw new HproseException("This client doesn't support " + scheme + " scheme.");
    }

    public final int getTimeout() {
        return timeout;
    }

    public final void setTimeout(int timeout) {
        if (timeout < 1) throw new IllegalArgumentException("timeout must be great than 0");
        this.timeout = timeout;
    }

    public final int getRetry() {
        return retry;
    }

    public final void setRetry(int retry) {
        this.retry = retry;
    }

    public final boolean isIdempontent() {
        return idempontent;
    }

    public final void setIdempontent(boolean idempontent) {
        this.idempontent = idempontent;
    }

    public final boolean isFailswitch() {
        return failswitch;
    }

    public final void setFailswitch(boolean failswitch) {
        this.failswitch = failswitch;
    }

    public final boolean isByref() {
        return byref;
    }

    public final void setByref(boolean byref) {
        this.byref = byref;
    }

    public final boolean isSimple() {
        return simple;
    }

    public final void setSimple(boolean simple) {
        this.simple = simple;
    }

    public final HproseFilter getFilter() {
        if (filters.isEmpty()) {
            return null;
        }
        return filters.get(0);
    }

    public final void setFilter(HproseFilter filter) {
        if (!filters.isEmpty()) {
            filters.clear();
        }
        if (filter != null) {
            filters.add(filter);
        }
    }

    public final void addFilter(HproseFilter filter) {
        filters.add(filter);
    }

    public final boolean removeFilter(HproseFilter filter) {
        return filters.remove(filter);
    }

    public final void useService(String uri) {
        useService(new String[] { uri });
    }

    public final void useService(String[] uris) {
        this.uris.clear();
        int n = uris.length;
        for (int i = 0; i < n; i++) {
            this.uris.add(uris[i]);
        }
        if (n > 0) {
            index.set((int)Math.floor(Math.random() * n));
        }
        this.uri = uris[index.get()];
    }

    public final <T> T useService(Class<T> type) {
        return useService(type, null);
    }

    public final <T> T useService(String uri, Class<T> type) {
        return useService(uri, type, null);
    }

    public final <T> T useService(String[] uris, Class<T> type) {
        return useService(uris, type, null);
    }

    @SuppressWarnings("unchecked")
    public final <T> T useService(Class<T> type, String ns) {
        HproseInvocationHandler handler = new HproseInvocationHandler(this, ns);
        if (type.isInterface()) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
        }
        else {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), type.getInterfaces(), handler);
        }
    }

    public final <T> T useService(String uri, Class<T> type, String ns) {
        useService(uri);
        return useService(type, ns);
    }

    public final <T> T useService(String[] uris, Class<T> type, String ns) {
        useService(uris);
        return useService(type, ns);
    }

    private ByteBuffer outputFilter(ByteBuffer request, ClientContext context) {
        for (int i = 0, n = filters.size(); i < n; ++i) {
            request = filters.get(i).outputFilter(request, context);
            if (request.position() != 0) {
                request.flip();
            }
        }
        return request;
    }

    private ByteBuffer inputFilter(ByteBuffer response, ClientContext context) {
        for (int i = filters.size() - 1; i >= 0; --i) {
            response = filters.get(i).inputFilter(response, context);
            if (response.position() != 0) {
                response.flip();
            }
        }
        return response;
    }

    private ByteBuffer syncBeforeFilterHandler(ByteBuffer request, ClientContext context) throws IOException {
        request = outputFilter(request, context);
        ByteBuffer response = afterFilterHandler.handle(request, context);
        if (context.getSettings().isOneway()) return null;
        if (response.position() != 0) {
            response.flip();
        }
        response = inputFilter(response, context);
        return response;
    }

    private Promise<ByteBuffer> asyncBeforeFilterHandler(ByteBuffer request, final ClientContext context) {
        request = outputFilter(request, context);
        Promise<ByteBuffer> response = asyncAfterFilterHandler.handle(request, context);
        return (Promise<ByteBuffer>)response.then(new Func<ByteBuffer, ByteBuffer>() {
            public ByteBuffer call(ByteBuffer response) throws Throwable {
                if (context.getSettings().isOneway()) return null;
                if (response.position() != 0) {
                    response.flip();
                }
                response = inputFilter(response, context);
                return response;
            }
        });
    }

    private ByteBuffer syncAfterFilterHandler(ByteBuffer request, ClientContext context) throws IOException {
        return sendAndReceive(request);
    }

    private Promise<ByteBuffer> asyncAfterFilterHandler(ByteBuffer request, ClientContext context) {
        final Promise<ByteBuffer> response = new Promise<ByteBuffer>();
        sendAndReceive(request, new ReceiveCallback() {
            public void handler(ByteBuffer stream, Exception e) {
                if (e != null) {
                    response.reject(e);
                }
                else {
                    response.resolve(stream);
                }
            }
        });
        return response;
    }

    private ByteBuffer syncSendAndReceive(ByteBuffer request, ClientContext context) throws IOException {
        try {
            return beforeFilterHandler.handle(request, context);
        }
        catch (IOException e) {
            ByteBuffer response = syncRetry(request, context);
            if (response != null) return response;
            throw e;
        }
    }

    private ByteBuffer syncRetry(ByteBuffer request, ClientContext context) throws IOException {
        InvokeSettings settings = context.getSettings();
        if (settings.isFailswitch()) {
            failswitch();
        }
        if (settings.isIdempotent()) {
            int n = settings.getRetry();
            if (n > 0) {
                settings.setRetry(n - 1);
                int interval = (n >= 10) ? 500 : (10 - n) * 500;
                try {
                    Thread.sleep(interval);
                }
                catch (InterruptedException ex) {
                    return null;
                }
                return syncSendAndReceive(request, context);
            }
        }
        return null;
    }

    private Promise<ByteBuffer> asyncSendAndReceive(final ByteBuffer request, final ClientContext context) {
        return (Promise<ByteBuffer>) asyncBeforeFilterHandler.handle(request, context).catchError(
            new Func<Promise<ByteBuffer>, Throwable>() {
                public Promise<ByteBuffer> call(Throwable e) throws Throwable {
                    Promise<ByteBuffer> response = asyncRetry(request, context);
                    if (response != null) {
                        return response;
                    }
                    throw e;
                }
            }
        );
    }

    private Promise<ByteBuffer> asyncRetry(final ByteBuffer request, final ClientContext context) {
        InvokeSettings settings = context.getSettings();
        if (settings.isFailswitch()) {
            failswitch();
        }
        if (settings.isIdempotent()) {
            int n = settings.getRetry();
            if (n > 0) {
                settings.setRetry(n - 1);
                int interval = (n >= 10) ? 500 : (10 - n) * 500;
                return (Promise<ByteBuffer>) Promise.delayed(interval, new Callable<Promise<ByteBuffer>>() {
                    public Promise<ByteBuffer> call() throws Exception {
                        return asyncSendAndReceive(request, context);
                    }
                });
            }
        }
        return null;
    }

    private void failswitch() {
        int i = index.get() + 1;
        if (i >= uris.size()) {
            index.set(i = 0);
        }
        index.set(i);
        uri = uris.get(i);
    }

    private ClientContext getContext(InvokeSettings settings) {
        ClientContext context = new ClientContext(this);
        context.getSettings().copyFrom(settings);
        return context;
    }

    private ByteBufferStream encode(String name, Object[] args, ClientContext context) throws IOException {
        ByteBufferStream stream = new ByteBufferStream();
        InvokeSettings settings = context.getSettings();
        Writer writer = new Writer(stream.getOutputStream(), mode, settings.isSimple());
        stream.write(TagCall);
        writer.writeString(name);
        if ((args != null) && (args.length > 0 || settings.isByref())) {
            writer.reset();
            writer.writeArray(args);
            if (settings.isByref()) {
                writer.writeBoolean(true);
            }
        }
        stream.write(TagEnd);
        stream.flip();
        return stream;
    }

    private Object getRaw(ByteBufferStream stream, Type returnType) throws HproseException {
        stream.flip();
        if (returnType == null ||
            returnType == Object.class ||
            returnType == ByteBuffer.class ||
            returnType == Buffer.class) {
            return stream.buffer;
        }
        else if (returnType == ByteBufferStream.class) {
            return stream;
        }
        else if (returnType == byte[].class) {
            byte[] bytes = stream.toArray();
            stream.close();
            return bytes;
        }
        throw new HproseException("Can't Convert ByteBuffer to Type: " + returnType.toString());
    }

    private Object decode(ByteBufferStream stream, Object[] args, ClientContext context) throws IOException, HproseException {
        InvokeSettings settings = context.getSettings();
        if (settings.isOneway()) {
            return null;
        }
        if (stream.available() == 0) return new HproseException("EOF");
        int tag = stream.buffer.get(stream.buffer.limit() - 1);
        if (tag != TagEnd) {
            throw new HproseException("Wrong Response: \r\n" + StrUtil.toString(stream));
        }
        HproseResultMode resultMode = settings.getMode();
        Type returnType = settings.getReturnType();
        if (resultMode == HproseResultMode.RawWithEndTag) {
            return getRaw(stream, returnType);
        }
        else if (resultMode == HproseResultMode.Raw) {
            stream.buffer.limit(stream.buffer.limit() - 1);
            return getRaw(stream, returnType);
        }
        Object result = null;
        Reader reader = new Reader(stream.getInputStream(), mode);
        tag = stream.read();
        if (tag == TagResult) {
            if (resultMode == HproseResultMode.Normal) {
                result = reader.unserialize(returnType);
            }
            else if (resultMode == HproseResultMode.Serialized) {
                result = getRaw(reader.readRaw(), returnType);
            }
            tag = stream.read();
            if (tag == TagArgument) {
                reader.reset();
                Object[] arguments = reader.readObjectArray();
                int length = args.length;
                if (length > arguments.length) {
                    length = arguments.length;
                }
                System.arraycopy(arguments, 0, args, 0, length);
                tag = stream.read();
            }
        }
        else if (tag == TagError) {
            throw new HproseException(reader.readString());
        }
        if (tag != TagEnd) {
            stream.rewind();
            throw new HproseException("Wrong Response: \r\n" + StrUtil.toString(stream));
        }
        return result;
    }

    private Object syncInvokeHandler(String name, Object[] args, ClientContext context) throws IOException {
        ByteBufferStream stream = encode(name, args, context);
        try {
            ByteBuffer buffer = syncSendAndReceive(stream.buffer, context);
            ByteBufferStream.free(stream.buffer);
            stream.buffer = buffer;
            return decode(stream, args, context);
        }
        finally {
            stream.close();
        }
    }

    private Promise asyncInvokeHandler(String name, final Object[] args, final ClientContext context) {
        try {
            final ByteBufferStream stream = encode(name, args, context);
            return asyncSendAndReceive(stream.buffer, context).then(new Func<Object, ByteBuffer>() {
                public Object call(ByteBuffer value) throws Throwable {
                    ByteBufferStream.free(stream.buffer);
                    stream.buffer = value;
                    try {
                        return decode(stream, args, context);
                    }
                    finally {
                        stream.close();
                    }
                }
            });
        }
        catch (IOException e) {
            return Promise.error(e);
        }
    }

    private NextInvokeHandler getNextInvokeHandler(final NextInvokeHandler next, final InvokeHandler handler) {
        return new NextInvokeHandler() {
            public Object handle(String name, Object[] args, HproseContext context) throws IOException {
                return handler.handle(name, args, context, next);
            }
        };
    }

    private NextAsyncInvokeHandler getNextAsyncInvokeHandler(final NextAsyncInvokeHandler next, final AsyncInvokeHandler handler) {
        return new NextAsyncInvokeHandler() {
            public Promise<Object> handle(String name, Object[] args, HproseContext context) {
                return handler.handle(name, args, context, next);
            }
        };
    }

    private NextFilterHandler getNextFilterHandler(final NextFilterHandler next, final FilterHandler handler) {
        return new NextFilterHandler() {
            public ByteBuffer handle(ByteBuffer request, HproseContext context) throws IOException {
                return handler.handle(request, context, next);
            }
        };
    }

    private NextAsyncFilterHandler getNextAsyncFilterHandler(final NextAsyncFilterHandler next, final AsyncFilterHandler handler) {
        return new NextAsyncFilterHandler() {
            public Promise<ByteBuffer> handle(ByteBuffer request, HproseContext context) {
                return handler.handle(request, context, next);
            }
        };
    }

    public final void addInvokeHandler(InvokeHandler handler) {
        invokeHandlers.add(handler);
        NextInvokeHandler next = defaultInvokeHandler;
        for (int i = invokeHandlers.size() - 1; i >= 0; --i) {
            next = getNextInvokeHandler(next, invokeHandlers.get(i));
        }
        invokeHandler = next;
    }
    public final void addAsyncInvokeHandler(AsyncInvokeHandler handler) {
        asyncInvokeHandlers.add(handler);
        NextAsyncInvokeHandler next = defaultAsyncInvokeHandler;
        for (int i = asyncInvokeHandlers.size() - 1; i >= 0; --i) {
            next = getNextAsyncInvokeHandler(next, asyncInvokeHandlers.get(i));
        }
        asyncInvokeHandler = next;
    }
    public final void addBeforeFilterHandler(FilterHandler handler) {
        beforeFilterHandlers.add(handler);
        NextFilterHandler next = defaultBeforeFilterHandler;
        for (int i = beforeFilterHandlers.size() - 1; i >= 0; --i) {
            next = getNextFilterHandler(next, beforeFilterHandlers.get(i));
        }
        beforeFilterHandler = next;
    }
    public final void addAsyncBeforeFilterHandler(AsyncFilterHandler handler) {
        asyncBeforeFilterHandlers.add(handler);
        NextAsyncFilterHandler next = defaultAsyncBeforeFilterHandler;
        for (int i = asyncBeforeFilterHandlers.size() - 1; i >= 0; --i) {
            next = getNextAsyncFilterHandler(next, asyncBeforeFilterHandlers.get(i));
        }
        asyncBeforeFilterHandler = next;
    }
    public final void addAfterFilterHandler(FilterHandler handler) {
        afterFilterHandlers.add(handler);
        NextFilterHandler next = defaultAfterFilterHandler;
        for (int i = afterFilterHandlers.size() - 1; i >= 0; --i) {
            next = getNextFilterHandler(next, afterFilterHandlers.get(i));
        }
        afterFilterHandler = next;
    }
    public final void addAsyncAfterFilterHandler(AsyncFilterHandler handler) {
        asyncAfterFilterHandlers.add(handler);
        NextAsyncFilterHandler next = defaultAsyncAfterFilterHandler;
        for (int i = asyncAfterFilterHandlers.size() - 1; i >= 0; --i) {
            next = getNextAsyncFilterHandler(next, asyncAfterFilterHandlers.get(i));
        }
        asyncAfterFilterHandler = next;
    }
    public final HproseClient use(InvokeHandler handler) {
        addInvokeHandler(handler);
        return this;
    }
    public final HproseClient use(AsyncInvokeHandler handler) {
        addAsyncInvokeHandler(handler);
        return this;
    }
    public interface FilterHandlerManager {
        FilterHandlerManager use(FilterHandler handler);
        FilterHandlerManager use(AsyncFilterHandler handler);
    }
    public final FilterHandlerManager beforeFilter = new FilterHandlerManager() {
        public final FilterHandlerManager use(FilterHandler handler) {
            addBeforeFilterHandler(handler);
            return this;
        }
        public final FilterHandlerManager use(AsyncFilterHandler handler) {
            addAsyncBeforeFilterHandler(handler);
            return this;
        }
    };
    public final FilterHandlerManager afterFilter = new FilterHandlerManager() {
        public final FilterHandlerManager use(FilterHandler handler) {
            addAfterFilterHandler(handler);
            return this;
        }
        public final FilterHandlerManager use(AsyncFilterHandler handler) {
            addAsyncAfterFilterHandler(handler);
            return this;
        }
    };

    protected abstract ByteBuffer sendAndReceive(ByteBuffer buffer) throws IOException;

    protected abstract void sendAndReceive(ByteBuffer buffer, ReceiveCallback callback);

    public final void invoke(String name, HproseCallback1<?> callback) {
        invoke(name, nullArgs, callback, null, null, null);
    }
    public final void invoke(String name, HproseCallback1<?> callback, HproseErrorEvent errorEvent) {
        invoke(name, nullArgs, callback, errorEvent, null, null);
    }

    public final void invoke(String name, HproseCallback1<?> callback, InvokeSettings settings) {
        invoke(name, nullArgs, callback, null, null, settings);
    }
    public final void invoke(String name, HproseCallback1<?> callback, HproseErrorEvent errorEvent, InvokeSettings settings) {
        invoke(name, nullArgs, callback, errorEvent, null, settings);
    }

    public final void invoke(String name, Object[] args, HproseCallback1<?> callback) {
        invoke(name, args, callback, null, null, null);
    }
    public final void invoke(String name, Object[] args, HproseCallback1<?> callback, HproseErrorEvent errorEvent) {
        invoke(name, args, callback, errorEvent, null, null);
    }

    public final void invoke(String name, Object[] args, HproseCallback1<?> callback, InvokeSettings settings) {
        invoke(name, args, callback, null, null, settings);
    }
    public final void invoke(String name, Object[] args, HproseCallback1<?> callback, HproseErrorEvent errorEvent, InvokeSettings settings) {
        invoke(name, args, callback, errorEvent, null, settings);
    }

    public final <T> void invoke(String name, HproseCallback1<T> callback, Class<T> returnType) {
        invoke(name, nullArgs, callback, null, returnType, null);
    }
    public final <T> void invoke(String name, HproseCallback1<T> callback, HproseErrorEvent errorEvent, Class<T> returnType) {
        invoke(name, nullArgs, callback, errorEvent, returnType, null);
    }

    public final <T> void invoke(String name, HproseCallback1<T> callback, Class<T> returnType, InvokeSettings settings) {
        invoke(name, nullArgs, callback, null, returnType, settings);
    }
    public final <T> void invoke(String name, HproseCallback1<T> callback, HproseErrorEvent errorEvent, Class<T> returnType, InvokeSettings settings) {
        invoke(name, nullArgs, callback, errorEvent, returnType, settings);
    }

    public final <T> void invoke(String name, Object[] args, HproseCallback1<T> callback, Class<T> returnType) {
        invoke(name, args, callback, null, returnType, null);
    }
    public final <T> void invoke(String name, Object[] args, HproseCallback1<T> callback, HproseErrorEvent errorEvent, Class<T> returnType) {
        invoke(name, args, callback, errorEvent, returnType, null);
    }

    public final <T> void invoke(String name, Object[] args, HproseCallback1<T> callback, Class<T> returnType, InvokeSettings settings) {
        invoke(name, args, callback, null, returnType, settings);
    }
    public final <T> void invoke(final String name, Object[] args, final HproseCallback1<T> callback, final HproseErrorEvent errorEvent, Class<T> returnType, InvokeSettings settings) {
        if (settings == null) settings = new InvokeSettings();
        if (returnType != null) settings.setReturnType(returnType);
        ((Promise<T>) asyncInvokeHandler.handle(name, args, getContext(settings))).then(
            new Action<T>() {
                public void call(T value) throws Throwable {
                    callback.handler(value);
                }
            },
            new Action<Throwable>() {
                public void call(Throwable value) throws Throwable {
                    errorEvent.handler(name, value);
                }
            }
        );
    }

    public final void invoke(String name, Object[] args, HproseCallback<?> callback) {
        invoke(name, args, callback, null, null, null);
    }
    public final void invoke(String name, Object[] args, HproseCallback<?> callback, HproseErrorEvent errorEvent) {
        invoke(name, args, callback, errorEvent, null, null);
    }
    public final void invoke(String name, Object[] args, HproseCallback<?> callback, InvokeSettings settings) {
        invoke(name, args, callback, null, null, settings);
    }
    public final void invoke(String name, Object[] args, HproseCallback<?> callback, HproseErrorEvent errorEvent, InvokeSettings settings) {
        invoke(name, args, callback, errorEvent, null, settings);
    }

    public final <T> void invoke(String name, Object[] args, HproseCallback<T> callback, Class<T> returnType) {
        invoke(name, args, callback, null, returnType, null);
    }
    public final <T> void invoke(String name, Object[] args, HproseCallback<T> callback, HproseErrorEvent errorEvent, Class<T> returnType) {
        invoke(name, args, callback, errorEvent, returnType, null);
    }
    public final <T> void invoke(String name, Object[] args, HproseCallback<T> callback, Class<T> returnType, InvokeSettings settings) {
        invoke(name, args, callback, null, returnType, settings);
    }
    public final <T> void invoke(final String name, final Object[] args, final HproseCallback<T> callback, final HproseErrorEvent errorEvent, Class<T> returnType, InvokeSettings settings) {
        if (settings == null) settings = new InvokeSettings();
        if (returnType != null) settings.setReturnType(returnType);
        ((Promise<T>) asyncInvokeHandler.handle(name, args, getContext(settings))).then(
            new Action<T>() {
                public void call(T value) throws Throwable {
                    callback.handler(value, args);
                }
            },
            new Action<Throwable>() {
                public void call(Throwable value) throws Throwable {
                    errorEvent.handler(name, value);
                }
            }
        );
    }

    public final Object invoke(String name) throws IOException {
        return invoke(name, nullArgs, (Class<?>)null, null);
    }
    public final Object invoke(String name, InvokeSettings settings) throws IOException {
        return invoke(name, nullArgs, (Class<?>)null, settings);
    }
    public final Object invoke(String name, Object[] args) throws IOException {
        return invoke(name, args, (Class<?>)null, null);
    }
    public final Object invoke(String name, Object[] args, InvokeSettings settings) throws IOException {
        return invoke(name, args, (Class<?>)null, settings);
    }

    public final <T> T invoke(String name, Class<T> returnType) throws IOException {
        return invoke(name, nullArgs, returnType, null);
    }
    public final <T> T invoke(String name, Class<T> returnType, InvokeSettings settings) throws IOException {
        return invoke(name, nullArgs, returnType, settings);
    }

    public final <T> T invoke(String name, Object[] args, Class<T> returnType) throws IOException {
        return invoke(name, args, returnType, null);
    }
    public final <T> T invoke(String name, Object[] args, Class<T> returnType, InvokeSettings settings) throws IOException {
        if (settings == null) settings = new InvokeSettings();
        if (returnType != null) settings.setReturnType(returnType);
        Type type = settings.getReturnType();
        Class<?> cls = ClassUtil.toClass(type);
        if (Promise.class.equals(cls)) {
            return (T)asyncInvoke(name, args, type, settings);
        }
        if (Future.class.equals(cls)) {
           return (T)asyncInvoke(name, args, type, settings).toFuture();
        }
        return (T)invokeHandler.handle(name, args, getContext(settings));
    }

    private Promise<?> asyncInvoke(String name, Object[] args, Type type, InvokeSettings settings) {
        if (type instanceof ParameterizedType) {
            type = ((ParameterizedType)type).getActualTypeArguments()[0];
            if (void.class.equals(type) || Void.class.equals(type)) {
                type = null;
            }
        }
        else {
            type = null;
        }
        settings.setReturnType(type);
        return asyncInvokeHandler.handle(name, args, getContext(settings));
    }

}
