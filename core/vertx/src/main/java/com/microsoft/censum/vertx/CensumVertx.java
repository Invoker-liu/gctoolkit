// Copyright (c) Microsoft Corporation.
// Licensed under the MIT License.
package com.microsoft.censum.vertx;

import com.microsoft.censum.event.jvm.JVMEvent;
import com.microsoft.censum.event.jvm.JVMTermination;
import com.microsoft.censum.io.DataSource;
import com.microsoft.censum.time.DateTimeStamp;
import com.microsoft.censum.vertx.aggregator.AggregatorVerticle;
import com.microsoft.censum.vertx.io.JVMEventCodec;
import com.microsoft.censum.vertx.jvm.LogFileParser;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;

import java.io.IOException;
import java.util.Set;
import java.util.logging.Logger;

public class CensumVertx extends AbstractVerticle {

    private static final Logger LOGGER = Logger.getLogger(CensumVertx.class.getName());

    public static final String PARSER_INBOX = "PARSER";
    public static final String JVM_EVENT_PARSER_OUTBOX = "JVMEventParser";
    public static final String SURVIVOR_MEMORY_POOL_PARSER_OUTBOX = "SurvivorMemoryPoolParser";
    public static final String GENERATIONAL_HEAP_PARSER_OUTBOX = "GenerationalHeapParser";
    public static final String CMS_TENURED_POOL_PARSER_OUTBOX = "CMSTenuredPoolParser";
    public static final String G1GC_PARSER_OUTBOX = "G1GCParser";
    public static final String ZGC_PARSER_OUTBOX = "ZGCParser";
    public static final String SHENANDOAH_PARSER_OUTBOX = "ShenandoahParser";

    static {
        disableCaching();
    }

    public static void disableCaching() {
        System.setProperty("vertx.disableFileCPResolving", "true");
        System.setProperty("vertx.disableFileCaching", "true");
    }

    private final String mailBox;
    private final Vertx vertx;
    private DateTimeStamp timeOfLastEvent = new DateTimeStamp(0.0d);

    private CensumVertx(String mailBox) {
        disableCaching();
        this.mailBox = mailBox;
        this.vertx = Vertx.vertx();
        vertx.eventBus().registerDefaultCodec(JVMEvent.class, new JVMEventCodec());
    }

    public void shutdown() { vertx.close(); }

    /**
     * Parse the data source and feed events to the aggregators.
     * @param dataSource The JVM event data source
     * @param logFileParsers The parsers for parsing the data source
     * @param aggregatorVerticles The verticles that dispatch events to the aggregators
     * @param mailBox The mailbox for the log file parser.
     * @return The runtime duration
     */
    public static DateTimeStamp aggregateDataSource(
            DataSource<?> dataSource,
            Set<LogFileParser> logFileParsers,
            Set<AggregatorVerticle> aggregatorVerticles,
            String mailBox) throws IOException {

        CensumVertx censumVertx = new CensumVertx(mailBox);
        JVMEventSource jvmEventSource = new JVMEventSource(PARSER_INBOX);
        censumVertx.deployVerticle(jvmEventSource);
        jvmEventSource.awaitDeployment();

        censumVertx.deployVerticle(censumVertx);

        logFileParsers.forEach(logFileParser -> {
            censumVertx.deployVerticle(logFileParser, new DeploymentOptions().setWorker(true));
        });
        logFileParsers.forEach(LogFileParser::awaitDeployment);

        aggregatorVerticles.forEach(censumVertx::deployVerticle);
        aggregatorVerticles.forEach(AggregatorVerticle::awaitDeployment);

        jvmEventSource.publishGCDataSource(dataSource);
        aggregatorVerticles.forEach(AggregatorVerticle::awaitCompletion);

        censumVertx.shutdown();

        return censumVertx.timeOfLastEvent;
    }

    private Future<String> deployVerticle(Verticle verticle) {
        return vertx.deployVerticle(verticle);
    }

    private Future<String> deployVerticle(Verticle verticle, DeploymentOptions deploymentOptions) {
        return vertx.deployVerticle(verticle, deploymentOptions);
    }

    @Override
    public void start() {
        try {
            vertx.eventBus().
                    consumer(mailBox, message -> {
                        try {
                            JVMEvent event = (JVMEvent) message.body();
                            if (event instanceof JVMTermination)
                                return;
                            DateTimeStamp now = event.getDateTimeStamp().add(event.getDuration());
                            if (now.after(timeOfLastEvent)) {
                                timeOfLastEvent = now;
                            }
                        } catch (Throwable t) {
                            LOGGER.throwing(this.getClass().getName(), "start", t);
                        }
                    });
        } catch (Throwable t) {
            LOGGER.throwing(this.getClass().getName(), "start", t);
        }
    }

}
