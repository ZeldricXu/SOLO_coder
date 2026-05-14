package com.reviewsystem.util;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SensitiveWordFilter {

    private final Map<String, Object> sensitiveWordTree = new ConcurrentHashMap<>();
    private final Set<String> sensitiveWordSet = ConcurrentHashMap.newKeySet();
    private static final String END_FLAG = "end";

    public SensitiveWordFilter() {
        initDefaultSensitiveWords();
    }

    private void initDefaultSensitiveWords() {
        List<String> defaultWords = Arrays.asList(
                "广告", "营销", "推广", "刷单", "刷好评",
                "政治", "反动", "分裂", "独立",
                "色情", "淫秽", "赌博", "毒品",
                "诈骗", "虚假", "欺诈", "钓鱼",
                "暴力", "恐怖", "威胁", "恐吓",
                "侮辱", "诽谤", "辱骂", "人身攻击",
                "垃圾", "灌水", "刷屏", "无意义"
        );
        addSensitiveWords(defaultWords);
    }

    public void addSensitiveWord(String word) {
        if (word == null || word.trim().isEmpty()) {
            return;
        }
        sensitiveWordSet.add(word);
        addWordToTree(word);
    }

    public void addSensitiveWords(List<String> words) {
        if (words == null || words.isEmpty()) {
            return;
        }
        for (String word : words) {
            addSensitiveWord(word);
        }
    }

    public void removeSensitiveWord(String word) {
        if (word != null) {
            sensitiveWordSet.remove(word);
        }
    }

    public Set<String> getSensitiveWords() {
        return new HashSet<>(sensitiveWordSet);
    }

    private void addWordToTree(String word) {
        Map<String, Object> current = sensitiveWordTree;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            String key = String.valueOf(c);
            Object node = current.get(key);
            if (node == null) {
                Map<String, Object> newNode = new ConcurrentHashMap<>();
                current.put(key, newNode);
                current = newNode;
            } else {
                current = (Map<String, Object>) node;
            }
        }
        current.put(END_FLAG, true);
    }

    public FilterResult filter(String text) {
        List<String> foundWords = new ArrayList<>();
        String filteredText = text;
        boolean hasSensitiveWord = false;

        if (text == null || text.isEmpty()) {
            return new FilterResult(false, new ArrayList<>(), text);
        }

        int position = 0;
        int length = text.length();

        while (position < length) {
            int matchLength = checkWord(text, position);
            if (matchLength > 0) {
                String word = text.substring(position, position + matchLength);
                foundWords.add(word);
                hasSensitiveWord = true;
                position += matchLength;
            } else {
                position++;
            }
        }

        if (hasSensitiveWord) {
            for (String word : foundWords) {
                filteredText = filteredText.replace(word, "*".repeat(word.length()));
            }
        }

        return new FilterResult(hasSensitiveWord, foundWords, filteredText);
    }

    private int checkWord(String text, int start) {
        Map<String, Object> current = sensitiveWordTree;
        int maxLength = 0;
        int length = text.length();

        for (int i = start; i < length; i++) {
            char c = text.charAt(i);
            String key = String.valueOf(c);
            Object node = current.get(key);
            if (node == null) {
                break;
            }
            current = (Map<String, Object>) node;
            if (current.containsKey(END_FLAG)) {
                maxLength = i - start + 1;
            }
        }

        return maxLength;
    }

    public boolean containsSensitiveWord(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return filter(text).hasSensitiveWord();
    }

    public static class FilterResult {
        private final boolean hasSensitiveWord;
        private final List<String> matchedWords;
        private final String filteredText;

        public FilterResult(boolean hasSensitiveWord, List<String> matchedWords, String filteredText) {
            this.hasSensitiveWord = hasSensitiveWord;
            this.matchedWords = matchedWords;
            this.filteredText = filteredText;
        }

        public boolean hasSensitiveWord() {
            return hasSensitiveWord;
        }

        public List<String> getMatchedWords() {
            return matchedWords;
        }

        public String getFilteredText() {
            return filteredText;
        }

        public String getMatchedWordsString() {
            return String.join(",", matchedWords);
        }
    }
}
