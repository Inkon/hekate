/*
 * Copyright 2017 The Hekate Project
 *
 * The Hekate Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.hekate;

import io.hekate.cluster.ClusterAddress;
import io.hekate.cluster.ClusterNode;
import io.hekate.cluster.ClusterNodeId;
import io.hekate.cluster.ClusterNodeService;
import io.hekate.cluster.internal.DefaultClusterNode;
import io.hekate.cluster.internal.DefaultClusterNodeBuilder;
import io.hekate.codec.Codec;
import io.hekate.codec.CodecFactory;
import io.hekate.codec.DataReader;
import io.hekate.codec.DataWriter;
import io.hekate.codec.SingletonCodecFactory;
import io.hekate.core.HekateException;
import io.hekate.core.internal.util.HekateThreadFactory;
import io.hekate.core.internal.util.Utils;
import java.io.File;
import java.io.IOException;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.rules.Timeout;
import org.junit.runner.Description;
import org.junit.runners.model.TestTimedOutException;
import org.mockito.internal.util.collections.Iterables;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public abstract class HekateTestBase {
    /**
     * Test task for {@link #sayTime(String, TestTask)}, {@link #expect(Class, String, TestTask)}, etc.
     */
    public interface TestTask {
        void execute() throws Exception;
    }

    /**
     * Task for {@link #repeat(int, IterationTask)}.
     */
    public interface IterationTask {
        void execute(int i) throws Exception;
    }

    /**
     * Task for {@link #runParallel(int, int, ParallelTask)}.
     */
    public interface ParallelTask {
        void execute(ParallelTaskStatus status) throws Exception;
    }

    /**
     * Status of {@link #runParallel(int, int, ParallelTask)} task.
     */
    public static class ParallelTaskStatus {
        private final int thread;

        public ParallelTaskStatus(int thread) {
            this.thread = thread;
        }

        public int getThread() {
            return thread;
        }
    }

    /** Unchecked error for tests. */
    public static class TestAssertionError extends AssertionError {
        private static final long serialVersionUID = 1;

        public TestAssertionError(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /** Checked error for tests. */
    public static class TestHekateException extends HekateException {
        private static final long serialVersionUID = 1;

        public TestHekateException(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /** Maximum timeout in {@link TimeUnit#SECONDS} for each individual test case (see {@link #testTimeoutRule}). */
    public static final int MAX_TEST_TIMEOUT_SEC = 2;

    /** Maximum number of loop for {@link #busyWait(String, Callable)}. */
    public static final int BUSY_WAIT_LOOPS = 200;

    /** Interval between loops of {@link #busyWait(String, Callable)}. */
    public static final int BUSY_WAIT_INTERVAL = 25;

    /** Common prefix for messages of expected errors that were produced by tests. */
    public static final String TEST_ERROR_PREFIX = "*** RELAX:)";

    /** Common message for expected errors that were produced by tests. */
    public static final String TEST_ERROR_MESSAGE = TEST_ERROR_PREFIX + " This exception was created by a test case.";

    /** Singleton for expected errors ...{@link #TEST_ERROR_MESSAGE RELAX:)}. */
    public static final AssertionError TEST_ERROR = new TestAssertionError(TEST_ERROR_MESSAGE);

    /** Timestamp format for test messages (see {@link #say(Object)}). */
    public static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** State of nested {@link #repeat(int, IterationTask)}. */
    private static final ThreadLocal<String> REPEAT_STATE = new ThreadLocal<>();

    /** Cache to prevent reentrancy in {@link #assertAllThreadsStopped()}. */
    private static final Set<String> FAILED_THREAD_NAMES_CACHE = new HashSet<>();

    /** Cache for {@link #selectLocalAddress()}. */
    private static final AtomicReference<InetAddress> LOCAL_ADDRESS_CACHE = new AtomicReference<>();

    /** Cache for {@link #getNetworkInterfaces()}. */
    private static final AtomicReference<List<NetworkInterface>> NETWORK_INTERFACES_CACHE = new AtomicReference<>();

    /** Prefixes for {@link Thread#getName() thread names} that should NOT be checked by {@link #assertAllThreadsStopped()}. */
    private static final List<String> KNOWN_THREAD_PREFIXES = new ArrayList<>();

    static {
        // JVM threads.
        KNOWN_THREAD_PREFIXES.add("threadDeathWatcher".toLowerCase());
        KNOWN_THREAD_PREFIXES.add("globalEventExecutor".toLowerCase());
        KNOWN_THREAD_PREFIXES.add("Time-limited test".toLowerCase());
        KNOWN_THREAD_PREFIXES.add("Reference Handler".toLowerCase());
        KNOWN_THREAD_PREFIXES.add("Monitor Ctrl-Break".toLowerCase());
        KNOWN_THREAD_PREFIXES.add("Signal Dispatcher".toLowerCase());
        KNOWN_THREAD_PREFIXES.add("Finalizer".toLowerCase());
        KNOWN_THREAD_PREFIXES.add("Attach Listener".toLowerCase());
        KNOWN_THREAD_PREFIXES.add("ForkJoinPool.".toLowerCase());
        KNOWN_THREAD_PREFIXES.add("main".toLowerCase());

        // Maven surefire plugin threads.
        KNOWN_THREAD_PREFIXES.add("surefire-".toLowerCase());
        KNOWN_THREAD_PREFIXES.add("last-ditch-daemon".toLowerCase());

        // Third party libraries that start threads but do not allow to stop them.
        KNOWN_THREAD_PREFIXES.add("Okio Watchdog".toLowerCase());
        KNOWN_THREAD_PREFIXES.add("OkHttp ConnectionPool".toLowerCase());
        KNOWN_THREAD_PREFIXES.add("com.google.inject.internal.util.$Finalizer".toLowerCase());
        KNOWN_THREAD_PREFIXES.add("Keep-Alive-Timer".toLowerCase());
        KNOWN_THREAD_PREFIXES.add("H2 Close".toLowerCase());
    }

    /** Test timeout rule (see {@link #MAX_TEST_TIMEOUT_SEC}). */
    @Rule
    public final Timeout testTimeoutRule = new Timeout(MAX_TEST_TIMEOUT_SEC, TimeUnit.MINUTES);

    /** Prints info about the currently running test. */
    @Rule
    public final TestRule testInfoRule = new TestWatcher() {
        private final ThreadLocal<Long> time = new ThreadLocal<>();

        @Override
        protected void starting(Description description) {
            LocalDateTime now = LocalDateTime.now();

            time.set(now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());

            System.out.println("#######################################");
            System.out.println("# Starting: " + description.getDisplayName() + " [start-time=" + TIMESTAMP_FORMAT.format(now) + ']');
            System.out.println("#--------------------------------------");
        }

        @Override
        protected void finished(Description description) {
            String durationInfo = "";

            if (time.get() != null) {
                long duration = System.currentTimeMillis() - time.get();

                if (duration >= 0) {
                    durationInfo = " [duration=" + duration + "ms]";
                }

                time.remove();
            }

            System.out.println("#--------------------------------------");
            System.out.println("# Finished: " + description.getDisplayName() + durationInfo);
            System.out.println("#######################################");
            System.out.println();
            System.out.println();
        }

        @Override
        protected void failed(Throwable e, Description description) {
            if (e instanceof TestTimedOutException) {
                System.out.println(threadDump());
            }

            super.failed(e, description);
        }
    };

    /** Sequence number to generate network ports for tests. */
    private final AtomicInteger portSeq = new AtomicInteger(10000);

    /** Executor for {@link #runAsync(Callable)}. */
    private volatile ExecutorService async;

    @BeforeClass
    public static void ensureTestProperties() {
        // Make sure that test properties can be loaded.
        HekateTestProps.get("test");
    }

    public static void busyWait(String comment, Callable<Boolean> condition) throws Exception {
        for (int i = 0; i < BUSY_WAIT_LOOPS; i++) {
            if (condition.call()) {
                return;
            }

            Thread.sleep(BUSY_WAIT_INTERVAL);
        }

        fail("Failed to await for " + comment);
    }

    public static CountDownLatch await(CountDownLatch latch) {
        return await(latch, 3);
    }

    public static CountDownLatch await(CountDownLatch latch, int seconds) {
        try {
            boolean success = latch.await(seconds, TimeUnit.SECONDS);

            if (!success) {
                System.out.println(threadDump());
            }

            assertTrue(success);
        } catch (InterruptedException e) {
            fail(e.toString());
        }

        return latch;
    }

    public static <T> T get(Future<T> future) throws ExecutionException, InterruptedException, TimeoutException {
        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.out.println(threadDump());

            throw e;
        }
    }

    @After
    public void cleanup() throws Exception {
        if (async != null) {
            async.shutdownNow();

            async.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);

            async = null;
        }

        assertAllThreadsStopped();
    }

    protected static ClusterNodeId newNodeId() {
        return new ClusterNodeId();
    }

    protected static ClusterNodeId newNodeId(int order) {
        return new ClusterNodeId(0, order);
    }

    protected static void say(Object msg) {
        String sep = System.lineSeparator();

        System.out.println(sep + "*** [" + TIMESTAMP_FORMAT.format(LocalDateTime.now()) + "]: " + msg + sep);
    }

    protected static void sayHeader(Object msg) {
        String sep = System.lineSeparator();

        System.out.println(sep
            + "***************************" + sep
            + "*** [" + TIMESTAMP_FORMAT.format(LocalDateTime.now()) + "]: " + msg + sep
            + "***************************"
        );
    }

    protected static void sleep(long time) {
        if (time > 20) {
            say("Will sleep for " + time + " ms...");
        }

        try {
            Thread.sleep(time);

            if (time > 20) {
                say("Done sleeping.");
            }
        } catch (InterruptedException e) {
            say("!!!! Thread was interrupted while sleeping [name=" + Thread.currentThread().getName() + ']');
        }
    }

    protected static String getStacktrace(Throwable error) {
        return Utils.getStacktrace(error);
    }

    protected static List<NetworkInterface> getNetworkInterfaces() throws SocketException {
        List<NetworkInterface> cached = NETWORK_INTERFACES_CACHE.get();

        if (cached == null) {
            cached = Collections.list(NetworkInterface.getNetworkInterfaces());

            NETWORK_INTERFACES_CACHE.set(cached);
        }

        return cached;
    }

    protected static String threadDump() {
        ThreadInfo[] threads = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);

        String nl = System.lineSeparator();

        StringBuilder buf = new StringBuilder();

        buf.append("----------------------- Thread dump start ---------------------------").append(nl);

        for (ThreadInfo thread : threads) {
            buf.append(toString(thread)).append(nl);
        }

        buf.append("----------------------- Thread dump end -----------------------------").append(nl);

        return buf.toString();
    }

    protected void assertAllThreadsStopped() throws InterruptedException {
        Set<String> failedThreads = new HashSet<>();

        int attempts = 3;

        for (int i = 0; i < attempts; i++) {
            ThreadMXBean jmx = ManagementFactory.getThreadMXBean();

            ThreadInfo[] threads = jmx.getThreadInfo(jmx.getAllThreadIds());

            for (ThreadInfo t : threads) {
                if (t != null && t.getThreadState() != Thread.State.TERMINATED) {
                    String name = t.getThreadName();

                    if (!FAILED_THREAD_NAMES_CACHE.contains(name)) {
                        String nameLower = name.toLowerCase();

                        if (KNOWN_THREAD_PREFIXES.stream().noneMatch(nameLower::startsWith)) {
                            failedThreads.add(name);
                        }
                    }
                }
            }

            if (failedThreads.isEmpty()) {
                break;
            }

            if (i < attempts - 1) {
                failedThreads.clear();

                Thread.sleep(BUSY_WAIT_INTERVAL);
            }
        }

        if (!failedThreads.isEmpty()) {
            FAILED_THREAD_NAMES_CACHE.addAll(failedThreads);
        }

        assertTrue(failedThreads.toString(), failedThreads.isEmpty());
    }

    protected void runParallel(int threads, int iterations, ParallelTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads, new HekateThreadFactory("TestParallel"));

        List<Future<?>> futures = new ArrayList<>(threads);

        CountDownLatch start = new CountDownLatch(1);

        for (int i = 0; i < threads; i++) {
            ParallelTaskStatus status = new ParallelTaskStatus(i);

            futures.add(pool.submit(() -> {
                start.await();

                for (int j = 0; j < iterations; j++) {
                    task.execute(status);
                }

                return null;
            }));
        }

        start.countDown();

        try {
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }

        pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    protected ClusterNode newNode() throws Exception {
        return newNode(null, false, null, null);
    }

    protected ClusterNode newNode(int order) throws Exception {
        return newNode(null, false, null, newAddress(order));
    }

    protected ClusterNode newNode(ClusterNodeId nodeId) throws Exception {
        return newNode(nodeId, false, null, null);
    }

    protected ClusterNode newNode(Consumer<DefaultClusterNodeBuilder> transform) throws Exception {
        return newNode(transform, null);
    }

    protected ClusterNode newNode(Consumer<DefaultClusterNodeBuilder> transform, ClusterAddress addr) throws Exception {
        return newNode(false, transform, addr);
    }

    protected ClusterNode newNode(boolean local, Consumer<DefaultClusterNodeBuilder> transform, ClusterAddress addr) throws Exception {
        return newNode(null, local, transform, addr);
    }

    protected ClusterNode newNode(ClusterNodeId nodeId, boolean local, Consumer<DefaultClusterNodeBuilder> transform, ClusterAddress addr)
        throws Exception {
        return newNode(nodeId, local, transform, addr, DefaultClusterNode.NON_JOINED_ORDER);
    }

    protected ClusterNode newNode(ClusterNodeId nodeId, boolean local, Consumer<DefaultClusterNodeBuilder> transform, ClusterAddress addr,
        int joinOrder) throws Exception {
        if (nodeId == null) {
            nodeId = newNodeId();
        }

        if (addr == null) {
            InetSocketAddress sockAddr = newSocketAddress();

            addr = new ClusterAddress(sockAddr, nodeId);
        }

        String name = "node" + addr.getSocket().getPort();

        DefaultClusterNodeBuilder builder = new DefaultClusterNodeBuilder()
            .withAddress(addr)
            .withName(name)
            .withLocalNode(local)
            .withJoinOrder(joinOrder);

        if (transform == null) {
            Set<String> roles = new HashSet<>();

            roles.add("test-role-1");
            roles.add("test-role-2");
            roles.add("test-role-3");

            Map<String, String> props = new HashMap<>();

            props.put("test-prop-1", "test-val-1");
            props.put("test-prop-2", "test-val-2");
            props.put("test-prop-3", "test-val-3");

            Map<String, ClusterNodeService> services = Collections.emptyMap();

            return builder.withRoles(roles).withProperties(props).withServices(services).createNode();
        } else {
            transform.accept(builder);

            return builder.createNode();
        }
    }

    protected ClusterNode newLocalNode() throws Exception {
        return newNode(true, null, null);
    }

    protected ClusterNode newLocalNode(ClusterNodeId nodeId) throws Exception {
        return newNode(nodeId, true, null, null);
    }

    protected ClusterNode newLocalNode(Consumer<DefaultClusterNodeBuilder> transform) throws Exception {
        return newNode(true, transform, null);
    }

    protected int newTcpPort() {
        return portSeq.incrementAndGet();
    }

    protected InetSocketAddress newSocketAddress() throws Exception {
        return newSocketAddress(newTcpPort());
    }

    protected InetSocketAddress newSocketAddress(int port) throws Exception {
        InetAddress address = selectLocalAddress();

        return newSocketAddress(address.getHostAddress(), port);
    }

    protected InetSocketAddress newSocketAddress(String host, int port) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(host), port);
    }

    protected ClusterAddress newAddress(int order) throws Exception {
        InetSocketAddress addr = newSocketAddress();

        return new ClusterAddress(addr, newNodeId(order));
    }

    protected ClusterAddress newAddress(InetSocketAddress addr) throws UnknownHostException {
        return new ClusterAddress(addr, newNodeId());
    }

    protected CodecFactory<String> createStringCodecFactory() {
        return new SingletonCodecFactory<>(createStringCodec());
    }

    protected Codec<String> createStringCodec() {
        return new Codec<String>() {
            @Override
            public boolean isStateful() {
                return false;
            }

            @Override
            public String decode(DataReader in) throws IOException {
                return in.readUTF();
            }

            @Override
            public void encode(String message, DataWriter out) throws IOException {
                out.writeUTF(message);
            }
        };
    }

    protected void expect(Class<? extends Throwable> error, TestTask task) {
        expect(error, null, task);
    }

    protected void expect(Class<? extends Throwable> error, String message, TestTask task) {
        try {
            task.execute();

            fail("Failure of type " + error.getName() + " was expected.");
        } catch (AssertionError e) {
            throw e;
        } catch (Throwable t) {
            assertSame(Utils.getStacktrace(t), error, t.getClass());

            if (message != null) {
                assertNotNull(t.toString(), t.getMessage());
                assertTrue(t.getMessage(), t.getMessage().contains(message));
            }
        }
    }

    protected void expectErrorMessage(Class<? extends Throwable> error, String message, TestTask task) {
        try {
            task.execute();

            fail("Failure of type " + error.getName() + " was expected.");
        } catch (AssertionError e) {
            throw e;
        } catch (Throwable t) {
            assertSame(Utils.getStacktrace(t), error, t.getClass());

            assertNotNull(t.toString(), t.getMessage());
            assertEquals(message, t.getMessage());
        }
    }

    @SafeVarargs
    protected final <T> Set<T> toSet(T... nodes) {
        return new HashSet<>(Arrays.asList(nodes));
    }

    protected long sayTime(String msg, TestTask task) throws Exception {
        say(msg);

        long t = System.currentTimeMillis();

        task.execute();

        say(msg + " [time=" + (System.currentTimeMillis() - t) + ']');

        return t;
    }

    protected void repeat(int iterations, IterationTask task) throws Exception {
        String parentName = REPEAT_STATE.get();

        try {
            String baseName = parentName == null ? "" : parentName + '-';

            for (int i = 0; i < iterations; i++) {
                String name = baseName + i;

                REPEAT_STATE.set(name);

                if (parentName == null) {
                    say("Iteration: " + name);
                }

                task.execute(i);
            }
        } finally {
            REPEAT_STATE.set(parentName);
        }
    }

    protected synchronized <V> Future<V> runAsync(Callable<V> task) {
        if (async == null) {
            async = Executors.newCachedThreadPool(new HekateThreadFactory("TestAsync"));
        }

        return async.submit(task);
    }

    protected <V> V runAsyncAndGet(Callable<V> task) throws InterruptedException, ExecutionException, TimeoutException {
        return get(runAsync(task));
    }

    protected void deleteDirectory(File dir) {
        File[] files = dir.listFiles();

        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirectory(f);
                }

                f.delete();
            }
        }

        dir.delete();
    }

    protected void assertValidUtilityClass(Class<?> type) throws Exception {
        assertTrue(Modifier.isFinal(type.getModifiers()));
        assertEquals(1, type.getDeclaredConstructors().length);

        Constructor<?> ctor = type.getDeclaredConstructor();

        assertFalse(ctor.isAccessible());
        assertTrue(Modifier.isPrivate(ctor.getModifiers()));

        ctor.setAccessible(true);

        try {
            ctor.newInstance();
        } finally {
            ctor.setAccessible(false);
        }

        for (Method m : type.getDeclaredMethods()) {
            assertTrue(Modifier.isStatic(m.getModifiers()));
        }
    }

    private InetAddress selectLocalAddress() throws Exception {
        InetAddress cached = LOCAL_ADDRESS_CACHE.get();

        if (cached == null) {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();

            for (NetworkInterface nif : Iterables.toIterable(interfaces)) {
                if (nif.isUp() && !nif.isLoopback() && nif.supportsMulticast()) {
                    for (InetAddress addr : Iterables.toIterable(nif.getInetAddresses())) {
                        if (!addr.isLinkLocalAddress()) {
                            cached = addr;

                            break;
                        }
                    }

                    if (cached != null) {
                        break;
                    }
                }
            }

            if (cached == null) {
                cached = InetAddress.getLocalHost();
            }

            LOCAL_ADDRESS_CACHE.set(cached);
        }

        return cached;
    }

    private static String toString(ThreadInfo t) {
        StringBuilder sb = new StringBuilder("\"" + t.getThreadName() + "\" Id=" + t.getThreadId() + " " + t.getThreadState());

        if (t.getLockName() != null) {
            sb.append(" on ").append(t.getLockName());
        }

        if (t.getLockOwnerName() != null) {
            sb.append(" owned by \"").append(t.getLockOwnerName()).append("\" Id=").append(t.getLockOwnerId());
        }

        if (t.isSuspended()) {
            sb.append(" (suspended)");
        }

        if (t.isInNative()) {
            sb.append(" (in native)");
        }

        sb.append('\n');

        StackTraceElement[] stackTrace = t.getStackTrace();

        for (int i = 0; i < stackTrace.length; i++) {
            StackTraceElement ste = stackTrace[i];

            sb.append("\tat ").append(ste.toString());
            sb.append('\n');

            if (i == 0 && t.getLockInfo() != null) {
                Thread.State ts = t.getThreadState();

                switch (ts) {
                    case NEW:
                    case RUNNABLE:
                    case TERMINATED: {
                        break;
                    }
                    case BLOCKED: {
                        sb.append("\t-  blocked on ").append(t.getLockInfo());
                        sb.append('\n');

                        break;
                    }
                    case WAITING: {
                        sb.append("\t-  waiting on ").append(t.getLockInfo());
                        sb.append('\n');

                        break;
                    }
                    case TIMED_WAITING: {
                        sb.append("\t-  waiting on ").append(t.getLockInfo());
                        sb.append('\n');

                        break;
                    }
                    default: {
                        throw new IllegalArgumentException("Unexpected thread state: " + ts);
                    }
                }
            }

            MonitorInfo[] lockedMonitors = t.getLockedMonitors();

            for (MonitorInfo mi : lockedMonitors) {
                if (mi.getLockedStackDepth() == i) {
                    sb.append("\t-  locked ").append(mi);
                    sb.append('\n');
                }
            }
        }

        LockInfo[] locks = t.getLockedSynchronizers();

        if (locks.length > 0) {
            sb.append("\n\tNumber of locked synchronizers = ").append(locks.length);
            sb.append('\n');

            for (LockInfo li : locks) {
                sb.append("\t- ").append(li);
                sb.append('\n');
            }
        }

        sb.append('\n');

        return sb.toString();
    }
}
