/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.wali

import ch.qos.logback.classic.Level
import org.apache.commons.lang3.SystemUtils
import org.apache.nifi.controller.queue.FlowFileQueue
import org.apache.nifi.controller.repository.EncryptedSchemaRepositoryRecordSerde
import org.apache.nifi.controller.repository.RepositoryRecord
import org.apache.nifi.controller.repository.RepositoryRecordType
import org.apache.nifi.controller.repository.SchemaRepositoryRecordSerde
import org.apache.nifi.controller.repository.StandardFlowFileRecord
import org.apache.nifi.controller.repository.StandardRepositoryRecord
import org.apache.nifi.controller.repository.StandardRepositoryRecordSerdeFactory
import org.apache.nifi.controller.repository.claim.ResourceClaimManager
import org.apache.nifi.controller.repository.claim.StandardResourceClaimManager
import org.apache.nifi.security.kms.CryptoUtils
import org.apache.nifi.security.repository.config.FlowFileRepositoryEncryptionConfiguration
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.wali.SerDe
import org.wali.SerDeFactory
import org.wali.SingletonSerDeFactory

import java.security.Security

import static org.apache.nifi.security.kms.CryptoUtils.STATIC_KEY_PROVIDER_CLASS_NAME
import static org.junit.Assert.assertNotNull

@RunWith(JUnit4.class)
class EncryptedSequentialAccessWriteAheadLogTest extends GroovyTestCase {
    private static final Logger logger = LoggerFactory.getLogger(EncryptedSequentialAccessWriteAheadLogTest.class)

    private static Level ORIGINAL_REPO_LOG_LEVEL
    private static Level ORIGINAL_TEST_LOG_LEVEL
    private static final String REPO_LOG_PACKAGE = "org.apache.nifi.security.repository"

    public static final String TEST_QUEUE_IDENTIFIER = "testQueueIdentifier"

    private ResourceClaimManager claimManager
    private Map<String, FlowFileQueue> queueMap
    private FlowFileQueue flowFileQueue
    private ByteArrayOutputStream byteArrayOutputStream
    private DataOutputStream dataOutputStream

    // TODO: Mock the wrapped serde
    // TODO: Make integration test with real wrapped serde
    private SerDe<RepositoryRecord> wrappedSerDe

    private static final String KPI = STATIC_KEY_PROVIDER_CLASS_NAME
    private static final String KPL = ""
    private static final String KEY_ID = "K1"
    private static final Map<String, String> KEYS = [K1: "0123456789ABCDEFFEDCBA98765432100123456789ABCDEFFEDCBA9876543210"]
    // TODO: Change to WAL impl name
    private static final String REPO_IMPL = CryptoUtils.EWAFFR_CLASS_NAME

    private FlowFileRepositoryEncryptionConfiguration flowFileREC

    private EncryptedSchemaRepositoryRecordSerde esrrs

    private final EncryptedSequentialAccessWriteAheadLog<RepositoryRecord> encryptedWAL

    @Rule
    public TestName testName = new TestName()

    @BeforeClass
    static void setUpOnce() throws Exception {
        Assume.assumeTrue("Test only runs on *nix", !SystemUtils.IS_OS_WINDOWS)
        Security.addProvider(new BouncyCastleProvider())

        logger.metaClass.methodMissing = { String name, args ->
            logger.debug("[${name?.toUpperCase()}] ${(args as List).join(" ")}")
        }
    }

    @Before
    void setUp() throws Exception {
        claimManager = new StandardResourceClaimManager()
        queueMap = [:]
        flowFileQueue = createAndRegisterMockQueue(TEST_QUEUE_IDENTIFIER)
        byteArrayOutputStream = new ByteArrayOutputStream()
        dataOutputStream = new DataOutputStream(byteArrayOutputStream)
        wrappedSerDe = new SchemaRepositoryRecordSerde(claimManager)
        wrappedSerDe.setQueueMap(queueMap)

        flowFileREC = new FlowFileRepositoryEncryptionConfiguration(KPI, KPL, KEY_ID, KEYS, REPO_IMPL)

        esrrs = new EncryptedSchemaRepositoryRecordSerde(wrappedSerDe, flowFileREC)
    }

    @After
    void tearDown() throws Exception {
        claimManager.purge()
        queueMap.clear()
    }

    private FlowFileQueue createMockQueue(String identifier = testName.methodName + new Date().toString()) {
        [getIdentifier: { ->
            logger.mock("Retrieving flowfile queue identifier: ${identifier}" as String)
            identifier
        }] as FlowFileQueue
    }

    private FlowFileQueue createAndRegisterMockQueue(String identifier = testName.methodName + new Date().toString()) {
        FlowFileQueue queue = createMockQueue(identifier)
        queueMap.put(identifier, queue)
        queue
    }

    private RepositoryRecord buildCreateRecord(FlowFileQueue queue, Map<String, String> attributes = [:]) {
        StandardRepositoryRecord record = new StandardRepositoryRecord(queue)
        StandardFlowFileRecord.Builder ffrb = new StandardFlowFileRecord.Builder().id(System.nanoTime())
        ffrb.addAttributes([uuid: getMockUUID()] + attributes as Map<String, String>)
        record.setWorking(ffrb.build())
        record
    }

    private String getMockUUID() {
        "${testName.methodName ?: "no_test"}@${new Date().format("mmssSSS")}" as String
    }

