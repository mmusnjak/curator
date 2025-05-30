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

package org.apache.curator.x.async.modeled;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import com.google.common.collect.Sets;
import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.framework.schema.Schema;
import org.apache.curator.framework.schema.SchemaSet;
import org.apache.curator.framework.schema.SchemaViolation;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.x.async.AsyncCuratorFramework;
import org.apache.curator.x.async.AsyncStage;
import org.apache.curator.x.async.api.CreateOption;
import org.apache.curator.x.async.api.DeleteOption;
import org.apache.curator.x.async.modeled.models.TestModel;
import org.apache.curator.x.async.modeled.models.TestNewerModel;
import org.apache.curator.x.async.modeled.versioned.Versioned;
import org.apache.curator.x.async.modeled.versioned.VersionedModeledFramework;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.server.auth.DigestAuthenticationProvider;
import org.junit.jupiter.api.Test;

public class TestModeledFramework extends TestModeledFrameworkBase {
    @Test
    public void testCrud() {
        TestModel rawModel = new TestModel("John", "Galt", "1 Galt's Gulch", 42, BigInteger.valueOf(1));
        TestModel rawModel2 = new TestModel("Wayne", "Rooney", "Old Trafford", 10, BigInteger.valueOf(1));
        ModeledFramework<TestModel> client = ModeledFramework.wrap(async, modelSpec);
        AsyncStage<String> stage = client.set(rawModel);
        assertNull(stage.event());
        complete(stage, (s, e) -> assertNotNull(s));
        complete(client.read(), (model, e) -> assertEquals(model, rawModel));
        complete(client.update(rawModel2));
        complete(client.read(), (model, e) -> assertEquals(model, rawModel2));
        complete(client.delete());
        complete(client.checkExists(), (stat, e) -> assertNull(stat));
    }

    @Test
    public void testBackwardCompatibility() {
        TestNewerModel rawNewModel =
                new TestNewerModel("John", "Galt", "1 Galt's Gulch", 42, BigInteger.valueOf(1), 100);
        ModeledFramework<TestNewerModel> clientForNew = ModeledFramework.wrap(async, newModelSpec);
        complete(clientForNew.set(rawNewModel), (s, e) -> assertNotNull(s));

        ModeledFramework<TestModel> clientForOld = ModeledFramework.wrap(async, modelSpec);
        complete(clientForOld.read(), (model, e) -> assertTrue(rawNewModel.equalsOld(model)));
    }

