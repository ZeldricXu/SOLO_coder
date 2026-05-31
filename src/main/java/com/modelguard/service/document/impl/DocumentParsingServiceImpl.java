package com.modelguard.service.document.impl;

import com.modelguard.exception.BusinessException;
import com.modelguard.service.document.DocumentParsingService;
import com.modelguard.util.TextSplitUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParsingServiceImpl implements DocumentParsingService {

    private static final Pattern SENTENCE_PATTERN = Pattern.compile("(?<=[.!?。！？])\\s+");
    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with",
            "是", "的", "了", "和", "与", "及", "在", "于", "对", "对于", "关于"
    ));

    @Override
    public Mono<String> extractText(String filePath, String fileType) {
        return Mono.fromCallable(() -> {
            try {
                byte[] content = Files.readAllBytes(Paths.get(filePath));
                return extractTextByType(content, fileType);
            } catch (IOException e) {
                log.error("Failed to read file: {}", filePath, e);
                throw new BusinessException("Failed to read file: " + e.getMessage());
            }
        });
    }

    @Override
    public Mono<String> extractTextFromContent(byte[] content, String fileType) {
        return Mono.fromCallable(() -> extractTextByType(content, fileType));
    }

    private String extractTextByType(byte[] content, String fileType) {
        String type = fileType != null ? fileType.toLowerCase() : "txt";
        switch (type) {
            case "txt":
            case "md":
            case "markdown":
                return new String(content);
            case "html":
                return extractTextFromHtml(new String(content));
            case "pdf":
                return simulatePdfExtraction(content);
            case "doc":
            case "docx":
                return simulateWordExtraction(content);
            default:
                return new String(content);
        }
    }

    private String extractTextFromHtml(String html) {
        return html.replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String simulatePdfExtraction(byte[] content) {
        return new String(content).replaceAll("\\x00", " ").trim();
    }

    private String simulateWordExtraction(byte[] content) {
        return new String(content).replaceAll("\\x00", " ").trim();
    }

    @Override
    public Mono<String> cleanText(String rawText) {
        return Mono.fromCallable(() -> {
            if (rawText == null) return "";

            String cleaned = rawText
                    .replaceAll("\\r\\n", "\n")
                    .replaceAll("\\r", "\n")
                    .replaceAll("\n{3,}", "\n\n")
                    .replaceAll("[\\t\\f\\v]", " ")
                    .replaceAll(" +", " ")
                    .replaceAll("\u3000", " ")
                    .trim();

            cleaned = removeControlCharacters(cleaned);

            return cleaned;
        });
    }

    private String removeControlCharacters(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == '\n' || c == '\t' || c == '\r' || (c >= 32 && c != 127)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public Mono<List<String>> splitIntoSentences(String text) {
        return Mono.fromCallable(() -> {
            if (text == null || text.isEmpty()) return Collections.emptyList();

            String[] sentences = SENTENCE_PATTERN.split(text);
            return Arrays.stream(sentences)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        });
    }

    @Override
    public Mono<List<Map<String, Object>>> splitIntoChunks(String text, int chunkSize, int overlapSize) {
        return Mono.fromCallable(() -> {
            List<String> chunks = TextSplitUtil.smartSplit(text, chunkSize, overlapSize);
            List<Map<String, Object>> result = new ArrayList<>();

            int startPos = 0;
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                Map<String, Object> chunkInfo = new HashMap<>();
                chunkInfo.put("index", i);
                chunkInfo.put("content", chunk);
                chunkInfo.put("char_count", chunk.length());
                chunkInfo.put("word_count", countWords(chunk));
                chunkInfo.put("start_pos", startPos);
                chunkInfo.put("end_pos", startPos + chunk.length());
                result.add(chunkInfo);

                startPos += Math.max(0, chunk.length() - overlapSize);
            }

            return result;
        });
    }

    private int countWords(String text) {
        if (text == null || text.isEmpty()) return 0;
        String[] words = text.split("\\s+");
        return (int) Arrays.stream(words).filter(w -> !w.isEmpty()).count();
    }

    @Override
    public Mono<List<Float>> vectorizeChunk(String chunkText) {
        return Mono.fromCallable(() -> {
            float[] vector = new float[384];
            Random random = new Random(chunkText.hashCode());
            for (int i = 0; i < 384; i++) {
                vector[i] = (random.nextFloat() - 0.5f) * 2;
            }

            float norm = 0;
            for (float v : vector) {
                norm += v * v;
            }
            norm = (float) Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < 384; i++) {
                    vector[i] /= norm;
                }
            }

            List<Float> result = new ArrayList<>();
            for (float v : vector) {
                result.add(v);
            }
            return result;
        });
    }

    @Override
    public Mono<Map<String, Object>> detectDocumentType(String filePath) {
        return Mono.fromCallable(() -> {
            Map<String, Object> result = new HashMap<>();
            String fileName = filePath.toLowerCase();

            String detectedType = "unknown";
            if (fileName.endsWith(".pdf")) detectedType = "pdf";
            else if (fileName.endsWith(".doc") || fileName.endsWith(".docx")) detectedType = "word";
            else if (fileName.endsWith(".txt")) detectedType = "txt";
            else if (fileName.endsWith(".md") || fileName.endsWith(".markdown")) detectedType = "markdown";
            else if (fileName.endsWith(".html") || fileName.endsWith(".htm")) detectedType = "html";

            result.put("file_path", filePath);
            result.put("detected_type", detectedType);
            result.put("confidence", detectedType.equals("unknown") ? 0.0 : 0.95);
            result.put("supported", !detectedType.equals("unknown"));

            return result;
        });
    }

    @Override
    public Mono<Map<String, Object>> extractMetadata(String filePath, String fileType) {
        return Mono.fromCallable(() -> {
            Map<String, Object> metadata = new HashMap<>();
            try {
                metadata.put("file_path", filePath);
                metadata.put("file_type", fileType);
                metadata.put("file_size", Files.size(Paths.get(filePath)));
                metadata.put("last_modified", Files.getLastModifiedTime(Paths.get(filePath)).toString());
            } catch (IOException e) {
                log.warn("Failed to extract metadata for: {}", filePath, e);
            }
            metadata.put("encoding", "UTF-8");
            metadata.put("language", detectLanguage(filePath));
            return metadata;
        });
    }

    private String detectLanguage(String filePath) {
        return "zh-CN";
    }

    @Override
    public Mono<String> summarizeDocument(String text, int maxLength) {
        return Mono.fromCallable(() -> {
            if (text == null || text.isEmpty()) return "";

            List<String> sentences = SENTENCE_PATTERN.splitAsStream(text)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());

            if (sentences.isEmpty()) return "";

            StringBuilder summary = new StringBuilder();
            int count = 0;
            int sentenceCount = Math.min(3, sentences.size());

            for (int i = 0; i < sentenceCount && count < maxLength; i++) {
                String sentence = sentences.get(i);
                if (summary.length() + sentence.length() > maxLength) {
                    break;
                }
                if (summary.length() > 0) summary.append(" ");
                summary.append(sentence);
                count += sentence.length();
            }

            return summary.toString();
        });
    }

    @Override
    public Mono<List<String>> extractKeywords(String text, int topN) {
        return Mono.fromCallable(() -> {
            if (text == null || text.isEmpty()) return Collections.emptyList();

            String[] words = text.toLowerCase().split("[^a-zA-Z0-9\\u4e00-\\u9fa5]+");
            Map<String, Integer> wordFreq = new HashMap<>();

            for (String word : words) {
                if (word.length() < 2 || STOPWORDS.contains(word)) continue;
                wordFreq.merge(word, 1, Integer::sum);
            }

            return wordFreq.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(topN)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        });
    }
}
