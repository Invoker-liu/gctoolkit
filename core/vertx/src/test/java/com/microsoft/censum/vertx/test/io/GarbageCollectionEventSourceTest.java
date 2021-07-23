// Copyright (c) Microsoft Corporation.
// Licensed under the MIT License.
package com.microsoft.censum.vertx.test.io;

import com.microsoft.censum.io.GCLogFile;
import com.microsoft.censum.io.RotatingGCLogFile;
import com.microsoft.censum.io.SingleGCLogFile;
import com.microsoft.censum.vertx.JVMEventSource;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class GarbageCollectionEventSourceTest {

    private static final Logger LOG = Logger.getLogger(GarbageCollectionEventSourceTest.class.getName());

    private static final String TEST_CHANNEL = "TEST";
    private static final String END_OF_DATA_SENTINAL = GCLogFile.END_OF_DATA_SENTINAL;

    private GCLogFile loadLogFile(Path path, boolean rotating) {
        return rotating ? new RotatingGCLogFile(path) : new SingleGCLogFile(path);
    }
    
    @Test
    public void testRotatingLogDirectory() {
        Path path = new TestLogFile("rotating_directory").getFile().toPath();
        assertExpectedLineCountInLog(72210, loadLogFile(path, true));
    }

    /*
        @Test
        public void testPlainTextFileLineCount() {
            Path path = new TestLogFile("gc.log").getFile().toPath();
            assertExpectedLineCountInLog(431604, new SingleGarbageCollectionLogFile(path));
        }
    */
    @Test
    public void testGZipTarFileLineCount() {
        Path path = new TestLogFile("streaming/gc.log.tar.gz").getFile().toPath();
        assertExpectedLineCountInLog(410056, loadLogFile(path, false));
    }

    @Test
    public void testSingleLogInZipLineCount() {
        Path path = new TestLogFile("streaming/gc.log.zip").getFile().toPath();
        assertExpectedLineCountInLog(431604, loadLogFile(path, false));
    }

    @Test
    public void testRotatingLogsLineCount() {
        Path path = new TestLogFile("rotating.zip").getFile().toPath();
        assertExpectedLineCountInLog(72210, loadLogFile(path, true));
    }

    @Test
    public void testRotatingLogsRotatingLineCount() {
        Path path = new TestLogFile("rotating.zip").getFile().toPath();
        assertExpectedLineCountInLog(72210, loadLogFile(path, true));
    }

    @Test
    public void testZippedDirectoryWithRotatingLogRotatingLineCount() {
        Path path = new TestLogFile("streaming/rotating_directory.zip").getFile().toPath();
        assertExpectedLineCountInLog(72211, loadLogFile(path, true));
    }

    private static void disableCaching() {
        System.setProperty("vertx.disableFileCPResolving", "true");
        System.setProperty("vertx.disableFileCaching", "true");
    }

    private void assertExpectedLineCountInLog(int expectedNumberOfLines, GCLogFile logFile) {
        CountDownLatch consumerStarted = new CountDownLatch(2);
        disableCaching();
        Vertx vertx = Vertx.vertx();

        GCLogConsumer consumer = new GCLogConsumer();
        vertx.deployVerticle(consumer, asyncResult -> consumerStarted.countDown());

        JVMEventSource garbageCollectionLogSource = new JVMEventSource(TEST_CHANNEL);
        vertx.deployVerticle(garbageCollectionLogSource, asyncResult -> consumerStarted.countDown());

        try {
            consumerStarted.await();
            garbageCollectionLogSource.publishGCDataSource(logFile);
            consumer.awaitEOF();
            vertx.undeploy(garbageCollectionLogSource.deploymentID());
        } catch (IOException | InterruptedException e) {
            fail(e.getMessage());
        }
        assertEquals(expectedNumberOfLines, consumer.getEventCount());
    }

    class GCLogConsumer extends AbstractVerticle {

        private CountDownLatch eof = new CountDownLatch(1);
        private int eventCount = 0;

        GCLogConsumer() {
        }

        public void awaitEOF() {
            try {
                eof.await();
            } catch (InterruptedException e) {
                fail(e.getMessage());
            }
        }

        @Override
        public void start() {
            vertx.eventBus().consumer(TEST_CHANNEL, message -> {
                eventCount++;
                if (message.body().toString().equals(END_OF_DATA_SENTINAL))
                    eof.countDown();
            });
        }

        int getEventCount() {
            return eventCount;
        }
    }
}
