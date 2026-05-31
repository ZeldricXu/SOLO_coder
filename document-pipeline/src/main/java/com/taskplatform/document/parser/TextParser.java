package com.taskplatform.document.parser;

import com.taskplatform.document.DocumentParser;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
public class TextParser implements DocumentParser {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "txt", "md", "markdown", "log", "csv", "json", "xml", "html"
    );

    @Override
    public boolean supports(String fileType) {
        return SUPPORTED_TYPES.contains(fileType.toLowerCase());
    }

    @Override
    public String parse(InputStream inputStream, String fileType) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }
        return content.toString();
    }
}
