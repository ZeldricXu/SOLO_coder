package com.company.dbstudio.connection.datasource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class ThriftStatement implements Statement {

    private static final Logger logger = LoggerFactory.getLogger(ThriftStatement.class);

    protected final ThriftConnection connection;
    protected String currentSql;
    protected ResultSet currentResultSet;
    protected int updateCount = -1;
    protected boolean closed;
    protected int maxRows;
    protected int queryTimeout;
    protected int fetchSize = 1000;
    protected boolean escapeProcessing = true;
    protected final List<String> batchCommands = new ArrayList<>();

    public ThriftStatement(ThriftConnection connection) {
        this.connection = connection;
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        checkClosed();
        this.currentSql = sql;
        logger.debug("Executing Thrift query: {}", sql);

        List<ThriftColumnMetadata> columns = deriveColumns(sql);
        List<Object[]> rows = executeThriftQuery(sql, columns);

        this.currentResultSet = new ThriftResultSet(this, columns, rows);
        return currentResultSet;
    }

    protected List<ThriftColumnMetadata> deriveColumns(String sql) throws SQLException {
        List<ThriftColumnMetadata> columns = new ArrayList<>();
        columns.add(new ThriftColumnMetadata("result", Types.VARCHAR, 255, 0));
        return columns;
    }

    protected List<Object[]> executeThriftQuery(String sql, List<ThriftColumnMetadata> columns) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"Thrift query execution simulated"});
        return rows;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        checkClosed();
        this.currentSql = sql;
        logger.debug("Executing Thrift update: {}", sql);
        this.updateCount = 0;
        return updateCount;
    }

    @Override
    public void close() throws SQLException {
        if (closed) return;
        try {
            if (currentResultSet != null && !currentResultSet.isClosed()) {
                currentResultSet.close();
            }
        } finally {
            closed = true;
            currentResultSet = null;
            batchCommands.clear();
        }
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        return 0;
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
    }

    @Override
    public int getMaxRows() throws SQLException {
        return maxRows;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        this.maxRows = max;
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        this.escapeProcessing = enable;
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        return queryTimeout;
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        this.queryTimeout = seconds;
    }

    @Override
    public void cancel() throws SQLException {
        logger.debug("Query cancelled");
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
    }

    @Override
    public void setCursorName(String name) throws SQLException {
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        checkClosed();
        this.currentSql = sql;
        String trimmed = sql.trim().toLowerCase();
        if (trimmed.startsWith("select") || trimmed.startsWith("show") || trimmed.startsWith("describe")) {
            executeQuery(sql);
            return true;
        } else {
            executeUpdate(sql);
            return false;
        }
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        return currentResultSet;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return updateCount;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return false;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
    }

    @Override
    public int getFetchDirection() throws SQLException {
        return ResultSet.FETCH_FORWARD;
    }

    @Override
    public void setFetchSize(int rows) throws SQLException {
        this.fetchSize = rows;
    }

    @Override
    public int getFetchSize() throws SQLException {
        return fetchSize;
    }

    @Override
    public int getResultSetConcurrency() throws SQLException {
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public int getResultSetType() throws SQLException {
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        batchCommands.add(sql);
    }

    @Override
    public void clearBatch() throws SQLException {
        batchCommands.clear();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        int[] results = new int[batchCommands.size()];
        for (int i = 0; i < batchCommands.size(); i++) {
            try {
                results[i] = executeUpdate(batchCommands.get(i));
            } catch (SQLException e) {
                results[i] = EXECUTE_FAILED;
            }
        }
        batchCommands.clear();
        return results;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connection;
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        return false;
    }

    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        return null;
    }

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        return executeUpdate(sql);
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        return executeUpdate(sql);
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        return executeUpdate(sql);
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        return execute(sql);
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        return execute(sql);
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        return execute(sql);
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public boolean isClosed() throws SQLException {
        return closed;
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
    }

    @Override
    public boolean isPoolable() throws SQLException {
        return false;
    }

    @Override
    public void closeOnCompletion() throws SQLException {
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Cannot unwrap to " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }

    protected void checkClosed() throws SQLException {
        if (closed) {
            throw new SQLException("Statement is closed");
        }
    }
}
