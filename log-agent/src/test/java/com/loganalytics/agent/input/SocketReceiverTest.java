package com.loganalytics.agent.input;

import com.loganalytics.agent.config.AgentConfig;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SocketReceiverTest {

    private AgentConfig config;
    private SocketReceiver receiver;
    private List<LogEvent> receivedEvents;
    private FileTailer.LogEventHandler eventHandler;
    private int testPort;

    @BeforeEach
    void setUp() throws Exception {
        testPort = findAvailablePort();

        config = new AgentConfig();
        config.setServiceName("test-service");
        config.setHostname("test-host");
        config.setSourceIp("127.0.0.1");
        config.setSocketPort(testPort);
        config.setMultiLineEnabled(false);

        receivedEvents = new ArrayList<>();
        eventHandler = receivedEvents::add;

        receiver = new SocketReceiver(config, eventHandler);
        receiver.start();

        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> serverSocketIsReady(testPort));
    }

    @AfterEach
    void tearDown() {
        if (receiver != null) {
            receiver.stop();
        }
        receivedEvents.clear();
    }

    @Test
    void shouldReceiveLogMessagesOverSocket() throws Exception {
        CountDownLatch latch = new CountDownLatch(5);

        Thread senderThread = new Thread(() -> {
            try (Socket socket = new Socket("127.0.0.1", testPort);
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream()))) {

                for (int i = 0; i < 5; i++) {
                    String ts = Instant.now().toString();
                    String line = String.format("%s INFO socket-test - Socket message %d%n", ts, i);
                    writer.write(line);
                    writer.flush();
                    Thread.sleep(50);
                    latch.countDown();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        senderThread.start();
        latch.await(5, TimeUnit.SECONDS);
        senderThread.join(1000);

        Thread.sleep(100);

        assertThat(receivedEvents).hasSize(5);
        for (int i = 0; i < 5; i++) {
            LogEvent event = receivedEvents.get(i);
            assertThat(event.getMessage()).contains("Socket message " + i);
            assertThat(event.getLevel()).isEqualTo(LogLevel.INFO);
            assertThat(event.getSource()).isEqualTo("socket");
            assertThat(event.getSourceIp()).isEqualTo("127.0.0.1");
            assertThat(event.getServiceName()).isEqualTo("test-service");
        }
    }

    @Test
    void shouldAutoReconnectAfterConnectionDrops() throws Exception {
        CountDownLatch firstBatch = new CountDownLatch(3);
        CountDownLatch secondBatch = new CountDownLatch(3);

        Thread sender1 = new Thread(() -> {
            try (Socket socket = new Socket("127.0.0.1", testPort);
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream()))) {

                for (int i = 0; i < 3; i++) {
                    writer.write(Instant.now() + " INFO test - First batch " + i + "\n");
                    writer.flush();
                    Thread.sleep(50);
                    firstBatch.countDown();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        sender1.start();
        firstBatch.await(5, TimeUnit.SECONDS);
        sender1.join(1000);

        Thread.sleep(100);
        assertThat(receivedEvents).hasSize(3);

        Thread.sleep(1000);

        Thread sender2 = new Thread(() -> {
            try (Socket socket = new Socket("127.0.0.1", testPort);
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream()))) {

                for (int i = 0; i < 3; i++) {
                    writer.write(Instant.now() + " INFO test - Second batch " + i + "\n");
                    writer.flush();
                    Thread.sleep(50);
                    secondBatch.countDown();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        sender2.start();
        secondBatch.await(5, TimeUnit.SECONDS);
        sender2.join(1000);

        Thread.sleep(100);

        assertThat(receivedEvents).hasSize(6);
        assertThat(receiver.getConnectionCount()).isEqualTo(2);
    }

    @Test
    void shouldHandleMultipleConcurrentConnections() throws Exception {
        int numClients = 5;
        int messagesPerClient = 10;
        CountDownLatch latch = new CountDownLatch(numClients * messagesPerClient);

        List<Thread> clientThreads = new ArrayList<>();
        for (int clientId = 0; clientId < numClients; clientId++) {
            final int id = clientId;
            Thread t = new Thread(() -> {
                try (Socket socket = new Socket("127.0.0.1", testPort);
                     BufferedWriter writer = new BufferedWriter(
                             new OutputStreamWriter(socket.getOutputStream()))) {

                    for (int i = 0; i < messagesPerClient; i++) {
                        writer.write(Instant.now() + " INFO test - Client " + id + " msg " + i + "\n");
                        writer.flush();
                        Thread.sleep(10);
                        latch.countDown();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            clientThreads.add(t);
            t.start();
        }

        latch.await(10, TimeUnit.SECONDS);
        for (Thread t : clientThreads) {
            t.join(1000);
        }

        Thread.sleep(100);

        assertThat(receivedEvents).hasSize(numClients * messagesPerClient);
        assertThat(receiver.getConnectionCount()).isEqualTo(numClients);
        assertThat(receiver.getMessageCount()).isEqualTo(numClients * messagesPerClient);
    }

    @Test
    void shouldExtractServiceNameFromLogLine() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);

        try (Socket socket = new Socket("127.0.0.1", testPort);
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream()))) {

            writer.write(Instant.now() + " INFO service=custom-service - Custom service log\n");
            writer.flush();
            latch.countDown();
        }

        latch.await(5, TimeUnit.SECONDS);
        Thread.sleep(100);

        assertThat(receivedEvents).hasSize(1);
        assertThat(receivedEvents.get(0).getServiceName()).isEqualTo("custom-service");
    }

    @Test
    void shouldExtractLogLevelsCorrectly() throws Exception {
        String[] levels = {"DEBUG", "INFO", "WARN", "ERROR", "FATAL"};
        CountDownLatch latch = new CountDownLatch(levels.length);

        try (Socket socket = new Socket("127.0.0.1", testPort);
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream()))) {

            for (String level : levels) {
                writer.write(Instant.now() + " " + level + " test - Level test\n");
                writer.flush();
                Thread.sleep(50);
                latch.countDown();
            }
        }

        latch.await(5, TimeUnit.SECONDS);
        Thread.sleep(100);

        assertThat(receivedEvents).hasSize(5);
        assertThat(receivedEvents.get(0).getLevel()).isEqualTo(LogLevel.DEBUG);
        assertThat(receivedEvents.get(1).getLevel()).isEqualTo(LogLevel.INFO);
        assertThat(receivedEvents.get(2).getLevel()).isEqualTo(LogLevel.WARN);
        assertThat(receivedEvents.get(3).getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(receivedEvents.get(4).getLevel()).isEqualTo(LogLevel.FATAL);
    }

    @Test
    void shouldHandleMultiLineLogsOverSocket() throws Exception {
        config.setMultiLineEnabled(true);
        config.setMultiLinePattern("^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}");
        receiver.stop();
        receivedEvents.clear();

        receiver = new SocketReceiver(config, eventHandler);
        receiver.start();
        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> serverSocketIsReady(testPort));

        CountDownLatch latch = new CountDownLatch(1);

        try (Socket socket = new Socket("127.0.0.1", testPort);
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(socket.getOutputStream()))) {

            writer.write(Instant.now() + " ERROR test - Exception occurred\n");
            writer.write("    at com.test.Class.method(Class.java:100)\n");
            writer.write("Caused by: java.lang.RuntimeException: Test\n");
            writer.write("    at com.test.Caller.call(Caller.java:50)\n");
            writer.flush();
            latch.countDown();
        }

        latch.await(5, TimeUnit.SECONDS);
        Thread.sleep(6000);

        assertThat(receivedEvents).hasSize(1);
        assertThat(receivedEvents.get(0).getMultiLineCount()).isEqualTo(4);
        assertThat(receivedEvents.get(0).getMessage()).contains("Caused by:");
    }

    @Test
    void shouldCloseConnectionsCleanlyOnStop() throws Exception {
        CountDownLatch connectionLatch = new CountDownLatch(1);

        Thread clientThread = new Thread(() -> {
            try (Socket socket = new Socket("127.0.0.1", testPort);
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream()))) {

                writer.write(Instant.now() + " INFO test - Before stop\n");
                writer.flush();
                connectionLatch.countDown();

                while (!socket.isClosed()) {
                    Thread.sleep(100);
                }
            } catch (Exception e) {
            }
        });

        clientThread.start();
        connectionLatch.await(5, TimeUnit.SECONDS);

        Thread.sleep(100);
        assertThat(receivedEvents).hasSize(1);

        receiver.stop();
        clientThread.join(2000);

        assertThat(clientThread.isAlive()).isFalse();
    }

    private int findAvailablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private boolean serverSocketIsReady(int port) {
        try (Socket ignored = new Socket("127.0.0.1", port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
