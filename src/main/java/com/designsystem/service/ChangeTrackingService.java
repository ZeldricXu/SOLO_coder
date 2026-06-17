package com.designsystem.service;

import com.designsystem.common.enums.ApprovalStatus;
import com.designsystem.entity.Changelog;
import com.designsystem.entity.Project;
import com.designsystem.entity.TokenChange;
import com.designsystem.mapper.ChangelogMapper;
import com.designsystem.mapper.ProjectMapper;
import com.designsystem.mapper.TokenChangeMapper;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.designsystem.config.RabbitMQConfig.*;

@Service
public class ChangeTrackingService {

    private final ChangelogMapper changelogMapper;
    private final TokenChangeMapper tokenChangeMapper;
    private final ProjectMapper projectMapper;
    private final RabbitTemplate rabbitTemplate;

    public ChangeTrackingService(ChangelogMapper changelogMapper, TokenChangeMapper tokenChangeMapper,
                                 ProjectMapper projectMapper, RabbitTemplate rabbitTemplate) {
        this.changelogMapper = changelogMapper;
        this.tokenChangeMapper = tokenChangeMapper;
        this.projectMapper = projectMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = QUEUE_CHANGELOG_GENERATE)
    public void handleChangelogGenerate(Long componentId) {
        generateChangelogFromGit(componentId, null);
    }

    public List<Changelog> generateChangelogFromGit(Long componentId, String gitUrl) {
        List<Changelog> changelogs = new ArrayList<>();

        try {
            Path tempDir = Files.createTempDirectory("git-changelog-");
            Git git;

            if (gitUrl != null) {
                git = Git.cloneRepository()
                        .setURI(gitUrl)
                        .setDirectory(tempDir.toFile())
                        .setDepth(100)
                        .call();
            } else {
                git = Git.init().setDirectory(tempDir.toFile()).call();
            }

            Iterable<RevCommit> commits = git.log().call();

            for (RevCommit commit : commits) {
                Changelog changelog = parseConventionalCommit(commit);
                if (changelog != null) {
                    changelog.setComponentId(componentId);
                    changelog.setIncludedInRelease(0);
                    changelogMapper.insert(changelog);
                    changelogs.add(changelog);
                }
            }

            deleteDirectory(tempDir.toFile());
        } catch (GitAPIException | IOException e) {
            Changelog fallback = new Changelog();
            fallback.setComponentId(componentId);
            fallback.setCommitType("feat");
            fallback.setCommitSubject("Component updated");
            fallback.setCommittedAt(LocalDateTime.now());
            fallback.setIncludedInRelease(0);
            changelogMapper.insert(fallback);
            changelogs.add(fallback);
        }

        sendNotifications(componentId, changelogs);

        return changelogs;
    }

    public String generateReleaseChangelog(Long componentId, String version) {
        List<Changelog> unreleased = changelogMapper.selectUnreleasedByComponentId(componentId);

        Map<String, List<Changelog>> grouped = new LinkedHashMap<>();
        grouped.put("Features", new ArrayList<>());
        grouped.put("Bug Fixes", new ArrayList<>());
        grouped.put("Performance Improvements", new ArrayList<>());
        grouped.put("BREAKING CHANGES", new ArrayList<>());
        grouped.put("Others", new ArrayList<>());

        for (Changelog log : unreleased) {
            String category = switch (log.getCommitType()) {
                case "feat" -> "Features";
                case "fix" -> "Bug Fixes";
                case "perf" -> "Performance Improvements";
                default -> "Others";
            };
            if (log.getBreakingChange() != null && !log.getBreakingChange().isEmpty()) {
                grouped.get("BREAKING CHANGES").add(log);
            }
            grouped.get(category).add(log);
            log.setVersion(version);
            log.setIncludedInRelease(1);
            changelogMapper.updateById(log);
        }

        StringBuilder md = new StringBuilder();
        md.append("# ").append(version).append(" (").append(LocalDateTime.now().toLocalDate()).append(")\n\n");

        for (Map.Entry<String, List<Changelog>> entry : grouped.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                md.append("## ").append(entry.getKey()).append("\n\n");
                for (Changelog log : entry.getValue()) {
                    md.append("- ").append(log.getCommitSubject());
                    if (log.getCommitScope() != null && !log.getCommitScope().isEmpty()) {
                        md.append(" (").append(log.getCommitScope()).append(")");
                    }
                    if (log.getCommitHash() != null) {
                        md.append(" [").append(log.getCommitHash().substring(0, 7)).append("]");
                    }
                    md.append("\n");
                    if (log.getBreakingChange() != null && !log.getBreakingChange().isEmpty()) {
                        md.append("  \n  **BREAKING CHANGE**: ").append(log.getBreakingChange()).append("\n");
                    }
                }
                md.append("\n");
            }
        }

