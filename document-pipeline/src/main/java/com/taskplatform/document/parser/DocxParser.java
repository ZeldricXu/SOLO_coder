package com.taskplatform.document.parser;

import com.taskplatform.document.DocumentParser;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;

@Component
public class DocxParser implements DocumentParser {

    @Override
    public boolean supports(String fileType) {
        return "docx".equalsIgnoreCase(fileType) || "doc".equalsIgnoreCase(fileType);
    }

    @Override
    public String parse(InputStream inputStream, String fileType) throws IOException {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append("[DOCX Content] ").append(line).append("\n");
            }
        }
        return content.toString();
    }

    @Override
    public int getOrder() {
        return 20;
    }
}
