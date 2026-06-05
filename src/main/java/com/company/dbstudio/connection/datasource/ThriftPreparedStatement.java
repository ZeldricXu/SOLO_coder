package com.company.dbstudio.connection.datasource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

public class ThriftPreparedStatement extends ThriftStatement implements PreparedStatement {

    private static final Logger logger = LoggerFactory.getLogger(ThriftPreparedStatement.class);

    private final String sqlTemplate;
    private final Map<Integer, Object> parameters = new HashMap<>();

    public ThriftPreparedStatement(ThriftConnection connection, String sql) {
        super(connection);
        this.sqlTemplate = sql;
    }

    private String buildSql() {
        String sql = sqlTemplate;
        List<Integer> keys = new ArrayList<>(parameters.keySet());
        keys.sort((a, b) -> b - a);
        for (int index : keys) {
            Object value = parameters.get(index);
            String paramStr = formatParameter(value);
            sql = sql.replaceFirst("\\?", paramStr);
        }
        return sql;
    }

    private String formatParameter(Object value) {
        if (value == null) {
            return "NULL";
        } else if (value instanceof String || value instanceof Date
                || value instanceof Time || value instanceof Timestamp) {
            return "'" + escapeString(value.toString()) + "'";
        } else {
            return value.toString();
        }
    }

    private String escapeString(String s) {
        return s.replace("'", "''");
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        return executeQuery(buildSql());
    }

    @Override
    public int executeUpdate() throws SQLException {
        return executeUpdate(buildSql());
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        parameters.put(parameterIndex, null);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void clearParameters() throws SQLException {
        parameters.clear();
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public boolean execute() throws SQLException {
        return execute(buildSql());
    }

    @Override
    public void addBatch() throws SQLException {
        addBatch(buildSql());
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        parameters.put(parameterIndex, reader);
    }

    @Override
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setBlob(int parameterIndex, Blob x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setClob(int parameterIndex, Clob x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setArray(int parameterIndex, Array x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        return null;
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        parameters.put(parameterIndex, null);
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        return new ParameterMetaData() {
            @Override
            public int getParameterCount() throws SQLException {
                return (int) sqlTemplate.chars().filter(ch -> ch == '?').count();
            }

            @Override
            public int isNullable(int param) throws SQLException {
                return parameterNullableUnknown;
            }

            @Override
            public boolean isSigned(int param) throws SQLException {
                return true;
            }

            @Override
            public int getPrecision(int param) throws SQLException {
                return 0;
            }

            @Override
            public int getScale(int param) throws SQLException {
                return 0;
            }

            @Override
            public int getParameterType(int param) throws SQLException {
                return Types.OTHER;
            }

            @Override
            public String getParameterTypeName(int param) throws SQLException {
                return "OTHER";
            }

            @Override
            public String getParameterClassName(int param) throws SQLException {
                return Object.class.getName();
            }

            @Override
            public int getParameterMode(int param) throws SQLException {
                return parameterModeIn;
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("Cannot unwrap");
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) throws SQLException {
                return false;
            }
        };
    }

    @Override
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        parameters.put(parameterIndex, value);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        parameters.put(parameterIndex, value);
    }

    @Override
    public void setNClob(int parameterIndex, NClob value) throws SQLException {
        parameters.put(parameterIndex, value);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        parameters.put(parameterIndex, reader);
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        parameters.put(parameterIndex, inputStream);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        parameters.put(parameterIndex, reader);
    }

    @Override
    public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
        parameters.put(parameterIndex, xmlObject);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        parameters.put(parameterIndex, reader);
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        parameters.put(parameterIndex, x);
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        parameters.put(parameterIndex, reader);
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        parameters.put(parameterIndex, value);
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        parameters.put(parameterIndex, reader);
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        parameters.put(parameterIndex, inputStream);
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        parameters.put(parameterIndex, reader);
    }
}
