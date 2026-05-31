package com.solocoder.platform.prompt.service;

import com.solocoder.platform.prompt.model.PromptVersion;

import java.util.List;
import java.util.Optional;

public interface PromptVersionService {

    PromptVersion createVersion(PromptVersion version);

    Optional<PromptVersion> getVersion(String versionId);

    List<PromptVersion> getVersionsByPrompt(String promptId);

    Optional<PromptVersion> getLatestVersion(String promptId);

    PromptVersion rollback(String promptId, int targetVersion);
}
