package com.company.dbstudio.sql.highlight;

import com.company.dbstudio.connection.model.ConnectionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LexerFactory {

    private static final Logger logger = LoggerFactory.getLogger(LexerFactory.class);

    private static final Map<ConnectionType, Lexer> lexerCache = new ConcurrentHashMap<>();

    private LexerFactory() {
    }

    public static Lexer getLexer(ConnectionType connectionType) {
        if (connectionType == null) {
            return getDefaultLexer();
        }

        return lexerCache.computeIfAbsent(connectionType, type -> {
            logger.debug("Creating lexer for connection type: {}", type);
            return switch (type) {
                case MYSQL -> new MySqlLexer();
                case POSTGRESQL -> new PostgreSqlLexer();
                case ORACLE -> new OracleLexer();
                case SQL_SERVER -> new SqlServerLexer();
                default -> getDefaultLexer();
            };
        });
    }

    public static Lexer getDefaultLexer() {
        return lexerCache.computeIfAbsent(null, k -> new MySqlLexer());
    }

    public static void clearCache() {
        lexerCache.clear();
        logger.debug("Lexer cache cleared");
    }
}
