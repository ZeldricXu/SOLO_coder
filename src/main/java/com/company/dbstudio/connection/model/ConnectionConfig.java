package com.company.dbstudio.connection.model;

import com.company.dbstudio.core.model.BaseModel;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ConnectionConfig extends BaseModel {

    private String name;
    private ConnectionType type;
    private String host;
    private int port;
    private String database;
    private String username;
    private String password;
    private String group;
    private boolean favorite;
    private int orderIndex;
    private String color;
    private String description;

    private PoolConfig poolConfig;
    private SshConfig sshConfig;
    private SslConfig sslConfig;
    private Map<String, String> properties;

    public ConnectionConfig() {
        super();
        this.poolConfig = new PoolConfig();
        this.sshConfig = new SshConfig();
        this.sslConfig = new SslConfig();
        this.properties = new HashMap<>();
        this.orderIndex = 0;
    }

    public ConnectionConfig(String name, ConnectionType type) {
        this();
        this.name = name;
        this.type = type;
        this.host = "localhost";
        this.port = type.getDefaultPort();
        this.database = "";
    }

    public String getJdbcUrl() {
        return type.buildUrl(host, port, database);
    }

    public ConnectionConfig copy() {
        ConnectionConfig copy = new ConnectionConfig();
        copy.id = this.id;
        copy.name = this.name;
        copy.type = this.type;
        copy.host = this.host;
        copy.port = this.port;
        copy.database = this.database;
        copy.username = this.username;
        copy.password = this.password;
        copy.group = this.group;
        copy.favorite = this.favorite;
        copy.orderIndex = this.orderIndex;
        copy.color = this.color;
        copy.description = this.description;
        copy.createdAt = this.createdAt;
        copy.updatedAt = this.updatedAt;
        copy.poolConfig = this.poolConfig != null ? this.poolConfig.copy() : new PoolConfig();
        copy.sshConfig = this.sshConfig != null ? this.sshConfig.copy() : new SshConfig();
        copy.sslConfig = this.sslConfig != null ? this.sslConfig.copy() : new SslConfig();
        copy.properties = new HashMap<>(this.properties);
        return copy;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ConnectionType getType() {
        return type;
    }

    public void setType(ConnectionType type) {
        this.type = type;
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

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
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

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public boolean isFavorite() {
        return favorite;
    }

    public void setFavorite(boolean favorite) {
        this.favorite = favorite;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public PoolConfig getPoolConfig() {
        return poolConfig;
    }

    public void setPoolConfig(PoolConfig poolConfig) {
        this.poolConfig = poolConfig;
    }

    public SshConfig getSshConfig() {
        return sshConfig;
    }

    public void setSshConfig(SshConfig sshConfig) {
        this.sshConfig = sshConfig;
    }

    public SslConfig getSslConfig() {
        return sslConfig;
    }

    public void setSslConfig(SslConfig sslConfig) {
        this.sslConfig = sslConfig;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    public void setProperty(String key, String value) {
        this.properties.put(key, value);
    }

    public String getProperty(String key) {
        return this.properties.get(key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        ConnectionConfig that = (ConnectionConfig) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