    @Test
    public void testWatched() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        ModeledFramework<TestModel> client =
                ModeledFramework.builder(async, modelSpec).watched().build();
        client.checkExists().event().whenComplete((event, ex) -> latch.countDown());
        timing.sleepABit();
        assertEquals(latch.getCount(), 1);
        client.set(new TestModel());
        assertTrue(timing.awaitLatch(latch));
    }

    @Test
    public void testGetChildren() {
        TestModel model = new TestModel("John", "Galt", "1 Galt's Gulch", 42, BigInteger.valueOf(1));
        ModeledFramework<TestModel> client =
                ModeledFramework.builder(async, modelSpec).build();
        complete(client.child("one").set(model));
        complete(client.child("two").set(model));
        complete(client.child("three").set(model));

        Set<ZPath> expected = Sets.newHashSet(path.child("one"), path.child("two"), path.child("three"));
        complete(client.children(), (children, e) -> assertEquals(Sets.newHashSet(children), expected));
    }

    @Test
    public void testDelete() {
        ModeledFramework<TestModel> client = ModeledFramework.wrap(async, modelSpec);
        complete(client.set(new TestModel()));

        Stat stat = new Stat();
        client.child("a").set(new TestModel(), stat);
        exceptional(client.child("a").delete(stat.getVersion() + 1), KeeperException.BadVersionException.class);
        complete(client.child("a").delete(stat.getVersion()));

        client.child("b").set(new TestModel());
        complete(client.child("b").delete(-1));

        client.child("c").set(new TestModel());

        exceptional(client.delete(), KeeperException.NotEmptyException.class);

        ModelSpec<TestModel> deleteChildren = ModelSpec.builder(modelSpec.path(), modelSpec.serializer())
                .withDeleteOptions(Collections.singleton(DeleteOption.deletingChildrenIfNeeded))
                .build();

        complete(ModeledFramework.wrap(async, deleteChildren).delete());
        exceptional(ModeledFramework.wrap(async, deleteChildren).delete(), KeeperException.NoNodeException.class);
        exceptional(client.read(), KeeperException.NoNodeException.class);

        ModelSpec<TestModel> quietly = ModelSpec.builder(modelSpec.path(), modelSpec.serializer())
                .withDeleteOptions(Collections.singleton(DeleteOption.quietly))
                .build();
        complete(ModeledFramework.wrap(async, quietly).delete());
    }

    @Test
    public void testBadNode() {
        complete(async.create().forPath(modelSpec.path().fullPath(), "fubar".getBytes()), (v, e) -> {}); // ignore error

        ModeledFramework<TestModel> client =
                ModeledFramework.builder(async, modelSpec).watched().build();
        complete(client.read(), (model, e) -> assertTrue(e instanceof KeeperException.NoNodeException));
    }

    @Test
    public void testSchema() throws Exception {
        Schema schema = modelSpec.schema();
        try (CuratorFramework schemaClient = CuratorFrameworkFactory.builder()
                .connectString(server.getConnectString())
                .retryPolicy(new RetryOneTime(1))
                .schemaSet(new SchemaSet(Collections.singletonList(schema), false))
                .build()) {
            schemaClient.start();

            try {
                schemaClient.create().forPath(modelSpec.path().fullPath(), "asflasfas".getBytes());
                fail("Should've thrown SchemaViolation");
            } catch (SchemaViolation dummy) {
                // expected
            }

            ModeledFramework<TestModel> modeledSchemaClient =
                    ModeledFramework.wrap(AsyncCuratorFramework.wrap(schemaClient), modelSpec);
            complete(
                    modeledSchemaClient.set(new TestModel("one", "two", "three", 4, BigInteger.ONE)),
                    (dummy, e) -> assertNull(e));
        }
    }

    @Test
    public void testVersioned() {
        ModeledFramework<TestModel> client = ModeledFramework.wrap(async, modelSpec);
        TestModel model = new TestModel("John", "Galt", "Galt's Gulch", 21, BigInteger.valueOf(1010101));
        complete(client.set(model));
        complete(client.set(model)); // so that version goes to 1

        VersionedModeledFramework<TestModel> versioned = client.versioned();
        complete(
                versioned
                        .read()
                        .whenComplete((v, e) -> {
                            assertNull(e);
                            assertTrue(v.version() > 0);
                        })
                        .thenCompose(versioned::set),
                (s, e) -> assertNull(e)); // read version is correct; set moves version to 2

        Versioned<TestModel> badVersion = Versioned.from(model, 100000);
        complete(versioned.set(badVersion), (v, e) -> assertTrue(e instanceof KeeperException.BadVersionException));
        complete(versioned.update(badVersion), (v, e) -> assertTrue(e instanceof KeeperException.BadVersionException));

        final Versioned<TestModel> goodVersion = Versioned.from(model, 2);
        complete(versioned.update(goodVersion).whenComplete((v, e) -> {
            assertNull(e);
            assertEquals(3, v.getVersion());
        }));

        final Stat stat = new Stat();
        complete(client.read(stat));
        // wrong version, needs to fail
        complete(
                client.delete(stat.getVersion() + 1),
                (v, e) -> assertTrue(e instanceof KeeperException.BadVersionException));
        // correct version
        complete(client.delete(stat.getVersion()));
    }

    @Test
    public void testAcl() throws NoSuchAlgorithmException {
        List<ACL> aclList = Collections.singletonList(new ACL(
                ZooDefs.Perms.WRITE, new Id("digest", DigestAuthenticationProvider.generateDigest("test:test"))));
        ModelSpec<TestModel> aclModelSpec = ModelSpec.builder(modelSpec.path(), modelSpec.serializer())
                .withAclList(aclList)
                .build();
        ModeledFramework<TestModel> client = ModeledFramework.wrap(async, aclModelSpec);
        complete(client.set(new TestModel("John", "Galt", "Galt's Gulch", 21, BigInteger.valueOf(1010101))));
        complete(
                client.update(new TestModel("John", "Galt", "Galt's Gulch", 54, BigInteger.valueOf(88))),
                (__, e) -> assertNotNull(e, "Should've gotten an auth failure"));

        try (CuratorFramework authCurator = CuratorFrameworkFactory.builder()
                .connectString(server.getConnectString())
                .retryPolicy(new RetryOneTime(1))
                .authorization("digest", "test:test".getBytes())
                .build()) {
            authCurator.start();
            ModeledFramework<TestModel> authClient =
                    ModeledFramework.wrap(AsyncCuratorFramework.wrap(authCurator), aclModelSpec);
            complete(
                    authClient.update(new TestModel("John", "Galt", "Galt's Gulch", 42, BigInteger.valueOf(66))),
                    (__, e) -> assertNull(e, "Should've succeeded"));
        }
    }

    @Test
    public void testExceptionHandling() throws Exception {
        final List<ACL> writeAcl = Collections.singletonList(new ACL(
                ZooDefs.Perms.WRITE, new Id("digest", DigestAuthenticationProvider.generateDigest("test:test"))));

        // An ACLProvider is used to get the Write ACL (for the test user) for any path "/test/**".
        final ACLProvider aclProvider = new ACLProvider() {
            @Override
            public List<ACL> getDefaultAcl() {
                return ZooDefs.Ids.READ_ACL_UNSAFE;
            }

            @Override
            public List<ACL> getAclForPath(String path) {
                // Any sub-path "/test/**" should only be writeable by the test user.
                return path.startsWith("/test") ? writeAcl : getDefaultAcl();
            }
        };

        try (CuratorFramework authorizedFramework = CuratorFrameworkFactory.builder()
                .connectString(server.getConnectString())
                .retryPolicy(new RetryOneTime(1))
                .aclProvider(aclProvider)
                .authorization("digest", "test:test".getBytes())
                .build()) {

            authorizedFramework.start();

            // Create the parent path using the authorized framework, which will initially set the ACL accordingly.
            authorizedFramework.create().withMode(CreateMode.PERSISTENT).forPath("/test");
        }

        // Now attempt to set the sub-node using an unauthorized client.
        try (CuratorFramework unauthorizedFramework = CuratorFrameworkFactory.builder()
                .connectString(server.getConnectString())
                .retryPolicy(new RetryOneTime(1))
                .aclProvider(aclProvider)
                .build()) {
            unauthorizedFramework.start();

            // I overrode the TestModel provided path with a multi-component path under the "/test" parent path
            // (which was previously created with ACL protection).
            ModelSpec<TestModel> aclModelSpec = ModelSpec.builder(ZPath.parse("/test/foo/bar"), modelSpec.serializer())
                    .withCreateOptions(
                            EnumSet.of(CreateOption.createParentsIfNeeded, CreateOption.createParentsAsContainers))
                    .build();

            ModeledFramework<TestModel> noAuthClient =
                    ModeledFramework.wrap(AsyncCuratorFramework.wrap(unauthorizedFramework), aclModelSpec);

            noAuthClient
                    .set(new TestModel("John", "Galt", "Galt's Gulch", 42, BigInteger.valueOf(66)))
                    .toCompletableFuture()
                    .get(timing.forWaiting().milliseconds(), TimeUnit.MILLISECONDS);
            fail("expect to throw a NoAuth KeeperException");
        } catch (ExecutionException | CompletionException e) {
            assertTrue(e.getCause() instanceof KeeperException.NoAuthException);
        }
    }

    @Test
    public void testCompressedCreateAndRead() throws Exception {
        try (CuratorFramework compressedRawClient =
                createRawClientBuilder().enableCompression().build()) {
            compressedRawClient.start();
            AsyncCuratorFramework compressedAsync = AsyncCuratorFramework.wrap(compressedRawClient);
            TestModel rawModel = new TestModel("John", "Galt", "1 Galt's Gulch", 42, BigInteger.valueOf(1));

            // These should be compressed
            ModeledFramework<TestModel> clientWithCompressedFramework =
                    ModeledFramework.wrap(compressedAsync, modelSpec);
            ModeledFramework<TestModel> clientWithCompressedModel = ModeledFramework.wrap(async, compressedModelSpec);

            // These should be uncompressed
            ModeledFramework<TestModel> client = ModeledFramework.wrap(async, modelSpec);
            ModeledFramework<TestModel> clientWithUncompressedModel =
                    ModeledFramework.wrap(async, uncompressedModelSpec);
            ModeledFramework<TestModel> clientWithCompressedFrameworkAndUncompressedModel =
                    ModeledFramework.wrap(compressedAsync, uncompressedModelSpec);

            // Create with compressedFramework, read with all other clients
            complete(clientWithCompressedFramework.set(rawModel), (path, e) -> assertNull(e));
            complete(clientWithCompressedModel.read(), (model, e) -> assertEquals(model, rawModel));
            complete(client.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });
            complete(clientWithUncompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });
            complete(clientWithCompressedFrameworkAndUncompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });

            // Create with compressedModel, read with all other clients
            clientWithCompressedModel.delete();
            complete(clientWithCompressedModel.set(rawModel), (path, e) -> assertNull(e));
            complete(clientWithCompressedFramework.read(), (model, e) -> assertEquals(model, rawModel));
            complete(client.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });
            complete(clientWithUncompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });
            complete(clientWithCompressedFrameworkAndUncompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });

            // Create with regular (implicitly uncompressed) client, read with all other clients
            client.delete();
            complete(client.set(rawModel), (path, e) -> assertNull(e));
            complete(clientWithUncompressedModel.read(), (model, e) -> assertEquals(model, rawModel));
            complete(
                    clientWithCompressedFrameworkAndUncompressedModel.read(),
                    (model, e) -> assertEquals(model, rawModel));
            complete(clientWithCompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });
            complete(clientWithCompressedFramework.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });

            // Create with uncompressedModel, read with all other clients
            clientWithUncompressedModel.delete();
            complete(clientWithUncompressedModel.set(rawModel), (path, e) -> assertNull(e));
            complete(client.read(), (model, e) -> assertEquals(model, rawModel));
            complete(
                    clientWithCompressedFrameworkAndUncompressedModel.read(),
                    (model, e) -> assertEquals(model, rawModel));
            complete(clientWithCompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });
            complete(clientWithCompressedFramework.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });

            // Create with compressedFramework overriden by an uncompressedModel, read with all other clients
            clientWithCompressedFrameworkAndUncompressedModel.delete();
            complete(clientWithCompressedFrameworkAndUncompressedModel.set(rawModel), (path, e) -> assertNull(e));
            complete(client.read(), (model, e) -> assertEquals(model, rawModel));
            complete(clientWithUncompressedModel.read(), (model, e) -> assertEquals(model, rawModel));
            complete(clientWithCompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });
            complete(clientWithCompressedFramework.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });
            clientWithCompressedFrameworkAndUncompressedModel.delete();
        }
    }

    @Test
    public void testCompressedUpdateAndRead() throws Exception {
        try (CuratorFramework compressedRawClient =
                createRawClientBuilder().enableCompression().build()) {
            compressedRawClient.start();
            AsyncCuratorFramework compressedAsync = AsyncCuratorFramework.wrap(compressedRawClient);
            TestModel rawModel = new TestModel("John", "Galt", "1 Galt's Gulch", 42, BigInteger.valueOf(1));

            // These should be compressed
            ModeledFramework<TestModel> clientWithCompressedFramework =
                    ModeledFramework.wrap(compressedAsync, modelSpec);
            ModeledFramework<TestModel> clientWithCompressedModel = ModeledFramework.wrap(async, compressedModelSpec);

            // These should be uncompressed
            ModeledFramework<TestModel> client = ModeledFramework.wrap(async, modelSpec);
            ModeledFramework<TestModel> clientWithUncompressedModel =
                    ModeledFramework.wrap(async, uncompressedModelSpec);
            ModeledFramework<TestModel> clientWithCompressedFrameworkAndUncompressedModel =
                    ModeledFramework.wrap(compressedAsync, uncompressedModelSpec);

            // Create the node - so we can update in each command
            complete(client.set(rawModel), (model, e) -> assertNull(e));

            // Update with compressedFramework, read with all other clients
            complete(clientWithCompressedFramework.update(rawModel), (stat, e) -> assertNull(e));
            complete(clientWithCompressedModel.read(), (model, e) -> assertEquals(model, rawModel));
            complete(client.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });
            complete(clientWithUncompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });
            complete(clientWithCompressedFrameworkAndUncompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });

            // Update with compressedModel, read with all other clients
            complete(clientWithCompressedModel.update(rawModel), (stat, e) -> assertNull(e));
            complete(clientWithCompressedFramework.read(), (model, e) -> assertEquals(model, rawModel));
            complete(client.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });
            complete(clientWithUncompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });
            complete(clientWithCompressedFrameworkAndUncompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });

            // Update with regular (implicitly uncompressed) client, read with all other clients
            complete(client.update(rawModel), (stat, e) -> assertNull(e));
            complete(clientWithUncompressedModel.read(), (model, e) -> assertEquals(model, rawModel));
            complete(
                    clientWithCompressedFrameworkAndUncompressedModel.read(),
                    (model, e) -> assertEquals(model, rawModel));
            complete(clientWithCompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });
            complete(clientWithCompressedFramework.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });

            // Update with uncompressedModel, read with all other clients
            complete(clientWithUncompressedModel.update(rawModel), (stat, e) -> assertNull(e));
            complete(client.read(), (model, e) -> assertEquals(model, rawModel));
            complete(
                    clientWithCompressedFrameworkAndUncompressedModel.read(),
                    (model, e) -> assertEquals(model, rawModel));
            complete(clientWithCompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });
            complete(clientWithCompressedFramework.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });

            // Update with compressedFramework overriden by an uncompressedModel, read with all other clients
            complete(clientWithCompressedFrameworkAndUncompressedModel.update(rawModel), (stat, e) -> assertNull(e));
            complete(client.read(), (model, e) -> assertEquals(model, rawModel));
            complete(clientWithUncompressedModel.read(), (model, e) -> assertEquals(model, rawModel));
            complete(clientWithCompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });
            complete(clientWithCompressedFramework.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });
        }
    }

    @Test
    public void testCompressedCreateOp() throws Exception {
        try (CuratorFramework compressedRawClient =
                createRawClientBuilder().enableCompression().build()) {
            compressedRawClient.start();
            AsyncCuratorFramework compressedAsync = AsyncCuratorFramework.wrap(compressedRawClient);
            TestModel rawModel = new TestModel("John", "Galt", "1 Galt's Gulch", 42, BigInteger.valueOf(1));

            // These should be compressed
            ModeledFramework<TestModel> clientWithCompressedFramework =
                    ModeledFramework.wrap(compressedAsync, modelSpec);
            ModeledFramework<TestModel> clientWithCompressedModel = ModeledFramework.wrap(async, compressedModelSpec);

            // These should be uncompressed
            ModeledFramework<TestModel> client = ModeledFramework.wrap(async, modelSpec);
            ModeledFramework<TestModel> clientWithUncompressedModel =
                    ModeledFramework.wrap(async, uncompressedModelSpec);
            ModeledFramework<TestModel> clientWithCompressedFrameworkAndUncompressedModel =
                    ModeledFramework.wrap(compressedAsync, uncompressedModelSpec);

            // Make sure the parent node(s) exist
            rawClient
                    .create()
                    .creatingParentsIfNeeded()
                    .forPath(modelSpec.path().parent().fullPath());

            // Create with compressedFramework, read with all other clients
            complete(
                    clientWithCompressedFramework.inTransaction(
                            Collections.singletonList(clientWithCompressedFramework.createOp(rawModel))),
                    (results, e) -> assertNull(e));
            complete(clientWithCompressedModel.read(), (model, e) -> assertEquals(model, rawModel));
            complete(client.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });
            complete(clientWithUncompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });
            complete(clientWithCompressedFrameworkAndUncompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });

            // Create with compressedModel, read with all other clients
            clientWithCompressedModel.delete();
            complete(
                    clientWithCompressedModel.inTransaction(
                            Collections.singletonList(clientWithCompressedModel.createOp(rawModel))),
                    (results, e) -> assertNull(e));
            complete(clientWithCompressedFramework.read(), (model, e) -> assertEquals(model, rawModel));
            complete(client.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });
            complete(clientWithUncompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });
            complete(clientWithCompressedFrameworkAndUncompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });

            // Create with regular (implicitly uncompressed) client, read with all other clients
            client.delete();
            complete(
                    client.inTransaction(Collections.singletonList(client.createOp(rawModel))),
                    (results, e) -> assertNull(e));
            complete(clientWithUncompressedModel.read(), (model, e) -> assertEquals(model, rawModel));
            complete(
                    clientWithCompressedFrameworkAndUncompressedModel.read(),
                    (model, e) -> assertEquals(model, rawModel));
            complete(clientWithCompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });
            complete(clientWithCompressedFramework.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });

            // Create with uncompressedModel, read with all other clients
            clientWithUncompressedModel.delete();
            complete(
                    clientWithUncompressedModel.inTransaction(
                            Collections.singletonList(clientWithUncompressedModel.createOp(rawModel))),
                    (results, e) -> assertNull(e));
            complete(client.read(), (model, e) -> assertEquals(model, rawModel));
            complete(
                    clientWithCompressedFrameworkAndUncompressedModel.read(),
                    (model, e) -> assertEquals(model, rawModel));
            complete(clientWithCompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });
            complete(clientWithCompressedFramework.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });

            // Create with compressedFramework overriden by an uncompressedModel, read with all other clients
            clientWithCompressedFrameworkAndUncompressedModel.delete();
            complete(
                    clientWithCompressedFrameworkAndUncompressedModel.inTransaction(Collections.singletonList(
                            clientWithCompressedFrameworkAndUncompressedModel.createOp(rawModel))),
                    (results, e) -> assertNull(e));
            complete(client.read(), (model, e) -> assertEquals(model, rawModel));
            complete(clientWithUncompressedModel.read(), (model, e) -> assertEquals(model, rawModel));
            complete(clientWithCompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });
            complete(clientWithCompressedFramework.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });
            clientWithCompressedFrameworkAndUncompressedModel.delete();
        }
    }

    @Test
    public void testCompressedUpdateOp() throws Exception {
        try (CuratorFramework compressedRawClient =
                createRawClientBuilder().enableCompression().build()) {
            compressedRawClient.start();
            AsyncCuratorFramework compressedAsync = AsyncCuratorFramework.wrap(compressedRawClient);
            TestModel rawModel = new TestModel("John", "Galt", "1 Galt's Gulch", 42, BigInteger.valueOf(1));

            // These should be compressed
            ModeledFramework<TestModel> clientWithCompressedFramework =
                    ModeledFramework.wrap(compressedAsync, modelSpec);
            ModeledFramework<TestModel> clientWithCompressedModel = ModeledFramework.wrap(async, compressedModelSpec);

            // These should be uncompressed
            ModeledFramework<TestModel> client = ModeledFramework.wrap(async, modelSpec);
            ModeledFramework<TestModel> clientWithUncompressedModel =
                    ModeledFramework.wrap(async, uncompressedModelSpec);
            ModeledFramework<TestModel> clientWithCompressedFrameworkAndUncompressedModel =
                    ModeledFramework.wrap(compressedAsync, uncompressedModelSpec);

            // Create the node - so we can update in each command
            complete(client.set(rawModel), (model, e) -> assertNull(e));

            // Update with compressedFramework, read with all other clients
            complete(
                    clientWithCompressedFramework.inTransaction(
                            Collections.singletonList(clientWithCompressedFramework.updateOp(rawModel))),
                    (results, e) -> assertNull(e));
            complete(clientWithCompressedModel.read(), (model, e) -> assertEquals(model, rawModel));
            complete(client.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });
            complete(clientWithUncompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });
            complete(clientWithCompressedFrameworkAndUncompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });

            // Update with compressedModel, read with all other clients
            complete(
                    clientWithCompressedModel.inTransaction(
                            Collections.singletonList(clientWithCompressedModel.updateOp(rawModel))),
                    (results, e) -> assertNull(e));
            complete(clientWithCompressedFramework.read(), (model, e) -> assertEquals(model, rawModel));
            complete(client.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });
            complete(clientWithUncompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });
            complete(clientWithCompressedFrameworkAndUncompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(RuntimeException.class, e.getClass());
            });

            // Update with regular (implicitly uncompressed) client, read with all other clients
            complete(
                    client.inTransaction(Collections.singletonList(client.updateOp(rawModel))),
                    (results, e) -> assertNull(e));
            complete(clientWithUncompressedModel.read(), (model, e) -> assertEquals(model, rawModel));
            complete(
                    clientWithCompressedFrameworkAndUncompressedModel.read(),
                    (model, e) -> assertEquals(model, rawModel));
            complete(clientWithCompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });
            complete(clientWithCompressedFramework.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });

            // Update with uncompressedModel, read with all other clients
            complete(
                    clientWithUncompressedModel.inTransaction(
                            Collections.singletonList(clientWithUncompressedModel.updateOp(rawModel))),
                    (results, e) -> assertNull(e));
            complete(client.read(), (model, e) -> assertEquals(model, rawModel));
            complete(
                    clientWithCompressedFrameworkAndUncompressedModel.read(),
                    (model, e) -> assertEquals(model, rawModel));
            complete(clientWithCompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });
            complete(clientWithCompressedFramework.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });

            // Update with compressedFramework overriden by an uncompressedModel, read with all other clients
            complete(
                    clientWithCompressedFrameworkAndUncompressedModel.inTransaction(Collections.singletonList(
                            clientWithCompressedFrameworkAndUncompressedModel.updateOp(rawModel))),
                    (results, e) -> assertNull(e));
            complete(client.read(), (model, e) -> assertEquals(model, rawModel));
            complete(clientWithUncompressedModel.read(), (model, e) -> assertEquals(model, rawModel));
            complete(clientWithCompressedModel.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });
            complete(clientWithCompressedFramework.read(), (model, e) -> {
                assertNotNull(e);
                assertEquals(KeeperException.DataInconsistencyException.class, e.getClass());
            });
        }
    }
}
