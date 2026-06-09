package com.loganalytics.api.controller;

import com.loganalytics.api.service.LogSearchService;
import com.loganalytics.query.QueryParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/query")
@CrossOrigin(origins = "*")
public class QueryController {

    private final LogSearchService logSearchService;

    @Autowired
    public QueryController(LogSearchService logSearchService) {
        this.logSearchService = logSearchService;
    }

    @PostMapping("/parse")
    public ResponseEntity<Map<String, Object>> parseQuery(@RequestBody Map<String, String> body) {
        String query = body.get("query");
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Query is required"));
        }

        try {
            QueryParser.ParseResult result = QueryParser.parse(query);

            Map<String, Object> response = new HashMap<>();
            response.put("query", query);
            response.put("valid", true);
            response.put("ast", result.getAst() != null ? result.getAst().toString() : null);
            response.put("sql", result.getSql());
            response.put("startTime", result.getStartTime() != null ? result.getStartTime().toString() : null);
            response.put("endTime", result.getEndTime() != null ? result.getEndTime().toString() : null);
            response.put("fields", result.getFields());
            response.put("keywords", result.getKeywords());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> error = new HashMap<>();
            error.put("query", query);
            error.put("valid", false);
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> executeQuery(
            @RequestBody Map<String, String> body,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {

        String query = body.get("query");
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Query is required"));
        }

        return ResponseEntity.ok(logSearchService.searchByNaturalLanguage(query, page, pageSize));
    }

    @GetMapping("/examples")
    public ResponseEntity<Map<String, Object>> getQueryExamples() {
        Map<String, Object> examples = Map.of(
                "basic", List.of(
                        "service:payment AND level:ERROR",
                        "level:ERROR SINCE 1h AGO",
                        "message:\"connection refused\" AND service:gateway"
                ),
                "timeRange", List.of(
                        "SINCE 30m AGO",
                        "BETWEEN 2024-01-15T00:00:00Z AND 2024-01-16T00:00:00Z",
                        "SINCE yesterday"
                ),
                "complex", List.of(
                        "service:payment AND level:ERROR AND pattern:\"connection refused\" SINCE 30m AGO",
                        "(service:payment OR service:order) AND level:(ERROR OR WARN) SINCE 1h AGO",
                        "service:payment AND errorCode:500 AND duration > 500ms SINCE 15m AGO"
                )
        );

        Map<String, Object> response = new HashMap<>();
        response.put("examples", examples);
        response.put("syntax", Map.of(
                "fieldFilter", "field:value or field:\"value with spaces\"",
                "booleanOperators", "AND, OR, NOT",
                "comparison", "> < >= <= = != for numeric fields",
                "timeRange", "SINCE <time> AGO, BETWEEN <time1> AND <time2>",
                "timeUnits", "s, m, h, d, w (seconds, minutes, hours, days, weeks)",
                "naturalTime", "today, yesterday, now, last_week, last_month"
        ));

        return ResponseEntity.ok(response);
    }
}
