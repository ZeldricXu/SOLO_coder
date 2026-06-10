package com.company.dbstudio.connection.ssh;

import com.company.dbstudio.connection.model.SshConfig;
import com.jcraft.jsch.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class SshTunnelManager implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SshTunnelManager.class);
    private static final SshTunnelManager INSTANCE = new SshTunnelManager();
    private static final int MIN_PORT = 49152;
    private static final int MAX_PORT = 65535;
    private static final AtomicInteger nextPort = new AtomicInteger(MIN_PORT);

    private final Map<String, SshTunnel> tunnels = new ConcurrentHashMap<>();

    private SshTunnelManager() {
    }

    public static SshTunnelManager getInstance() {
        return INSTANCE;
    }

    public SshTunnel createTunnel(String connectionId, SshConfig config) throws JSchException, IOException {
        if (!config.isEnabled()) {
            return null;
        }

        SshTunnel existing = tunnels.get(connectionId);
        if (existing != null && existing.isOpen()) {
            return existing;
        }

        if (existing != null) {
            closeTunnel(connectionId);
        }

        String sshHost = config.getHost();
        int sshPort = config.getPort();
        String sshUser = config.getUsername();
        int localPort = config.getLocalPort() > 0 ? config.getLocalPort() : getNextAvailablePort();
        int remotePort = config.getRemotePort();
        String remoteHost = config.getRemoteHost() != null ? config.getRemoteHost() : "localhost";

        JSch jsch = new JSch();

        if (config.isKeyAuth()) {
            Path keyPath = Paths.get(config.getPrivateKeyPath());
            if (!Files.exists(keyPath)) {
                throw new IOException("Private key file not found: " + config.getPrivateKeyPath());
            }
            if (config.getPrivateKeyPassphrase() != null && !config.getPrivateKeyPassphrase().isEmpty()) {
                jsch.addIdentity(keyPath.toString(), config.getPrivateKeyPassphrase().getBytes());
            } else {
                jsch.addIdentity(keyPath.toString());
            }
            logger.debug("Using SSH key authentication: {}", keyPath);
        }

        Session session = jsch.getSession(sshUser, sshHost, sshPort);

        if (!config.isKeyAuth()) {
            session.setPassword(config.getPassword());
        }

        Properties sshConfig = new Properties();
        sshConfig.put("StrictHostKeyChecking", "no");
        sshConfig.put("Compression", config.isUseCompression() ? "yes" : "no");
        sshConfig.put("ConnectTimeout", String.valueOf(config.getConnectionTimeout()));
        sshConfig.put("ServerAliveInterval", String.valueOf(config.getKeepAliveInterval()));
        sshConfig.put("ServerAliveCountMax", "3");
        session.setConfig(sshConfig);

        session.connect(config.getConnectionTimeout());
        logger.info("SSH session connected to {}@{}:{}", sshUser, sshHost, sshPort);

        if (config.getSshJumpHost() != null && !config.getSshJumpHost().isEmpty()) {
            session = createJumpHostSession(session, config);
        }

        int assignedPort = session.setPortForwardingL(localPort, remoteHost, remotePort);
        logger.info("SSH tunnel established: localhost:{} -> {}:{} via {}:{}",
                assignedPort, remoteHost, remotePort, sshHost, sshPort);

        SshTunnel tunnel = new SshTunnel(connectionId, session, assignedPort, remoteHost, remotePort, sshHost, sshPort);
        tunnel.setConfig(config);
        tunnels.put(connectionId, tunnel);

        if (config.isKeepAliveEnabled()) {
            SshTunnelHealthChecker.getInstance().startMonitoring(connectionId, tunnel, config);
        }

        return tunnel;
    }

    private Session createJumpHostSession(Session firstSession, SshConfig config) throws JSchException {
        String jumpHost = config.getSshJumpHost();
        int jumpPort = config.getSshJumpPort();
        String jumpUser = config.getSshJumpUser() != null ? config.getSshJumpUser() : config.getUsername();

        int forwardedPort = firstSession.setPortForwardingL(0, jumpHost, jumpPort);
        logger.debug("Jump host port forwarding: localhost:{} -> {}:{}", forwardedPort, jumpHost, jumpPort);

        JSch jsch = new JSch();
        Session jumpSession = jsch.getSession(jumpUser, "localhost", forwardedPort);
        jumpSession.setPassword(config.getPassword());

        Properties sshConfig = new Properties();
        sshConfig.put("StrictHostKeyChecking", "no");
        sshConfig.put("Compression", config.isUseCompression() ? "yes" : "no");
        jumpSession.setConfig(sshConfig);
        jumpSession.connect();

        firstSession.disconnect();
        logger.info("Jump host session connected via {}@{}:{}", jumpUser, jumpHost, jumpPort);

        return jumpSession;
    }

    public SshTunnel getTunnel(String connectionId) {
        return tunnels.get(connectionId);
    }

    public boolean hasTunnel(String connectionId) {
        SshTunnel tunnel = tunnels.get(connectionId);
        return tunnel != null && tunnel.isOpen();
    }

    public void closeTunnel(String connectionId) {
        SshTunnelHealthChecker.getInstance().stopMonitoring(connectionId);
        SshTunnel tunnel = tunnels.remove(connectionId);
        if (tunnel != null) {
            try {
                tunnel.close();
                logger.info("SSH tunnel closed for connection: {}", connectionId);
            } catch (Exception e) {
                logger.error("Error closing SSH tunnel for connection: {}", connectionId, e);
            }
        }
    }

    private synchronized int getNextAvailablePort() {
        int port = nextPort.getAndIncrement();
        if (port > MAX_PORT) {
            nextPort.set(MIN_PORT);
            port = MIN_PORT;
        }
        return port;
    }

    @Override
    public void close() {
        logger.info("Closing all SSH tunnels...");
        tunnels.keySet().forEach(this::closeTunnel);
        tunnels.clear();
        logger.info("All SSH tunnels closed");
    }

    public static class SshTunnel implements AutoCloseable {
        private final String connectionId;
        private Session session;
        private int localPort;
        private final String remoteHost;
        private final int remotePort;
        private final String sshHost;
        private final int sshPort;
        private long createdAt;
        private SshConfig config;

        public SshTunnel(String connectionId, Session session, int localPort,
                         String remoteHost, int remotePort, String sshHost, int sshPort) {
            this.connectionId = connectionId;
            this.session = session;
            this.localPort = localPort;
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
            this.sshHost = sshHost;
            this.sshPort = sshPort;
            this.createdAt = System.currentTimeMillis();
        }

        public void setConfig(SshConfig config) {
            this.config = config;
        }

        public SshConfig getConfig() {
            return config;
        }

        public void updateFromNewTunnel(SshTunnel newTunnel) {
            if (newTunnel != null && newTunnel.isOpen()) {
                if (this.session != null && this.session.isConnected()) {
                    try {
                        this.session.delPortForwardingL(this.localPort);
                    } catch (Exception e) {
                        logger.debug("Error removing old port forwarding", e);
                    }
                    this.session.disconnect();
                }
                this.session = newTunnel.session;
                this.localPort = newTunnel.localPort;
                this.createdAt = System.currentTimeMillis();
                logger.info("SSH tunnel updated: new port {}", this.localPort);
            }
        }

        public boolean isOpen() {
            return session != null && session.isConnected();
        }

        public int getLocalPort() {
            return localPort;
        }

        public String getRemoteHost() {
            return remoteHost;
        }

        public int getRemotePort() {
            return remotePort;
        }

        public String getSshHost() {
            return sshHost;
        }

        public int getSshPort() {
            return sshPort;
        }

        public String getConnectionId() {
            return connectionId;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public Session getSession() {
            return session;
        }

        @Override
        public void close() {
            if (session != null && session.isConnected()) {
                try {
                    session.delPortForwardingL(localPort);
                } catch (JSchException e) {
                    logger.debug("Error removing port forwarding", e);
                }
                session.disconnect();
            }
        }
    }
}
