package com.loganalytics.agent.input;

import com.loganalytics.agent.config.AgentConfig;
import com.loganalytics.agent.multiline.MultiLineMerger;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.common.util.TimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class SocketReceiver {
    private static final Logger log = LoggerFactory.getLogger(SocketReceiver.class);

    private final AgentConfig config;
    private final FileTailer.LogEventHandler eventHandler;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService workerPool;
    private volatile boolean running;
    private final AtomicLong connectionCount;
    private final AtomicLong messageCount;
    private final ConcurrentHashMap<String, MultiLineMerger> clientMergers;

    public SocketReceiver(AgentConfig config, FileTailer.LogEventHandler eventHandler) {
        this.config = config;
        this.eventHandler = eventHandler;
        this.connectionCount = new AtomicLong(0);
        this.messageCount = new AtomicLong(0);
        this.clientMergers = new ConcurrentHashMap<>();
    }

    public void start() throws IOException {
        int port = config.getSocketPort();
        serverSocket = new ServerSocket(port, 100, InetAddress.getByName("0.0.0.0"));
        serverSocket.setReuseAddress(true);
        serverSocket.setReceiveBufferSize(1024 * 1024);

        workerPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        running = true;

        acceptThread = new Thread(this::acceptLoop, "socket-receiver-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        log.info("Socket receiver started on port {}", port);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true);
                clientSocket.setKeepAlive(true);
                clientSocket.setSoTimeout(30000);

                String clientKey = clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort();
                log.info("New connection from {}", clientKey);
                connectionCount.incrementAndGet();

                MultiLineMerger merger = new MultiLineMerger(config);
                clientMergers.put(clientKey, merger);

                workerPool.submit(() -> handleClient(clientSocket, clientKey, merger));
            } catch (SocketException e) {
                if (!running) {
                    break;
                }
                log.error("Socket accept error", e);
            } catch (IOException e) {
                log.error("Failed to accept connection", e);
            }
        }
        log.info("Socket receiver accept loop stopped");
    }

    private void handleClient(Socket clientSocket, String clientKey, MultiLineMerger merger) {
        String clientIp = clientSocket.getInetAddress().getHostAddress();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream()), 8192)) {

            String line;
            while (running && (line = reader.readLine()) != null) {
                messageCount.incrementAndGet();
                processLine(line, clientIp, merger);
            }
        } catch (IOException e) {
            log.debug("Client {} disconnected: {}", clientKey, e.getMessage());
        } finally {
            try {
                merger.flush((line, count) -> handleCompleteLine(line, count, clientIp));
                clientSocket.close();
            } catch (IOException e) {
                log.debug("Error closing client socket", e);
            }
            clientMergers.remove(clientKey);
            log.debug("Connection {} closed", clientKey);
        }
    }

    private void processLine(String line, String clientIp, MultiLineMerger merger) {
        if (line.isBlank()) return;

        if (config.isMultiLineEnabled()) {
            merger.processLine(line, (mergedLine, count) -> handleCompleteLine(mergedLine, count, clientIp));
        } else {
            handleCompleteLine(line, 1, clientIp);
        }
    }

    private void handleCompleteLine(String line, int multiLineCount, String clientIp) {
        LogEvent event = new LogEvent();
        event.setTimestamp(TimeUtils.parseTimestamp(extractTimestamp(line)));
        event.setLevel(extractLevel(line));
        event.setServiceName(extractService(line, config.getServiceName()));
        event.setHostname(config.getHostname());
        event.setSourceIp(clientIp);
        event.setRawMessage(line);
        event.setMessage(line);
        event.setSource("socket");
        event.setMultiLineCount(multiLineCount);
        event.setTraceId(extractTraceId(line));
        event.addTag("client_ip", clientIp);

        eventHandler.onEvent(event);
    }

    private String extractTimestamp(String line) {
        int firstSpace = line.indexOf(' ');
        if (firstSpace > 0) {
            String maybeTs = line.substring(0, firstSpace);
            if (maybeTs.matches("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}.*")) {
                return maybeTs;
            }
        }
        return null;
    }

    private LogLevel extractLevel(String line) {
        String upper = line.toUpperCase();
        if (upper.contains("ERROR")) return LogLevel.ERROR;
        if (upper.contains("WARN")) return LogLevel.WARN;
        if (upper.contains("INFO")) return LogLevel.INFO;
        if (upper.contains("DEBUG")) return LogLevel.DEBUG;
        if (upper.contains("TRACE")) return LogLevel.TRACE;
        if (upper.contains("FATAL")) return LogLevel.FATAL;
        return LogLevel.UNKNOWN;
    }

    private String extractService(String line, String defaultService) {
        int idx = line.indexOf("service=");
        if (idx >= 0) {
            int start = idx + 8;
            int end = line.indexOf(' ', start);
            if (end < 0) end = line.length();
            String svc = line.substring(start, end).trim();
            if (!svc.isEmpty()) return svc;
        }
        return defaultService;
    }

    private String extractTraceId(String line) {
        int idx = line.indexOf("traceId=");
        if (idx >= 0) {
            int start = idx + 8;
            int end = line.indexOf(' ', start);
            if (end < 0) end = line.length();
            return line.substring(start, end).trim();
        }
        return null;
    }

    public long getConnectionCount() {
        return connectionCount.get();
    }

    public long getMessageCount() {
        return messageCount.get();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log.debug("Error closing server socket", e);
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        if (workerPool != null) {
            workerPool.shutdownNow();
        }

        clientMergers.values().forEach(merger ->
                merger.flush((line, count) -> handleCompleteLine(line, count, "127.0.0.1")));
        clientMergers.clear();

        log.info("Socket receiver stopped. Total connections: {}, messages: {}",
                connectionCount.get(), messageCount.get());
    }
}
