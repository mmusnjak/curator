/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.curator.framework.imps;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.curator.CuratorConnectionLossException;
import org.apache.curator.CuratorZookeeperClient;
import org.apache.curator.RetryLoop;
import org.apache.curator.drivers.OperationTrace;
import org.apache.curator.framework.AuthInfo;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.framework.api.CompressionProvider;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.curator.framework.api.CuratorEventType;
import org.apache.curator.framework.api.CuratorListener;
import org.apache.curator.framework.api.UnhandledErrorListener;
import org.apache.curator.framework.listen.Listenable;
import org.apache.curator.framework.listen.StandardListenerManager;
import org.apache.curator.framework.schema.SchemaSet;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.framework.state.ConnectionStateErrorPolicy;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.curator.framework.state.ConnectionStateManager;
import org.apache.curator.utils.DebugUtils;
import org.apache.curator.utils.EnsurePath;
import org.apache.curator.utils.ThreadUtils;
import org.apache.curator.utils.ZookeeperCompatibility;
import org.apache.curator.utils.ZookeeperFactory;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.client.ZKClientConfig;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CuratorFrameworkImpl extends CuratorFrameworkBase {
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final CuratorZookeeperClient client;
    private final StandardListenerManager<CuratorListener> listeners;
    private final StandardListenerManager<UnhandledErrorListener> unhandledErrorListeners;
    private final ThreadFactory threadFactory;
    private final int maxCloseWaitMs;
    private final BlockingQueue<OperationAndData<?>> backgroundOperations;
    private final BlockingQueue<OperationAndData<?>> forcedSleepOperations;
    private final NamespaceImpl namespace;
    private final ConnectionStateManager connectionStateManager;
    private final List<AuthInfo> authInfos;
    private final byte[] defaultData;
    private final FailedDeleteManager failedDeleteManager;
    private final FailedRemoveWatchManager failedRemoveWatcherManager;
    private final CompressionProvider compressionProvider;
    private final boolean compressionEnabled;
    private final ACLProvider aclProvider;
    private final NamespaceFacadeCache namespaceFacadeCache;
    private final boolean useContainerParentsIfAvailable;
    private final ConnectionStateErrorPolicy connectionStateErrorPolicy;
    private final AtomicLong currentInstanceIndex = new AtomicLong(-1);
    private final InternalConnectionHandler internalConnectionHandler;
    private final EnsembleTracker ensembleTracker;
    private final SchemaSet schemaSet;
    private final Executor runSafeService;
    private final ZookeeperCompatibility zookeeperCompatibility;

    private volatile ExecutorService executorService;
    private final AtomicBoolean logAsErrorConnectionErrors = new AtomicBoolean(false);

    private static final boolean LOG_ALL_CONNECTION_ISSUES_AS_ERROR_LEVEL =
            !Boolean.getBoolean(DebugUtils.PROPERTY_LOG_ONLY_FIRST_CONNECTION_ISSUE_AS_ERROR_LEVEL);

    interface DebugBackgroundListener {
        void listen(OperationAndData<?> data);
    }

    volatile DebugBackgroundListener debugListener = null;

    @VisibleForTesting
    public volatile UnhandledErrorListener debugUnhandledErrorListener = null;

    private final AtomicReference<CuratorFrameworkState> state;

    private final Object closeLock = new Object();

    public CuratorFrameworkImpl(CuratorFrameworkFactory.Builder builder) {
        ZookeeperFactory localZookeeperFactory =
                makeZookeeperFactory(builder.getZookeeperFactory(), builder.getZkClientConfig());
        this.client = new CuratorZookeeperClient(
                localZookeeperFactory,
                builder.getEnsembleProvider(),
                builder.getSessionTimeoutMs(),
                builder.getConnectionTimeoutMs(),
                builder.getWaitForShutdownTimeoutMs(),
                new Watcher() {
                    @Override
                    public void process(WatchedEvent watchedEvent) {
                        CuratorEvent event = new CuratorEventImpl(
                                CuratorFrameworkImpl.this,
                                CuratorEventType.WATCHED,
                                watchedEvent.getState().getIntValue(),
                                unfixForNamespace(watchedEvent.getPath()),
                                null,
                                null,
                                null,
                                null,
                                null,
                                watchedEvent,
                                null,
                                null);
                        processEvent(event);
                    }
                },
                builder.getRetryPolicy(),
                builder.canBeReadOnly());

        internalConnectionHandler = new StandardInternalConnectionHandler();
        listeners = StandardListenerManager.standard();
        unhandledErrorListeners = StandardListenerManager.standard();
        backgroundOperations = new DelayQueue<OperationAndData<?>>();
        forcedSleepOperations = new LinkedBlockingQueue<>();
        namespace = new NamespaceImpl(this, builder.getNamespace());
        threadFactory = getThreadFactory(builder);
        maxCloseWaitMs = builder.getMaxCloseWaitMs();
        connectionStateManager = new ConnectionStateManager(
                this,
                builder.getThreadFactory(),
                builder.getSessionTimeoutMs(),
                builder.getSimulatedSessionExpirationPercent(),
                builder.getConnectionStateListenerManagerFactory());
        compressionProvider = builder.getCompressionProvider();
        compressionEnabled = builder.compressionEnabled();
        aclProvider = builder.getAclProvider();
        state = new AtomicReference<CuratorFrameworkState>(CuratorFrameworkState.LATENT);
        useContainerParentsIfAvailable = builder.useContainerParentsIfAvailable();
        connectionStateErrorPolicy =
                Preconditions.checkNotNull(builder.getConnectionStateErrorPolicy(), "errorPolicy cannot be null");
        schemaSet = Preconditions.checkNotNull(builder.getSchemaSet(), "schemaSet cannot be null");

        byte[] builderDefaultData = builder.getDefaultData();
        defaultData = (builderDefaultData != null)
                ? Arrays.copyOf(builderDefaultData, builderDefaultData.length)
                : new byte[0];
        authInfos = buildAuths(builder);

        failedDeleteManager = new FailedDeleteManager(this);
        failedRemoveWatcherManager = new FailedRemoveWatchManager(this);
        namespaceFacadeCache = new NamespaceFacadeCache(this);

        ensembleTracker =
                builder.withEnsembleTracker() ? new EnsembleTracker(this, builder.getEnsembleProvider()) : null;

        runSafeService = makeRunSafeService(builder);
        zookeeperCompatibility = builder.getZookeeperCompatibility();
    }

    private Executor makeRunSafeService(CuratorFrameworkFactory.Builder builder) {
        if (builder.getRunSafeService() != null) {
            return builder.getRunSafeService();
        }
        ThreadFactory threadFactory = builder.getThreadFactory();
        if (threadFactory == null) {
            threadFactory = ThreadUtils.newThreadFactory("SafeNotifyService");
        }
        return Executors.newSingleThreadExecutor(threadFactory);
    }

    private List<AuthInfo> buildAuths(CuratorFrameworkFactory.Builder builder) {
        ImmutableList.Builder<AuthInfo> builder1 = ImmutableList.builder();
        if (builder.getAuthInfos() != null) {
            builder1.addAll(builder.getAuthInfos());
        }
        return builder1.build();
    }

    @Override
    public CompletableFuture<Void> runSafe(Runnable runnable) {
        return CompletableFuture.runAsync(runnable, runSafeService);
    }

    @Override
    public QuorumVerifier getCurrentConfig() {
        return (ensembleTracker != null) ? ensembleTracker.getCurrentConfig() : null;
    }

    private ZookeeperFactory makeZookeeperFactory(
            final ZookeeperFactory actualZookeeperFactory, ZKClientConfig zkClientConfig) {
        return new ZookeeperFactory() {
            @Override
            public ZooKeeper newZooKeeper(
                    String connectString, int sessionTimeout, Watcher watcher, boolean canBeReadOnly) throws Exception {
                ZooKeeper zooKeeper = actualZookeeperFactory.newZooKeeper(
                        connectString, sessionTimeout, watcher, canBeReadOnly, zkClientConfig);
                addAuthInfos(zooKeeper);
                return zooKeeper;
            }
        };
    }

    private void addAuthInfos(ZooKeeper zooKeeper) {
        for (AuthInfo auth : authInfos) {
            zooKeeper.addAuthInfo(auth.getScheme(), auth.getAuth());
        }
    }

    private ThreadFactory getThreadFactory(CuratorFrameworkFactory.Builder builder) {
        ThreadFactory threadFactory = builder.getThreadFactory();
        if (threadFactory == null) {
            threadFactory = ThreadUtils.newThreadFactory("Framework");
        }
        return threadFactory;
    }

    @Override
    public void clearWatcherReferences(Watcher watcher) {
        // NOP
    }

    @Override
    public CuratorFramework client() {
        return this;
    }

    @Override
    public CuratorFrameworkState getState() {
        return state.get();
    }

    @Override
    @Deprecated
    public boolean isStarted() {
        return state.get() == CuratorFrameworkState.STARTED;
    }

    @Override
    public boolean blockUntilConnected(int maxWaitTime, TimeUnit units) throws InterruptedException {
        return connectionStateManager.blockUntilConnected(maxWaitTime, units);
    }

    @Override
    public void blockUntilConnected() throws InterruptedException {
        blockUntilConnected(0, null);
    }

    @Override
    public ConnectionStateErrorPolicy getConnectionStateErrorPolicy() {
        return connectionStateErrorPolicy;
    }

    @Override
    public void start() {
        log.info("Starting");
        if (!state.compareAndSet(CuratorFrameworkState.LATENT, CuratorFrameworkState.STARTED)) {
            throw new IllegalStateException("Cannot be started more than once");
        }

        try {
            connectionStateManager.start(); // ordering dependency - must be called before client.start()

            final ConnectionStateListener listener = new ConnectionStateListener() {
                @Override
                public void stateChanged(CuratorFramework client, ConnectionState newState) {
                    if (ConnectionState.CONNECTED == newState || ConnectionState.RECONNECTED == newState) {
                        logAsErrorConnectionErrors.set(true);
                    }
                }

                @Override
                public boolean doNotProxy() {
                    return true;
                }
            };

            this.getConnectionStateListenable().addListener(listener);

            client.start();

            executorService = Executors.newSingleThreadScheduledExecutor(threadFactory);
            executorService.submit(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    backgroundOperationsLoop();
                    return null;
                }
            });

            if (ensembleTracker != null) {
                ensembleTracker.start();
            }

            log.info(schemaSet.toDocumentation());
        } catch (Exception e) {
            ThreadUtils.checkInterrupted(e);
            handleBackgroundOperationException(null, e);
        }
    }

    /**
     * Change state from {@link CuratorFrameworkState#STARTED} to {@link CuratorFrameworkState#STOPPED}
     * in {@link #closeLock}.
     *
     * <p>This gives us synchronized view of {@link #state} and other components in closing.</p>
     *
     * @return true if state changed by this call
     */
    private boolean closeWithLock() {
        synchronized (closeLock) {
            return state.compareAndSet(CuratorFrameworkState.STARTED, CuratorFrameworkState.STOPPED);
        }
    }

    @Override
    public void close() {
        log.debug("Closing");
        if (closeWithLock()) {
            listeners.forEach(listener -> {
                CuratorEvent event = new CuratorEventImpl(
                        CuratorFrameworkImpl.this,
                        CuratorEventType.CLOSING,
                        0,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
                try {
                    listener.eventReceived(CuratorFrameworkImpl.this, event);
                } catch (Exception e) {
                    ThreadUtils.checkInterrupted(e);
                    log.error("Exception while sending Closing event", e);
                }
            });

            if (executorService != null) {
                executorService.shutdownNow();
                try {
                    executorService.awaitTermination(maxCloseWaitMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    // Interrupted while interrupting; I give up.
                    Thread.currentThread().interrupt();
                }
            }

            if (ensembleTracker != null) {
                ensembleTracker.close();
            }
            // Operations are forbidden to queue after closing, but there are still other concurrent mutations,
            // say, un-sleeping and not fully terminated background thread. So we have to drain the queue atomically
            // to avoid duplicated close. But DelayQueue counts Delayed::getDelay, so we have to clear it up front.
            backgroundOperations.forEach(OperationAndData::clearSleep);
            Collection<OperationAndData<?>> droppedOperations = new ArrayList<>(backgroundOperations.size());
            backgroundOperations.drainTo(droppedOperations);
            droppedOperations.forEach(this::closeOperation);
            listeners.clear();
            unhandledErrorListeners.clear();
            connectionStateManager.close();
            client.close();
        }
    }

    NamespaceImpl getNamespaceImpl() {
        return namespace;
    }

    @Override
    public CuratorFramework usingNamespace(String newNamespace) {
        checkState();
        return namespaceFacadeCache.get(newNamespace);
    }

    @Override
    public Listenable<ConnectionStateListener> getConnectionStateListenable() {
        return connectionStateManager.getListenable();
    }

    @Override
    public Listenable<CuratorListener> getCuratorListenable() {
        return listeners;
    }

    @Override
    public Listenable<UnhandledErrorListener> getUnhandledErrorListenable() {
        return unhandledErrorListeners;
    }

    @Override
    public CuratorZookeeperClient getZookeeperClient() {
        return client;
    }

    @Override
    public ZookeeperCompatibility getZookeeperCompatibility() {
        return zookeeperCompatibility;
    }

    @Override
    public EnsurePath newNamespaceAwareEnsurePath(String path) {
        return namespace.newNamespaceAwareEnsurePath(path);
    }

    @Override
    public SchemaSet getSchemaSet() {
        return schemaSet;
    }

    @Override
    public boolean compressionEnabled() {
        return compressionEnabled;
    }

    ACLProvider getAclProvider() {
        return aclProvider;
    }

    @Override
    FailedDeleteManager getFailedDeleteManager() {
        return failedDeleteManager;
    }

    @Override
    FailedRemoveWatchManager getFailedRemoveWatcherManager() {
        return failedRemoveWatcherManager;
    }

    RetryLoop newRetryLoop() {
        return client.newRetryLoop();
    }

    @Override
    CompressionProvider getCompressionProvider() {
        return compressionProvider;
    }

    @Override
    boolean useContainerParentsIfAvailable() {
        return useContainerParentsIfAvailable;
    }

    <DATA_TYPE> void processBackgroundOperation(OperationAndData<DATA_TYPE> operationAndData, CuratorEvent event) {
        boolean isInitialExecution = (event == null);
        if (isInitialExecution) {
            performBackgroundOperation(operationAndData);
            return;
        }

        boolean doQueueOperation = false;
        do {
            KeeperException.Code code = KeeperException.Code.get(event.getResultCode());
            if ((code != KeeperException.Code.OK)
                    && getZookeeperClient().getRetryPolicy().allowRetry(KeeperException.create(code))) {
                doQueueOperation = checkBackgroundRetry(operationAndData, event);
                break;
            }

            if (operationAndData.getCallback() != null) {
                sendToBackgroundCallback(operationAndData, event);
                break;
            }

            processEvent(event);
        } while (false);

        if (doQueueOperation) {
            queueOperation(operationAndData);
        }
    }

    private void abortOperation(OperationAndData<?> operation, Throwable e) {
        if (operation.getCallback() == null) {
            return;
        }
        CuratorEvent event;
        if (e instanceof KeeperException) {
            event = new CuratorEventImpl(
                    this,
                    operation.getEventType(),
                    ((KeeperException) e).code().intValue(),
                    ((KeeperException) e).getPath(),
                    null,
                    operation.getContext(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        } else if (getState() == CuratorFrameworkState.STARTED) {
            event = new CuratorEventImpl(
                    this,
                    operation.getEventType(),
                    KeeperException.Code.SYSTEMERROR.intValue(),
                    null,
                    null,
                    operation.getContext(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        } else {
            event = new CuratorEventImpl(
                    this,
                    operation.getEventType(),
                    KeeperException.Code.SESSIONEXPIRED.intValue(),
                    null,
                    null,
                    operation.getContext(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        sendToBackgroundCallback(operation, event);
    }

    private void closeOperation(OperationAndData<?> operation) {
        if (operation.getCallback() == null) {
            return;
        }
        CuratorEvent event = new CuratorEventImpl(
                this,
                operation.getEventType(),
                KeeperException.Code.SESSIONEXPIRED.intValue(),
                null,
                null,
                operation.getContext(),
                null,
                null,
                null,
                null,
                null,
                null);
        sendToBackgroundCallback(operation, event);
    }

    private void requeueSleepOperation(OperationAndData<?> operationAndData) {
        operationAndData.clearSleep();
        synchronized (closeLock) {
            if (getState() == CuratorFrameworkState.STARTED) {
                if (backgroundOperations.remove(operationAndData)) {
                    // due to the internals of DelayQueue, operation must be removed/re-added so that re-sorting occurs
                    backgroundOperations.offer(operationAndData);
                } // This operation has been taken over by background thread.
                return;
            }
        }
        // Sleeping operations are queued with delay, it could have been pulled out for execution or cancellation.
        if (backgroundOperations.remove(operationAndData)) {
            closeOperation(operationAndData);
        }
    }

    /**
     * @param operationAndData operation entry
     * @return true if the operation was actually queued, false if not
     */
    <DATA_TYPE> boolean queueOperation(OperationAndData<DATA_TYPE> operationAndData) {
        synchronized (closeLock) {
            if (getState() == CuratorFrameworkState.STARTED) {
                backgroundOperations.offer(operationAndData);
                return true;
            }
        }
        closeOperation(operationAndData);
        return false;
    }

    @Override
    void logError(String reason, final Throwable e) {
        if ((reason == null) || (reason.length() == 0)) {
            reason = "n/a";
        }

        if (!Boolean.getBoolean(DebugUtils.PROPERTY_DONT_LOG_CONNECTION_ISSUES) || !(e instanceof KeeperException)) {
            if (e instanceof KeeperException.ConnectionLossException) {
                if (LOG_ALL_CONNECTION_ISSUES_AS_ERROR_LEVEL || logAsErrorConnectionErrors.compareAndSet(true, false)) {
                    log.error(reason, e);
                } else {
                    log.debug(reason, e);
                }
            } else {
                log.error(reason, e);
            }
        }

        final String localReason = reason;
        unhandledErrorListeners.forEach(l -> l.unhandledError(localReason, e));

        if (debugUnhandledErrorListener != null) {
            debugUnhandledErrorListener.unhandledError(reason, e);
        }
    }

    byte[] getDefaultData() {
        return defaultData;
    }

    NamespaceFacadeCache getNamespaceFacadeCache() {
        return namespaceFacadeCache;
    }

    void validateConnection(Watcher.Event.KeeperState state) {
        if (state == Watcher.Event.KeeperState.Disconnected) {
            internalConnectionHandler.suspendConnection(this);
        } else if (state == Watcher.Event.KeeperState.Expired) {
            connectionStateManager.addStateChange(ConnectionState.LOST);
        } else if (state == Watcher.Event.KeeperState.SyncConnected) {
            internalConnectionHandler.checkNewConnection(this);
            connectionStateManager.addStateChange(ConnectionState.RECONNECTED);
            unSleepBackgroundOperations();
        } else if (state == Watcher.Event.KeeperState.ConnectedReadOnly) {
            internalConnectionHandler.checkNewConnection(this);
            connectionStateManager.addStateChange(ConnectionState.READ_ONLY);
        }
    }

    void checkInstanceIndex() {
        long instanceIndex = client.getInstanceIndex();
        long newInstanceIndex = currentInstanceIndex.getAndSet(instanceIndex);
        if ((newInstanceIndex >= 0)
                && (instanceIndex != newInstanceIndex)) // currentInstanceIndex is initially -1 - ignore this
        {
            connectionStateManager.addStateChange(ConnectionState.LOST);
        }
    }

    boolean setToSuspended() {
        return connectionStateManager.setToSuspended();
    }

    void addStateChange(ConnectionState newConnectionState) {
        connectionStateManager.addStateChange(newConnectionState);
    }

    @Override
    EnsembleTracker getEnsembleTracker() {
        return ensembleTracker;
    }

    @VisibleForTesting
    volatile CountDownLatch debugCheckBackgroundRetryLatch;

    @VisibleForTesting
    volatile CountDownLatch debugCheckBackgroundRetryReadyLatch;

    @VisibleForTesting
    volatile KeeperException.Code injectedCode;

    @SuppressWarnings({"ThrowableResultOfMethodCallIgnored"})
    private <DATA_TYPE> boolean checkBackgroundRetry(OperationAndData<DATA_TYPE> operationAndData, CuratorEvent event) {
        boolean doRetry = false;
        if (client.getRetryPolicy()
                .allowRetry(
                        operationAndData.getThenIncrementRetryCount(),
                        operationAndData.getElapsedTimeMs(),
                        operationAndData)) {
            doRetry = true;
        } else {
            if (operationAndData.getErrorCallback() != null) {
                operationAndData.getErrorCallback().retriesExhausted(operationAndData);
            }

            if (operationAndData.getCallback() != null) {
                sendToBackgroundCallback(operationAndData, event);
            }

            KeeperException.Code code = KeeperException.Code.get(event.getResultCode());
            Exception e = null;
            try {
                e = (code != null) ? KeeperException.create(code) : null;
            } catch (Throwable t) {
                ThreadUtils.checkInterrupted(t);
            }
            if (e == null) {
                e = new Exception("Unknown result codegetResultCode()");
            }

            if (debugCheckBackgroundRetryLatch != null) // scaffolding to test CURATOR-525
            {
                if (debugCheckBackgroundRetryReadyLatch != null) {
                    debugCheckBackgroundRetryReadyLatch.countDown();
                }
                try {
                    debugCheckBackgroundRetryLatch.await();
                    if (injectedCode != null) {
                        code = injectedCode;
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }

            validateConnection(FrameworkUtils.codeToState(code));
            logError("Background operation retry gave up", e);
        }
        return doRetry;
    }

    private <DATA_TYPE> void sendToBackgroundCallback(
            OperationAndData<DATA_TYPE> operationAndData, CuratorEvent event) {
        try {
            operationAndData.getCallback().processResult(this, event);
        } catch (Exception e) {
            ThreadUtils.checkInterrupted(e);
            // This operation is already completed, and we don't retry a completed operation.
            handleBackgroundOperationException(null, e);
        }
    }

    private <DATA_TYPE> void handleBackgroundOperationException(
            OperationAndData<DATA_TYPE> operationAndData, Throwable e) {
        do {
            if ((operationAndData != null)
                    && getZookeeperClient().getRetryPolicy().allowRetry(e)) {
                if (!Boolean.getBoolean(DebugUtils.PROPERTY_DONT_LOG_CONNECTION_ISSUES)) {
                    log.debug("Retry-able exception received", e);
                }
                if (client.getRetryPolicy()
                        .allowRetry(
                                operationAndData.getThenIncrementRetryCount(),
                                operationAndData.getElapsedTimeMs(),
                                operationAndData)) {
                    if (!Boolean.getBoolean(DebugUtils.PROPERTY_DONT_LOG_CONNECTION_ISSUES)) {
                        log.debug("Retrying operation");
                    }
                    queueOperation(operationAndData);
                    break;
                } else {
                    if (!Boolean.getBoolean(DebugUtils.PROPERTY_DONT_LOG_CONNECTION_ISSUES)) {
                        log.debug("Retry policy did not allow retry");
                    }
                    if (operationAndData.getErrorCallback() != null) {
                        operationAndData.getErrorCallback().retriesExhausted(operationAndData);
                    }
                }
            }

            if (operationAndData != null) {
                abortOperation(operationAndData, e);
            }

            logError("Background exception was not retry-able or retry gave up", e);
        } while (false);
    }

    private void backgroundOperationsLoop() {
        try {
            while (state.get() == CuratorFrameworkState.STARTED) {
                OperationAndData<?> operationAndData;
                try {
                    operationAndData = backgroundOperations.take();
                    if (debugListener != null) {
                        debugListener.listen(operationAndData);
                    }
                    performBackgroundOperation(operationAndData);
                } catch (InterruptedException e) {
                    // swallow the interrupt as it's only possible from either a background
                    // operation and, thus, doesn't apply to this loop or the instance
                    // is being closed in which case the while test will get it
                }
            }
        } finally {
            log.info("backgroundOperationsLoop exiting");
        }
    }

    void performBackgroundOperation(OperationAndData<?> operationAndData) {
        try {
            if (!operationAndData.isConnectionRequired() || client.isConnected()) {
                operationAndData.callPerformBackgroundOperation();
                return;
            }

            client.getZooKeeper(); // important - allow connection resets, timeouts, etc. to occur
            if (operationAndData.getElapsedTimeMs() < client.getConnectionTimeoutMs()) {
                sleepAndQueueOperation(operationAndData);
                return;
            }

            /*
             * Fix edge case reported as CURATOR-52. Connection timeout is detected when the initial (or previously failed) connection
             * cannot be re-established. This needs to be run through the retry policy and callbacks need to get invoked, etc.
             */
            CuratorEvent event = new CuratorEventImpl(
                    this,
                    operationAndData.getEventType(),
                    KeeperException.Code.CONNECTIONLOSS.intValue(),
                    null,
                    null,
                    operationAndData.getContext(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
            if (checkBackgroundRetry(operationAndData, event)) {
                queueOperation(operationAndData);
            } else {
                logError("Background retry gave up", new CuratorConnectionLossException());
            }
        } catch (Throwable e) {
            ThreadUtils.checkInterrupted(e);
            handleBackgroundOperationException(operationAndData, e);
        }
    }

    @VisibleForTesting
    volatile long sleepAndQueueOperationSeconds = 1;

    private void sleepAndQueueOperation(OperationAndData<?> operationAndData) throws InterruptedException {
        operationAndData.sleepFor(sleepAndQueueOperationSeconds, TimeUnit.SECONDS);
        if (queueOperation(operationAndData)) {
            forcedSleepOperations.add(operationAndData);
        }
    }

    private void unSleepBackgroundOperations() {
        Collection<OperationAndData<?>> drain = new ArrayList<>(forcedSleepOperations.size());
        forcedSleepOperations.drainTo(drain);
        log.debug("Clearing sleep for {} operations", drain.size());
        drain.forEach(this::requeueSleepOperation);
    }

    private void processEvent(final CuratorEvent curatorEvent) {
        if (curatorEvent.getType() == CuratorEventType.WATCHED) {
            validateConnection(curatorEvent.getWatchedEvent().getState());
        }

        listeners.forEach(listener -> {
            try {
                OperationTrace trace = client.startAdvancedTracer("EventListener");
                listener.eventReceived(CuratorFrameworkImpl.this, curatorEvent);
                trace.commit();
            } catch (Exception e) {
                ThreadUtils.checkInterrupted(e);
                logError("Event listener threw exception", e);
            }
        });
    }
}
