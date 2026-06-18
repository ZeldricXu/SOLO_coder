package com.designsystem.common.util;

import com.designsystem.entity.DesignToken;
import com.designsystem.mapper.DesignTokenMapper;

import java.util.*;

public class TokenInheritanceUtil {

    private final DesignTokenMapper tokenMapper;
    private final Map<String, DesignToken> tokenCache = new HashMap<>();

    public TokenInheritanceUtil(DesignTokenMapper tokenMapper) {
        this.tokenMapper = tokenMapper;
    }

    public Set<String> getInheritanceChain(String tokenName) {
        Set<String> visited = new LinkedHashSet<>();
        String current = tokenName;

        while (current != null) {
            if (visited.contains(current)) {
                return visited;
            }
            visited.add(current);

            DesignToken token = getToken(current);
            if (token == null || token.getInheritsFrom() == null || token.getInheritsFrom().isEmpty()) {
                break;
            }
            current = token.getInheritsFrom();
        }

        return visited;
    }

    public boolean hasCircularReference(String tokenName, String newInheritsFrom) {
        if (newInheritsFrom == null || newInheritsFrom.isEmpty()) {
            return false;
        }

        Set<String> visited = new HashSet<>();
        visited.add(tokenName);

        String current = newInheritsFrom;
        while (current != null) {
            if (visited.contains(current)) {
                return true;
            }
            visited.add(current);

            DesignToken token = getToken(current);
            if (token == null || token.getInheritsFrom() == null || token.getInheritsFrom().isEmpty()) {
                break;
            }
            current = token.getInheritsFrom();
        }

        return false;
    }

    public List<String> detectAllCircularReferences() {
        List<DesignToken> allTokens = tokenMapper.selectList(null);
        List<String> cycles = new ArrayList<>();
        Set<String> checked = new HashSet<>();

        for (DesignToken token : allTokens) {
            if (checked.contains(token.getTokenName())) {
                continue;
            }

            Set<String> chain = getInheritanceChain(token.getTokenName());
            checked.addAll(chain);

            if (hasCycleInChain(chain)) {
                cycles.add(String.join(" -> ", chain) + " -> " + chain.iterator().next());
            }
        }

        return cycles;
    }

    private boolean hasCycleInChain(Set<String> chain) {
        List<String> chainList = new ArrayList<>(chain);
        for (int i = 0; i < chainList.size(); i++) {
            DesignToken token = getToken(chainList.get(i));
            if (token != null && token.getInheritsFrom() != null) {
                String parent = token.getInheritsFrom();
                if (chainList.subList(0, i).contains(parent)) {
                    return true;
                }
            }
        }
        return false;
    }

    public String resolveTokenValue(String tokenName) {
        Set<String> visited = new HashSet<>();
        return resolveTokenValueInternal(tokenName, visited);
    }

    private String resolveTokenValueInternal(String tokenName, Set<String> visited) {
        if (visited.contains(tokenName)) {
            throw new IllegalStateException("Circular reference detected in token chain: " + tokenName);
        }
        visited.add(tokenName);

        DesignToken token = getToken(tokenName);
        if (token == null) {
            return null;
        }

        String value = token.getBaseValue();
        if (value != null && !value.isEmpty() && !value.startsWith("{")) {
            return value;
        }

        if (value != null && value.startsWith("{") && value.endsWith("}")) {
            String referencedToken = value.substring(1, value.length() - 1);
            return resolveTokenValueInternal(referencedToken, visited);
        }

        if (token.getInheritsFrom() != null && !token.getInheritsFrom().isEmpty()) {
            return resolveTokenValueInternal(token.getInheritsFrom(), visited);
        }

        return value;
    }

    public Map<String, String> resolveAllTokenValues() {
        Map<String, String> resolved = new HashMap<>();
        List<DesignToken> allTokens = tokenMapper.selectList(null);

        for (DesignToken token : allTokens) {
            try {
                String value = resolveTokenValue(token.getTokenName());
                resolved.put(token.getTokenName(), value);
            } catch (IllegalStateException e) {
                resolved.put(token.getTokenName(), "ERROR: " + e.getMessage());
            }
        }

        return resolved;
    }

    public Set<String> getAffectedTokens(String modifiedTokenName) {
        Set<String> affected = new HashSet<>();
        List<DesignToken> allTokens = tokenMapper.selectList(null);

        for (DesignToken token : allTokens) {
            if (token.getTokenName().equals(modifiedTokenName)) {
                continue;
            }
            Set<String> chain = getInheritanceChain(token.getTokenName());
            if (chain.contains(modifiedTokenName)) {
                affected.add(token.getTokenName());
            }
        }

        return affected;
    }

    private DesignToken getToken(String tokenName) {
        if (tokenCache.containsKey(tokenName)) {
            return tokenCache.get(tokenName);
        }
        DesignToken token = tokenMapper.selectByName(tokenName);
        if (token != null) {
            tokenCache.put(tokenName, token);
        }
        return token;
    }

    public void clearCache() {
        tokenCache.clear();
    }
}