    /** This test creates flowfile records, adds them to the repository, and then recovers them to ensure they were persisted */
    @Test
    void testShouldUpdateWithExternalFile() {
        // Arrange
        final EncryptedSchemaRepositoryRecordSerde encryptedSerde = buildEncryptedSerDe()

        final SequentialAccessWriteAheadLog<RepositoryRecord> repo = createWriteRepo(encryptedSerde)

        final List<RepositoryRecord> records = new ArrayList<>()
        10.times { int i ->
            def attributes = [name: "User ${i}" as String, age: "${i}" as String]
            final RepositoryRecord record = buildCreateRecord(flowFileQueue, attributes)
            records.add(record)
        }

        // Act
        repo.update(records, false)
        repo.shutdown()

        // Assert
        final SequentialAccessWriteAheadLog<RepositoryRecord> recoveryRepo = createRecoveryRepo()
        final Collection<RepositoryRecord> recovered = recoveryRepo.recoverRecords()

        // Ensure that the same records (except now UPDATE instead of CREATE) are returned (order is not guaranteed)
        assert recovered.size() == records.size()
        assert recovered.every { it.type == RepositoryRecordType.UPDATE }

        // Check that all attributes (flowfile record) in the recovered records were present in the original list
        assert recovered.every { (it as StandardRepositoryRecord).current in records*.current }
    }

    /** This test creates flowfile records, adds them to the repository, and then recovers them to ensure they were persisted */
    @Test
    void testShouldUpdateWithExternalFileAfterCheckpoint() {
        // Arrange
        final EncryptedSchemaRepositoryRecordSerde encryptedSerde = buildEncryptedSerDe()

        final SequentialAccessWriteAheadLog<RepositoryRecord> repo = createWriteRepo(encryptedSerde)

        // Turn off debugging because of the high volume
        logger.debug("Temporarily turning off DEBUG logging")
        def encryptorLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(REPO_LOG_PACKAGE)
        ORIGINAL_REPO_LOG_LEVEL = encryptorLogger.getLevel()
        encryptorLogger.setLevel(Level.INFO)

        def testLogger = (ch.qos.logback.classic.Logger) logger
        ORIGINAL_TEST_LOG_LEVEL = testLogger.getLevel()
        testLogger.setLevel(Level.INFO)

        final List<RepositoryRecord> records = new ArrayList<>()
        100_000.times { int i ->
            def attributes = [name: "User ${i}" as String, age: "${i}" as String]
            final RepositoryRecord record = buildCreateRecord(flowFileQueue, attributes)
            records.add(record)
        }

        // Act
        repo.update(records, false)
        repo.shutdown()

        // Assert
        final SequentialAccessWriteAheadLog<RepositoryRecord> recoveryRepo = createRecoveryRepo()
        final Collection<RepositoryRecord> recovered = recoveryRepo.recoverRecords()

        // Ensure that the same records (except now UPDATE instead of CREATE) are returned (order is not guaranteed)
        assert recovered.size() == records.size()
        assert recovered.every { it.type == RepositoryRecordType.UPDATE }

        // Reset log level
        encryptorLogger.setLevel(ORIGINAL_REPO_LOG_LEVEL)
        testLogger.setLevel(ORIGINAL_TEST_LOG_LEVEL)
        logger.debug("Re-enabled DEBUG logging")
    }

    private EncryptedSchemaRepositoryRecordSerde buildEncryptedSerDe(FlowFileRepositoryEncryptionConfiguration ffrec = flowFileREC) {
        final StandardRepositoryRecordSerdeFactory factory = new StandardRepositoryRecordSerdeFactory(claimManager)
        SchemaRepositoryRecordSerde wrappedSerDe = factory.createSerDe() as SchemaRepositoryRecordSerde
        wrappedSerDe.setQueueMap(queueMap)
        return new EncryptedSchemaRepositoryRecordSerde(wrappedSerDe, ffrec)
    }

    private SequentialAccessWriteAheadLog<RepositoryRecord> createWriteRepo() throws IOException {
        return createWriteRepo(buildEncryptedSerDe())
    }

    private SequentialAccessWriteAheadLog<RepositoryRecord> createWriteRepo(final SerDe<RepositoryRecord> serde) throws IOException {
        final File targetDir = new File("target")
        final File storageDir = new File(targetDir, testName?.methodName ?: "unknown_test")
        deleteRecursively(storageDir)
        assertTrue(storageDir.mkdirs())

        final SerDeFactory<RepositoryRecord> serdeFactory = new SingletonSerDeFactory<>(serde)
        final SequentialAccessWriteAheadLog<RepositoryRecord> repo = new SequentialAccessWriteAheadLog<>(storageDir, serdeFactory)

        final Collection<RepositoryRecord> recovered = repo.recoverRecords()
        assertNotNull(recovered)
        assertTrue(recovered.isEmpty())

        return repo
    }

    private SequentialAccessWriteAheadLog<RepositoryRecord> createRecoveryRepo() throws IOException {
        final File targetDir = new File("target")
        final File storageDir = new File(targetDir, testName?.methodName ?: "unknown_test")

        final SerDe<RepositoryRecord> serde = buildEncryptedSerDe()
        final SerDeFactory<RepositoryRecord> serdeFactory = new SingletonSerDeFactory<>(serde)
        final SequentialAccessWriteAheadLog<RepositoryRecord> repo = new SequentialAccessWriteAheadLog<>(storageDir, serdeFactory)

        return repo
    }

    private void deleteRecursively(final File file) {
        final File[] children = file.listFiles()
        if (children != null) {
            for (final File child : children) {
                deleteRecursively(child)
            }
        }

        file.delete()
    }
}
