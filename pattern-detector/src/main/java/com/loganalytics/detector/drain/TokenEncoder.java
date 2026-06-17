package com.loganalytics.detector.drain;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class TokenEncoder {
    private final ConcurrentHashMap<String, Integer> tokenToCode;
    private final ConcurrentHashMap<Integer, String> codeToToken;
    private final AtomicInteger nextCode;

    public static final int WILDCARD_CODE = 0;

    public TokenEncoder() {
        this.tokenToCode = new ConcurrentHashMap<>();
        this.codeToToken = new ConcurrentHashMap<>();
        this.nextCode = new AtomicInteger(1);
    }

    public int encode(String token) {
        if ("<*>".equals(token)) return WILDCARD_CODE;
        return tokenToCode.computeIfAbsent(token, k -> {
            int code = nextCode.getAndIncrement();
            codeToToken.put(code, k);
            return code;
        });
    }

    public int[] encodeTokens(List<String> tokens) {
        int[] codes = new int[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) {
            codes[i] = encode(tokens.get(i));
        }
        return codes;
    }

    public String decode(int code) {
        if (code == WILDCARD_CODE) return "<*>";
        return codeToToken.get(code);
    }

    public boolean isVariable(int code) {
        return code == WILDCARD_CODE;
    }
}