        return md.toString();
    }

    public String generateTokenMigrationGuide(Long tokenChangeId) {
        TokenChange change = tokenChangeMapper.selectById(tokenChangeId);
        if (change == null) {
            throw new RuntimeException("Token change not found");
        }

        StringBuilder guide = new StringBuilder();
        guide.append("# 令牌迁移指南\n\n");
        guide.append("**变更类型**: ").append(change.getChangeType()).append("\n\n");
        guide.append("**生效日期**: ").append(change.getEffectiveDate()).append("\n\n");

        if ("RENAME".equals(change.getChangeType())) {
            guide.append("## 令牌重命名\n\n");
            guide.append("- 旧名称: `").append(change.getOldName()).append("`\n");
            guide.append("- 新名称: `").append(change.getNewName()).append("`\n\n");
            guide.append("### 代码替换示例:\n\n");
            guide.append("#### CSS\n```css\n/* 旧代码 */\ncolor: var(").append(change.getOldName()).append(");\n\n/* 新代码 */\ncolor: var(").append(change.getNewName()).append(");\n```\n\n");
            guide.append("#### JavaScript\n```js\n// 旧代码\nimport { ").append(toJsName(change.getOldName())).append(" } from '@design-system/tokens';\n\n// 新代码\nimport { ").append(toJsName(change.getNewName())).append(" } from '@design-system/tokens';\n```\n\n");
        }

        if ("UPDATE".equals(change.getChangeType())) {
            guide.append("## 值变更\n\n");
            guide.append("- 令牌: `").append(change.getNewName()).append("`\n");
            guide.append("- 旧值: `").append(change.getOldValue()).append("`\n");
            guide.append("- 新值: `").append(change.getNewValue()).append("`\n\n");
            guide.append("### 影响范围\n\n");
            if (change.getAffectedComponents() != null) {
                guide.append("受影响的组件:\n");
                for (String component : change.getAffectedComponents().split(",")) {
                    guide.append("- ").append(component.trim()).append("\n");
                }
                guide.append("\n");
            }
            guide.append("请检查视觉效果是否符合预期，必要时调整业务代码。\n\n");
        }

        if ("DEPRECATE".equals(change.getChangeType())) {
            guide.append("## 令牌废弃\n\n");
            guide.append("令牌 `").append(change.getOldName()).append("` 已被废弃。\n\n");
            guide.append("请使用替代令牌。\n\n");
        }

        if (change.getAffectedComponents() != null) {
            guide.append("## 受影响的组件\n\n");
            for (String component : change.getAffectedComponents().split(",")) {
                guide.append("- [ ] ").append(component.trim()).append("\n");
            }
            guide.append("\n");
        }

        return guide.toString();
    }

    public void sendNotifications(Long componentId, List<Changelog> changelogs) {
        List<Project> subscribedProjects = projectMapper.selectSubscribedProjects();

        for (Project project : subscribedProjects) {
            if (project.getWebhookUrl() != null && !project.getWebhookUrl().isEmpty()) {
                Map<String, Object> notification = new HashMap<>();
                notification.put("project", project.getProjectName());
                notification.put("componentId", componentId);
                notification.put("changelogs", changelogs.stream()
                        .map(c -> Map.of(
                                "type", c.getCommitType(),
                                "scope", c.getCommitScope(),
                                "subject", c.getCommitSubject(),
                                "breaking", c.getBreakingChange() != null
                        ))
                        .toList());

                rabbitTemplate.convertAndSend(EXCHANGE_DESIGN_SYSTEM, ROUTING_KEY_NOTIFICATION, notification);
            }
        }
    }

    public List<Changelog> getChangelogsByComponentId(Long componentId) {
        return changelogMapper.selectByComponentId(componentId);
    }

    public List<TokenChange> getTokenChanges(Long tokenId) {
        return tokenChangeMapper.selectByTokenId(tokenId);
    }

    public List<TokenChange> getPendingMigrations() {
        return tokenChangeMapper.selectPendingMigration();
    }

    private Changelog parseConventionalCommit(RevCommit commit) {
        String message = commit.getFullMessage();
        Pattern pattern = Pattern.compile("^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(?:\\(([^)]+)\\))?(!)?:\\s*(.+)$");
        Matcher matcher = pattern.matcher(message.split("\n")[0]);

        if (!matcher.find()) {
            return null;
        }

        Changelog changelog = new Changelog();
        changelog.setCommitType(matcher.group(1));
        changelog.setCommitScope(matcher.group(2));
        changelog.setCommitSubject(matcher.group(4));
        changelog.setCommitHash(commit.getName());
        changelog.setAuthor(commit.getAuthorIdent().getName());
        changelog.setAuthorEmail(commit.getAuthorIdent().getEmailAddress());
        changelog.setCommittedAt(LocalDateTime.ofInstant(
                commit.getAuthorIdent().getWhen().toInstant(),
                ZoneId.systemDefault()
        ));

        if ("!".equals(matcher.group(3))) {
            changelog.setBreakingChange("Breaking change in " + matcher.group(1));
        }

        Pattern breakingPattern = Pattern.compile("BREAKING CHANGE:\\s*(.+)$", Pattern.MULTILINE);
        Matcher breakingMatcher = breakingPattern.matcher(message);
        if (breakingMatcher.find()) {
            changelog.setBreakingChange(breakingMatcher.group(1));
        }

        return changelog;
    }

    private String toJsName(String tokenName) {
        return tokenName.replace("--", "").replaceAll("-([a-z])", m -> m.group(1).toUpperCase());
    }

    private void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }
}
