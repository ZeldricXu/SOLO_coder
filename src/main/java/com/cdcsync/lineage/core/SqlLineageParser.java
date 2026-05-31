package com.cdcsync.lineage.core;

import com.cdcsync.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class SqlLineageParser {

    private final ColumnLineageExtractor columnLineageExtractor;

    public SqlLineageParser(ColumnLineageExtractor columnLineageExtractor) {
        this.columnLineageExtractor = columnLineageExtractor;
    }

    public List<LineageRelation> parse(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            return switch (statement) {
                case Select select -> columnLineageExtractor.extractFromSelect(select);
                case Insert insert -> columnLineageExtractor.extractFromInsert(insert);
                case Update update -> columnLineageExtractor.extractFromUpdate(update);
                case Delete delete -> columnLineageExtractor.extractFromDelete(delete);
                case CreateTable createTable -> columnLineageExtractor.extractFromCreateTable(createTable);
                case Merge merge -> columnLineageExtractor.extractFromMerge(merge);
                case Upsert upsert -> columnLineageExtractor.extractFromUpsert(upsert);
                default -> throw new BusinessException("Unsupported SQL statement type: " + statement.getClass().getSimpleName());
            };
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse SQL: {}", sql, e);
            throw new BusinessException("SQL parsing failed: " + e.getMessage());
        }
    }
}
