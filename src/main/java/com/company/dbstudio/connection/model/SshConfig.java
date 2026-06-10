package com.company.dbstudio.connection.model;

public class SshConfig {

    private boolean enabled = false;
    private String host;
    private int port = 22;
    private String username;
    private String password;
    private String privateKeyPath;
    private String privateKeyPassphrase;
    private int localPort = 0;
    private int remotePort;
    private String remoteHost;
    private boolean useCompression = true;
    private int connectionTimeout = 10000;
    private int keepAliveInterval = 60000;
    private String sshJumpHost;
    private int sshJumpPort = 22;
    private String sshJumpUser;

    public SshConfig() {
    }

    public SshConfig copy() {
        SshConfig copy = new SshConfig();
        copy.enabled = this.enabled;
        copy.host = this.host;
        copy.port = this.port;
        copy.username = this.username;
        copy.password = this.password;
        copy.privateKeyPath = this.privateKeyPath;
        copy.privateKeyPassphrase = this.privateKeyPassphrase;
        copy.localPort = this.localPort;
        copy.remotePort = this.remotePort;
        copy.remoteHost = this.remoteHost;
        copy.useCompression = this.useCompression;
        copy.connectionTimeout = this.connectionTimeout;
        copy.keepAliveInterval = this.keepAliveInterval;
        copy.sshJumpHost = this.sshJumpHost;
        copy.sshJumpPort = this.sshJumpPort;
        copy.sshJumpUser = this.sshJumpUser;
        return copy;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public String getPrivateKeyPassphrase() {
        return privateKeyPassphrase;
    }

    public void setPrivateKeyPassphrase(String privateKeyPassphrase) {
        this.privateKeyPassphrase = privateKeyPassphrase;
    }

    public int getLocalPort() {
        return localPort;
    }

    public void setLocalPort(int localPort) {
        this.localPort = localPort;
    }

    public int getRemotePort() {
        return remotePort;
    }

    public void setRemotePort(int remotePort) {
        this.remotePort = remotePort;
    }

    public String getRemoteHost() {
        return remoteHost;
    }

    public void setRemoteHost(String remoteHost) {
        this.remoteHost = remoteHost;
    }

    public boolean isUseCompression() {
        return useCompression;
    }

    public void setUseCompression(boolean useCompression) {
        this.useCompression = useCompression;
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public int getKeepAliveInterval() {
        return keepAliveInterval;
    }

    public void setKeepAliveInterval(int keepAliveInterval) {
        this.keepAliveInterval = keepAliveInterval;
    }

    public String getSshJumpHost() {
        return sshJumpHost;
    }

    public void setSshJumpHost(String sshJumpHost) {
        this.sshJumpHost = sshJumpHost;
    }

    public int getSshJumpPort() {
        return sshJumpPort;
    }

    public void setSshJumpPort(int sshJumpPort) {
        this.sshJumpPort = sshJumpPort;
    }

    public String getSshJumpUser() {
        return sshJumpUser;
    }

    public void setSshJumpUser(String sshJumpUser) {
        this.sshJumpUser = sshJumpUser;
    }

    public boolean isKeyAuth() {
        return privateKeyPath != null && !privateKeyPath.isEmpty();
    }

    public boolean isKeepAliveEnabled() {
        return keepAliveInterval > 0;
    }
}
