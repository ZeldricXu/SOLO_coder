package com.company.dbstudio.connection;

import com.company.dbstudio.connection.model.ConnectionConfig;
import com.company.dbstudio.connection.model.ConnectionType;
import com.company.dbstudio.connection.repository.H2ConnectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class H2ConnectionRepositoryTest {

    private H2ConnectionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new H2ConnectionRepository();
        for (ConnectionConfig config : repository.getAllConnections()) {
            repository.deleteConnection(config.getId());
        }
    }

    @Test
    void testAddConnection() {
        ConnectionConfig config = createTestConnection("test-1", "Test Connection", ConnectionType.MYSQL);
        repository.addConnection(config);

        assertEquals(1, repository.getConnectionCount());
        ConnectionConfig retrieved = repository.getConnection(config.getId());
        assertNotNull(retrieved);
        assertEquals("Test Connection", retrieved.getName());
        assertEquals(ConnectionType.MYSQL, retrieved.getType());
        assertNotNull(retrieved.getCreatedAt());
        assertNotNull(retrieved.getUpdatedAt());
    }

    @Test
    void testUpdateConnection() {
        ConnectionConfig config = createTestConnection("test-2", "Original Name", ConnectionType.MYSQL);
        repository.addConnection(config);

        config.setName("Updated Name");
        config.setHost("192.168.1.100");
        repository.updateConnection(config);

        ConnectionConfig updated = repository.getConnection(config.getId());
        assertEquals("Updated Name", updated.getName());
        assertEquals("192.168.1.100", updated.getHost());
    }

    @Test
    void testDeleteConnection() {
        ConnectionConfig config = createTestConnection("test-3", "To Delete", ConnectionType.MYSQL);
        repository.addConnection(config);
        assertEquals(1, repository.getConnectionCount());

        repository.deleteConnection(config.getId());
        assertEquals(0, repository.getConnectionCount());
        assertNull(repository.getConnection(config.getId()));
    }

    @Test
    void testGetAllConnections() {
        repository.addConnection(createTestConnection("id-1", "Connection A", ConnectionType.MYSQL));
        repository.addConnection(createTestConnection("id-2", "Connection B", ConnectionType.POSTGRESQL));
        repository.addConnection(createTestConnection("id-3", "Connection C", ConnectionType.ORACLE));

        List<ConnectionConfig> all = repository.getAllConnections();
        assertEquals(3, all.size());
    }

    @Test
    void testGetFavoriteConnections() {
        ConnectionConfig conn1 = createTestConnection("fav-1", "Favorite", ConnectionType.MYSQL);
        conn1.setFavorite(true);
        repository.addConnection(conn1);

        ConnectionConfig conn2 = createTestConnection("fav-2", "Not Favorite", ConnectionType.MYSQL);
        conn2.setFavorite(false);
        repository.addConnection(conn2);

        List<ConnectionConfig> favorites = repository.getFavoriteConnections();
        assertEquals(1, favorites.size());
        assertEquals("Favorite", favorites.get(0).getName());
    }

    @Test
    void testMarkRecent() {
        ConnectionConfig conn1 = createTestConnection("recent-1", "Recent 1", ConnectionType.MYSQL);
        ConnectionConfig conn2 = createTestConnection("recent-2", "Recent 2", ConnectionType.POSTGRESQL);
        repository.addConnection(conn1);
        repository.addConnection(conn2);

        repository.markRecent(conn1.getId());
        repository.markRecent(conn2.getId());
        repository.markRecent(conn1.getId());

        List<ConnectionConfig> recent = repository.getRecentConnections();
        assertEquals(2, recent.size());
        assertEquals("Recent 1", recent.get(0).getName());
    }

    @Test
    void testToggleFavorite() {
        ConnectionConfig conn = createTestConnection("toggle-1", "Toggle", ConnectionType.MYSQL);
        repository.addConnection(conn);
        assertFalse(conn.isFavorite());

        repository.toggleFavorite(conn.getId());
        assertTrue(repository.getConnection(conn.getId()).isFavorite());

        repository.toggleFavorite(conn.getId());
        assertFalse(repository.getConnection(conn.getId()).isFavorite());
    }

    @Test
    void testFindByName() {
        repository.addConnection(createTestConnection("find-1", "MySQL Prod", ConnectionType.MYSQL));
        repository.addConnection(createTestConnection("find-2", "Postgres Dev", ConnectionType.POSTGRESQL));

        Optional<ConnectionConfig> found = repository.findByName("mysql prod");
        assertTrue(found.isPresent());
        assertEquals("MySQL Prod", found.get().getName());

        Optional<ConnectionConfig> notFound = repository.findByName("nonexistent");
        assertFalse(notFound.isPresent());
    }

    @Test
    void testSearchConnections() {
        repository.addConnection(createTestConnection("search-1", "Production DB", ConnectionType.MYSQL));
        repository.addConnection(createTestConnection("search-2", "Development DB", ConnectionType.POSTGRESQL));
        repository.addConnection(createTestConnection("search-3", "Test Database", ConnectionType.ORACLE));

        List<ConnectionConfig> results = repository.searchConnections("prod");
        assertEquals(1, results.size());
        assertEquals("Production DB", results.get(0).getName());

        List<ConnectionConfig> allResults = repository.searchConnections("");
        assertEquals(3, allResults.size());
    }

    @Test
    void testGetConnectionsByGroup() {
        ConnectionConfig conn1 = createTestConnection("group-1", "Conn 1", ConnectionType.MYSQL);
        conn1.setGroup("Production");
        repository.addConnection(conn1);

        ConnectionConfig conn2 = createTestConnection("group-2", "Conn 2", ConnectionType.POSTGRESQL);
        conn2.setGroup("Development");
        repository.addConnection(conn2);

        ConnectionConfig conn3 = createTestConnection("group-3", "Conn 3", ConnectionType.ORACLE);
        conn3.setGroup("Production");
        repository.addConnection(conn3);

        var byGroup = repository.getConnectionsByGroup();
        assertEquals(2, byGroup.size());
        assertEquals(2, byGroup.get("Production").size());
        assertEquals(1, byGroup.get("Development").size());
    }

    @Test
    void testGetAllGroups() {
        ConnectionConfig conn1 = createTestConnection("groups-1", "Conn 1", ConnectionType.MYSQL);
        conn1.setGroup("Production");
        repository.addConnection(conn1);

        ConnectionConfig conn2 = createTestConnection("groups-2", "Conn 2", ConnectionType.POSTGRESQL);
        conn2.setGroup("Development");
        repository.addConnection(conn2);

        List<String> groups = repository.getAllGroups();
        assertEquals(2, groups.size());
        assertTrue(groups.contains("Development"));
        assertTrue(groups.contains("Production"));
    }

    @Test
    void testUpdateOrder() {
        ConnectionConfig conn = createTestConnection("order-1", "Order Test", ConnectionType.MYSQL);
        repository.addConnection(conn);
        assertEquals(0, conn.getOrderIndex());

        repository.updateOrder(conn.getId(), 5);
        assertEquals(5, repository.getConnection(conn.getId()).getOrderIndex());
    }

    @Test
    void testConnectionWithProperties() {
        ConnectionConfig conn = createTestConnection("props-1", "Props Test", ConnectionType.MYSQL);
        conn.getProperties().put("useSSL", "false");
        conn.getProperties().put("serverTimezone", "UTC");
        repository.addConnection(conn);

        ConnectionConfig retrieved = repository.getConnection(conn.getId());
        assertEquals("false", retrieved.getProperties().get("useSSL"));
        assertEquals("UTC", retrieved.getProperties().get("serverTimezone"));
    }

    private ConnectionConfig createTestConnection(String id, String name, ConnectionType type) {
        ConnectionConfig config = new ConnectionConfig();
        config.setId(id);
        config.setName(name);
        config.setType(type);
        config.setHost("localhost");
        config.setPort(type.getDefaultPortInt());
        config.setDatabase("testdb");
        config.setUsername("testuser");
        config.setPassword("testpass");
        return config;
    }
}
