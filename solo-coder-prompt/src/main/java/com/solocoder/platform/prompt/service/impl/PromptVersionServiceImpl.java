package com.solocoder.platform.prompt.service.impl;

import com.solocoder.platform.prompt.model.PromptVersion;
import com.solocoder.platform.prompt.service.PromptVersionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PromptVersionServiceImpl implements PromptVersionService {

    private final Map<String, PromptVersion> versionStore = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> promptVersionCounter = new ConcurrentHashMap<>();

    @Override
    public PromptVersion createVersion(PromptVersion version) {
        String versionId = UUID.randomUUID().toString();
        int versionNumber = promptVersionCounter
                .computeIfAbsent(version.getPromptId(), k -> new AtomicInteger(0))
                .incrementAndGet();

        PromptVersion saved = PromptVersion.builder()
                .versionId(versionId)
                .promptId(version.getPromptId())
                .content(version.getContent())
                .versionNumber(versionNumber)
                .author(version.getAuthor())
                .changeLog(version.getChangeLog())
                .variables(version.getVariables())
                .createdAt(LocalDateTime.now())
                .build();
        versionStore.put(versionId, saved);
        log.info("Prompt version created: promptId={}, version={}", version.getPromptId(), versionNumber);
        return saved;
    }

    @Override
    public Optional<PromptVersion> getVersion(String versionId) {
        return Optional.ofNullable(versionStore.get(versionId));
    }

    @Override
    public List<PromptVersion> getVersionsByPrompt(String promptId) {
        return versionStore.values().stream()
                .filter(v -> promptId.equals(v.getPromptId()))
                .sorted(Comparator.comparingInt(PromptVersion::getVersionNumber).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public Optional<PromptVersion> getLatestVersion(String promptId) {
        return versionStore.values().stream()
                .filter(v -> promptId.equals(v.getPromptId()))
                .max(Comparator.comparingInt(PromptVersion::getVersionNumber));
    }

    @Override
    public PromptVersion rollback(String promptId, int targetVersion) {
        PromptVersion target = versionStore.values().stream()
                .filter(v -> promptId.equals(v.getPromptId()) && v.getVersionNumber() == targetVersion)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + targetVersion));

        PromptVersion rollback = PromptVersion.builder()
                .versionId(UUID.randomUUID().toString())
                .promptId(promptId)
                .content(target.getContent())
                .versionNumber(promptVersionCounter.get(promptId).incrementAndGet())
                .author("SYSTEM")
                .changeLog("Rollback to version " + targetVersion)
                .variables(target.getVariables())
                .createdAt(LocalDateTime.now())
                .build();
        versionStore.put(rollback.getVersionId(), rollback);
        log.info("Prompt rolled back: promptId={}, to version={}, new version={}",
                promptId, targetVersion, rollback.getVersionNumber());
        return rollback;
    }
}
