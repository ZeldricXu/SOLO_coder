package com.company.dbstudio.connection;

import com.company.dbstudio.connection.model.ConnectionConfig;
import com.company.dbstudio.core.util.IOUtils;
import com.company.dbstudio.core.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ConnectionRepository {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionRepository.class);
    private static final String CONNECTIONS_FILE = "connections.json";
    private static final String RECENT_FILE = "recent_connections.json";
    private static final int MAX_RECENT = 20;

    private final Path configDir;
    private final Map<String, ConnectionConfig> connections = new ConcurrentHashMap<>();
    private final LinkedList<String> recentConnectionIds = new LinkedList<>();

    public ConnectionRepository() {
        this.configDir = IOUtils.getAppDataDir("DBStudio");
        try {
            IOUtils.ensureDirectoryExists(configDir);
            loadConnections();
            loadRecentConnections();
        } catch (IOException e) {
            logger.error("Failed to initialize connection repository", e);
        }
    }

    private void loadConnections() throws IOException {
        Path connectionsFile = configDir.resolve(CONNECTIONS_FILE);
        if (Files.exists(connectionsFile)) {
            List<ConnectionConfig> loaded = JsonUtils.fromJson(
                    Files.newInputStream(connectionsFile),
                    new TypeReference<List<ConnectionConfig>>() {}
            );
            loaded.forEach(conn -> connections.put(conn.getId(), conn));
            logger.info("Loaded {} connections from repository", connections.size());
        }
    }

    private void loadRecentConnections() throws IOException {
        Path recentFile = configDir.resolve(RECENT_FILE);
        if (Files.exists(recentFile)) {
            List<String> loaded = JsonUtils.fromJson(
                    Files.newInputStream(recentFile),
                    new TypeReference<List<String>>() {}
            );
            recentConnectionIds.addAll(loaded);
            logger.info("Loaded {} recent connections", recentConnectionIds.size());
        }
    }

    public synchronized void saveConnections() {
        try {
            List<ConnectionConfig> configList = new ArrayList<>(connections.values());
            configList.sort(Comparator.comparing(ConnectionConfig::getOrderIndex)
                    .thenComparing(ConnectionConfig::getName));

            Path connectionsFile = configDir.resolve(CONNECTIONS_FILE);
            JsonUtils.toJsonFile(configList, connectionsFile.toFile());

            Path recentFile = configDir.resolve(RECENT_FILE);
            JsonUtils.toJsonFile(new ArrayList<>(recentConnectionIds), recentFile.toFile());

            logger.debug("Saved {} connections and {} recent connections",
                    configList.size(), recentConnectionIds.size());
        } catch (Exception e) {
            logger.error("Failed to save connections", e);
        }
    }

    public List<ConnectionConfig> getAllConnections() {
        return connections.values().stream()
                .sorted(Comparator.comparing(ConnectionConfig::getOrderIndex)
                        .thenComparing(ConnectionConfig::getName))
                .collect(Collectors.toList());
    }

    public List<ConnectionConfig> getFavoriteConnections() {
        return connections.values().stream()
                .filter(ConnectionConfig::isFavorite)
                .sorted(Comparator.comparing(ConnectionConfig::getOrderIndex)
                        .thenComparing(ConnectionConfig::getName))
                .collect(Collectors.toList());
    }

    public List<ConnectionConfig> getRecentConnections() {
        return recentConnectionIds.stream()
                .map(connections::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public Map<String, List<ConnectionConfig>> getConnectionsByGroup() {
        return connections.values().stream()
                .collect(Collectors.groupingBy(
                        conn -> Optional.ofNullable(conn.getGroup()).orElse("Ungrouped"),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
    }

    public List<String> getAllGroups() {
        return connections.values().stream()
                .map(ConnectionConfig::getGroup)
                .filter(Objects::nonNull)
                .filter(g -> !g.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public ConnectionConfig getConnection(String id) {
        return connections.get(id);
    }

    public synchronized void addConnection(ConnectionConfig config) {
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        connections.put(config.getId(), config);
        saveConnections();
        logger.info("Added connection: {}", config.getName());
    }

    public synchronized void updateConnection(ConnectionConfig config) {
        config.setUpdatedAt(LocalDateTime.now());
        connections.put(config.getId(), config);
        saveConnections();
        logger.info("Updated connection: {}", config.getName());
    }

    public synchronized void deleteConnection(String id) {
        ConnectionConfig removed = connections.remove(id);
        recentConnectionIds.remove(id);
        if (removed != null) {
            saveConnections();
            logger.info("Deleted connection: {}", removed.getName());
        }
    }

    public synchronized void markRecent(String connectionId) {
        recentConnectionIds.remove(connectionId);
        recentConnectionIds.addFirst(connectionId);
        while (recentConnectionIds.size() > MAX_RECENT) {
            recentConnectionIds.removeLast();
        }
        saveConnections();
    }

    public synchronized void toggleFavorite(String connectionId) {
        ConnectionConfig config = connections.get(connectionId);
        if (config != null) {
            config.setFavorite(!config.isFavorite());
            config.setUpdatedAt(LocalDateTime.now());
            saveConnections();
            logger.info("Toggled favorite for connection: {}", config.getName());
        }
    }

    public synchronized void updateOrder(String connectionId, int newOrder) {
        ConnectionConfig config = connections.get(connectionId);
        if (config != null) {
            config.setOrderIndex(newOrder);
            config.setUpdatedAt(LocalDateTime.now());
            saveConnections();
        }
    }

    public Optional<ConnectionConfig> findByName(String name) {
        return connections.values().stream()
                .filter(c -> c.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    public List<ConnectionConfig> searchConnections(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return getAllConnections();
        }
        String lower = keyword.toLowerCase();
        return connections.values().stream()
                .filter(c -> c.getName().toLowerCase().contains(lower)
                        || (c.getDescription() != null && c.getDescription().toLowerCase().contains(lower))
                        || (c.getHost() != null && c.getHost().toLowerCase().contains(lower))
                        || (c.getDatabase() != null && c.getDatabase().toLowerCase().contains(lower)))
                .collect(Collectors.toList());
    }
}
