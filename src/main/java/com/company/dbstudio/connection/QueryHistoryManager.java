package com.company.dbstudio.connection;

import com.company.dbstudio.connection.model.ConnectionType;
import com.company.dbstudio.connection.model.QueryHistory;
import com.company.dbstudio.core.util.IOUtils;
import com.company.dbstudio.core.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class QueryHistoryManager {

    private static final Logger logger = LoggerFactory.getLogger(QueryHistoryManager.class);
    private static final String HISTORY_FILE = "query_history.json";
    private static final int MAX_HISTORY = 1000;

    private final Path configDir;
    private final ObservableList<QueryHistory> history = FXCollections.observableArrayList();

    public QueryHistoryManager() {
        this.configDir = IOUtils.getAppDataDir("DBStudio");
        try {
            IOUtils.ensureDirectoryExists(configDir);
            loadHistory();
        } catch (IOException e) {
            logger.error("Failed to initialize query history", e);
        }
    }

    private void loadHistory() throws IOException {
        Path historyFile = configDir.resolve(HISTORY_FILE);
        if (Files.exists(historyFile)) {
            List<QueryHistory> loaded = JsonUtils.fromJson(
                    Files.newInputStream(historyFile),
                    new TypeReference<List<QueryHistory>>() {}
            );
            history.addAll(loaded);
            logger.info("Loaded {} query history items", history.size());
        }
    }

    public synchronized void saveHistory() {
        try {
            List<QueryHistory> toSave = new ArrayList<>(history);
            if (toSave.size() > MAX_HISTORY) {
                toSave = toSave.subList(0, MAX_HISTORY);
            }
            Path historyFile = configDir.resolve(HISTORY_FILE);
            JsonUtils.toJsonFile(toSave, historyFile.toFile());
            logger.debug("Saved {} query history items", toSave.size());
        } catch (Exception e) {
            logger.error("Failed to save query history", e);
        }
    }

    public synchronized void add(QueryHistory item) {
        history.add(0, item);
        while (history.size() > MAX_HISTORY) {
            history.remove(history.size() - 1);
        }
        saveHistory();
    }

    public ObservableList<QueryHistory> getHistory() {
        return history;
    }

    public List<QueryHistory> search(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return new ArrayList<>(history);
        }
        String lower = keyword.toLowerCase();
        return history.stream()
                .filter(h -> h.getSql().toLowerCase().contains(lower)
                        || (h.getConnectionName() != null && h.getConnectionName().toLowerCase().contains(lower))
                        || (h.getErrorMessage() != null && h.getErrorMessage().toLowerCase().contains(lower)))
                .collect(Collectors.toList());
    }

    public List<QueryHistory> filterByConnection(String connectionId) {
        return history.stream()
                .filter(h -> connectionId.equals(h.getConnectionId()))
                .collect(Collectors.toList());
    }

    public List<QueryHistory> filterByConnectionType(ConnectionType type) {
        return history.stream()
                .filter(h -> type == h.getConnectionType())
                .collect(Collectors.toList());
    }

    public List<QueryHistory> filterBySuccess(boolean success) {
        return history.stream()
                .filter(h -> h.isSuccess() == success)
                .collect(Collectors.toList());
    }

    public List<QueryHistory> getSlowQueries(long thresholdMs) {
        return history.stream()
                .filter(h -> h.getExecutionTime() > thresholdMs)
                .sorted(Comparator.comparingLong(QueryHistory::getExecutionTime).reversed())
                .collect(Collectors.toList());
    }

    public List<String> getUniqueSqlStatements(int limit) {
        Set<String> seen = new LinkedHashSet<>();
        for (QueryHistory h : history) {
            String normalized = normalizeSql(h.getSql());
            if (seen.add(normalized) && seen.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(seen);
    }

    private String normalizeSql(String sql) {
        if (sql == null) return "";
        return sql.trim()
                .replaceAll("\\s+", " ")
                .replaceAll("'[^']*'", "?")
                .replaceAll("\\b\\d+\\b", "?")
                .toLowerCase();
    }

    public Map<String, Long> getStatsByConnection() {
        return history.stream()
                .collect(Collectors.groupingBy(
                        QueryHistory::getConnectionName,
                        Collectors.counting()
                ));
    }

    public Map<Boolean, Long> getStatsBySuccess() {
        return history.stream()
                .collect(Collectors.groupingBy(
                        QueryHistory::isSuccess,
                        Collectors.counting()
                ));
    }

    public synchronized void clear() {
        history.clear();
        saveHistory();
        logger.info("Query history cleared");
    }

    public synchronized void delete(String id) {
        history.removeIf(h -> h.getId().equals(id));
        saveHistory();
    }

    public synchronized void deleteOlderThan(int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -days);
        long cutoff = cal.getTimeInMillis();

        history.removeIf(h -> h.getExecutedAt() == null
                || h.getExecutedAt().isBefore(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(cutoff),
                java.time.ZoneId.systemDefault())));
        saveHistory();
    }

    public Optional<QueryHistory> getById(String id) {
        return history.stream()
                .filter(h -> h.getId().equals(id))
                .findFirst();
    }

    public int size() {
        return history.size();
    }
}
