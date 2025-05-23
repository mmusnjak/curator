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

package org.apache.curator.framework.recipes.watch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.compatibility.CuratorTestBase;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(CuratorTestBase.zk36Group)
public class TestPersistentWatcher extends CuratorTestBase {
    @Test
    public void testConnectionLostRecursive() throws Exception {
        internalTest(true);
    }

    @Test
    public void testConnectionLost() throws Exception {
        internalTest(false);
    }

    @Test
    public void testNamespacedWatching() throws Exception {
        BlockingQueue<WatchedEvent> events = new LinkedBlockingQueue<>();

        try (CuratorFramework client = CuratorFrameworkFactory.newClient(
                server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1))) {
            client.start();
            // given: connected curator client
            client.blockUntilConnected();

            // given: started persistent watcher under namespaced facade
            PersistentWatcher persistentWatcher = new PersistentWatcher(client.usingNamespace("top"), "/main", true);
            persistentWatcher.getListenable().addListener(events::add);
            persistentWatcher.start();

            // when: create paths
            client.create().forPath("/top/main");
            client.create().forPath("/top/main/a");

            // then: receive node watch events
            WatchedEvent event1 = events.poll(5, TimeUnit.SECONDS);
            assertNotNull(event1);
            assertEquals(Watcher.Event.EventType.NodeCreated, event1.getType());
            assertEquals("/main", event1.getPath());

            WatchedEvent event2 = events.poll(5, TimeUnit.SECONDS);
            assertNotNull(event2);
            assertEquals(Watcher.Event.EventType.NodeCreated, event2.getType());
            assertEquals("/main/a", event2.getPath());
        }

        // when: curator client closed
        // then: listener get Closed notification
        WatchedEvent event = events.poll(5, TimeUnit.SECONDS);
        assertNotNull(event);
        assertEquals(Watcher.Event.EventType.None, event.getType());
        assertEquals(Watcher.Event.KeeperState.Closed, event.getState());
    }

    @Test
    public void testConcurrentClientClose() throws Exception {
        BlockingQueue<WatchedEvent> events = new LinkedBlockingQueue<>();

        // given: started curator client
        CuratorFramework client = CuratorFrameworkFactory.newClient(
                server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1));
        client.start();

        // given: started persistent watcher
        PersistentWatcher persistentWatcher = new PersistentWatcher(client, "/top/main", true);
        persistentWatcher.getListenable().addListener(events::add);
        persistentWatcher.start();

        // when: curator client closed
        client.close();

        // then: listener get Closed notification
        WatchedEvent event = events.poll(5, TimeUnit.SECONDS);
        assertNotNull(event);
        assertEquals(Watcher.Event.EventType.None, event.getType());
        assertEquals(Watcher.Event.KeeperState.Closed, event.getState());
    }

    @Test
    public void testAfterClientClose() throws Exception {
        BlockingQueue<WatchedEvent> events = new LinkedBlockingQueue<>();

        // given: closed client
        CuratorFramework client = CuratorFrameworkFactory.newClient(
                server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1));
        client.start();
        client.close();

        // when: start persistent watcher
        try (PersistentWatcher persistentWatcher = new PersistentWatcher(client, "/top/main", true)) {
            persistentWatcher.getListenable().addListener(events::add);
            persistentWatcher.start();
        }

        // then: listener get Closed notification
        WatchedEvent event = events.poll(5, TimeUnit.SECONDS);
        assertNotNull(event);
        assertEquals(Watcher.Event.EventType.None, event.getType());
        assertEquals(Watcher.Event.KeeperState.Closed, event.getState());
    }

    private void internalTest(boolean recursive) throws Exception {
        try (CuratorFramework client = CuratorFrameworkFactory.newClient(
                server.getConnectString(), timing.session(), timing.connection(), new RetryOneTime(1))) {
            CountDownLatch lostLatch = new CountDownLatch(1);
            CountDownLatch reconnectedLatch = new CountDownLatch(1);
            client.start();
            client.getConnectionStateListenable().addListener((__, newState) -> {
                if (newState == ConnectionState.LOST) {
                    lostLatch.countDown();
                } else if (newState == ConnectionState.RECONNECTED) {
                    reconnectedLatch.countDown();
                }
            });

            try (PersistentWatcher persistentWatcher = new PersistentWatcher(client, "/top/main", recursive)) {
                persistentWatcher.start();

                BlockingQueue<WatchedEvent> events = new LinkedBlockingQueue<>();
                persistentWatcher.getListenable().addListener(events::add);

                client.create().creatingParentsIfNeeded().forPath("/top/main/a");
                assertEquals(timing.takeFromQueue(events).getPath(), "/top/main");
                if (recursive) {
                    assertEquals(timing.takeFromQueue(events).getPath(), "/top/main/a");
                } else {
                    assertEquals(timing.takeFromQueue(events).getPath(), "/top/main"); // child added
                }

                server.stop();
                assertEquals(timing.takeFromQueue(events).getState(), Watcher.Event.KeeperState.Disconnected);
                assertTrue(timing.awaitLatch(lostLatch));

                server.restart();
                assertTrue(timing.awaitLatch(reconnectedLatch));

                timing.sleepABit(); // time to allow watcher to get reset
                events.clear();

                if (recursive) {
                    client.setData().forPath("/top/main/a", "foo".getBytes());
                    assertEquals(timing.takeFromQueue(events).getType(), Watcher.Event.EventType.NodeDataChanged);
                }
                client.setData().forPath("/top/main", "bar".getBytes());
                assertEquals(timing.takeFromQueue(events).getPath(), "/top/main");
            }
        }
    }
}
