package com.metricplatform.plugin;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Properties;

public interface MybatisPlugin extends Interceptor {

    String getName();

    String getDescription();

    default boolean isEnabled() {
        return true;
    }

    default int getOrder() {
        return 0;
    }

    default void init(Properties properties) {
    }

    default void destroy() {
    }

    default Object beforeQuery(Executor executor, MappedStatement ms, Object parameter,
                               RowBounds rowBounds, ResultHandler resultHandler) throws Throwable {
        return null;
    }

    default Object afterQuery(Executor executor, MappedStatement ms, Object parameter,
                              RowBounds rowBounds, ResultHandler resultHandler, Object result) throws Throwable {
        return result;
    }

    default Object beforeUpdate(Executor executor, MappedStatement ms, Object parameter) throws Throwable {
        return null;
    }

    default Object afterUpdate(Executor executor, MappedStatement ms, Object parameter, Object result) throws Throwable {
        return result;
    }

    default void beforePrepare(StatementHandler handler, Connection connection, Integer transactionTimeout) throws Throwable {
    }

    default void afterParameterize(StatementHandler handler, Statement statement) throws Throwable {
    }
}
