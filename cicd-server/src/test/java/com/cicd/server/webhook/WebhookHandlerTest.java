package com.cicd.server.webhook;

import com.cicd.common.enums.TriggerType;
import com.cicd.common.util.GitUtils;
import com.cicd.server.entity.Project;
import com.cicd.server.entity.WebhookEvent;
import com.cicd.server.pipeline.PipelineService;
import com.cicd.server.repository.ProjectRepository;
import com.cicd.server.repository.WebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookHandlerTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private WebhookEventRepository eventRepository;

    @Mock
    private PipelineService pipelineService;

    @InjectMocks
    private WebhookHandler webhookHandler;

    private Project testProject;
    private String gitlabPushPayload;
    private String githubPushPayload;

    @BeforeEach
    void setUp() {
        testProject = new Project();
        testProject.setId(1L);
        testProject.setName("test-project");
        testProject.setGitUrl("https://git.example.com/group/test-project.git");
        testProject.setWebhookSecret("test-secret");
        testProject.setBranchFilter("main,release/*");
        testProject.setPathFilter("src/**");

        gitlabPushPayload = """
            {
              "object_kind": "push",
              "ref": "refs/heads/main",
              "before": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
              "after": "b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1",
              "user_username": "testuser",
              "project": {
                "git_http_url": "https://git.example.com/group/test-project.git",
                "name": "test-project"
              },
              "commits": [
                {
                  "id": "b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1",
                  "message": "Update README",
                  "timestamp": "2024-01-15T10:00:00Z",
                  "url": "https://git.example.com/group/test-project/commit/b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1",
                  "author": {
                    "name": "Test User",
                    "email": "test@example.com"
                  },
                  "added": ["README.md"],
                  "modified": ["src/main.java"],
                  "removed": []
                }
              ],
              "total_commits_count": 1
            }
            """;

        githubPushPayload = """
            {
              "ref": "refs/heads/main",
              "before": "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
              "after": "b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1",
              "pusher": {
                "name": "testuser",
                "email": "test@example.com"
              },
              "repository": {
                "clone_url": "https://git.example.com/group/test-project.git",
                "name": "test-project"
              },
              "commits": [
                {
                  "id": "b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1",
                  "message": "Update README",
                  "timestamp": "2024-01-15T10:00:00Z",
                  "url": "https://git.example.com/group/test-project/commit/b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1",
                  "author": {
                    "name": "Test User",
                    "email": "test@example.com"
                  },
                  "added": ["README.md"],
                  "modified": ["src/main.java"],
                  "removed": []
                }
              ]
            }
            """;
    }

    @Test
    void testHandleGitLabPushEvent() {
        when(projectRepository.findByGitUrl(anyString())).thenReturn(Optional.of(testProject));
        when(eventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Gitlab-Event", "Push Hook");
        headers.put("X-Gitlab-Token", "test-secret");

        WebhookEvent result = webhookHandler.handleGitLabEvent(headers, gitlabPushPayload);

        assertNotNull(result);
        assertEquals(TriggerType.PUSH, result.getTriggerType());
        assertEquals("main", result.getBranch());
        assertEquals("b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1", result.getCommitSha());
        assertEquals("testuser", result.getTriggeredBy());
        verify(eventRepository, times(1)).save(any(WebhookEvent.class));
    }

    @Test
    void testHandleGitHubPushEvent() {
        when(projectRepository.findByGitUrl(anyString())).thenReturn(Optional.of(testProject));
        when(eventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, String> headers = new HashMap<>();
        headers.put("X-GitHub-Event", "push");
        headers.put("X-Hub-Signature-256", "sha256=test");

        WebhookEvent result = webhookHandler.handleGitHubEvent(headers, githubPushPayload);

        assertNotNull(result);
        assertEquals(TriggerType.PUSH, result.getTriggerType());
        assertEquals("main", result.getBranch());
    }

    @Test
    void testBranchFilterPass() {
        testProject.setBranchFilter("main,release/*");
        when(projectRepository.findByGitUrl(anyString())).thenReturn(Optional.of(testProject));
        when(eventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Gitlab-Event", "Push Hook");
        headers.put("X-Gitlab-Token", "test-secret");

        WebhookEvent result = webhookHandler.handleGitLabEvent(headers, gitlabPushPayload);

        assertNotNull(result);
        assertTrue(result.isFilterPassed());
    }

    @Test
    void testBranchFilterBlock() {
        testProject.setBranchFilter("release/*");
        when(projectRepository.findByGitUrl(anyString())).thenReturn(Optional.of(testProject));
        when(eventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Gitlab-Event", "Push Hook");
        headers.put("X-Gitlab-Token", "test-secret");

        WebhookEvent result = webhookHandler.handleGitLabEvent(headers, gitlabPushPayload);

        assertNotNull(result);
        assertFalse(result.isFilterPassed());
    }

    @Test
    void testPathFilterPass() {
        testProject.setPathFilter("src/**");
        when(projectRepository.findByGitUrl(anyString())).thenReturn(Optional.of(testProject));
        when(eventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Gitlab-Event", "Push Hook");
        headers.put("X-Gitlab-Token", "test-secret");

        WebhookEvent result = webhookHandler.handleGitLabEvent(headers, gitlabPushPayload);

        assertNotNull(result);
        assertTrue(result.isFilterPassed());
    }

    @Test
    void testPathFilterBlock() {
        testProject.setPathFilter("test/**");
        when(projectRepository.findByGitUrl(anyString())).thenReturn(Optional.of(testProject));
        when(eventRepository.save(any(WebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Gitlab-Event", "Push Hook");
        headers.put("X-Gitlab-Token", "test-secret");

        WebhookEvent result = webhookHandler.handleGitLabEvent(headers, gitlabPushPayload);

        assertNotNull(result);
        assertFalse(result.isFilterPassed());
    }

    @Test
    void testInvalidSignature() {
        when(projectRepository.findByGitUrl(anyString())).thenReturn(Optional.of(testProject));

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Gitlab-Event", "Push Hook");
        headers.put("X-Gitlab-Token", "wrong-secret");

        assertThrows(RuntimeException.class, () -> webhookHandler.handleGitLabEvent(headers, gitlabPushPayload));
        verify(eventRepository, never()).save(any(WebhookEvent.class));
    }

    @Test
    void testProjectNotFound() {
        when(projectRepository.findByGitUrl(anyString())).thenReturn(Optional.empty());

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Gitlab-Event", "Push Hook");
        headers.put("X-Gitlab-Token", "test-secret");

        assertThrows(RuntimeException.class, () -> webhookHandler.handleGitLabEvent(headers, gitlabPushPayload));
    }

    @Test
    void testExtractBranch() {
        String ref = "refs/heads/main";
        String branch = webhookHandler.extractBranch(ref);

        assertEquals("main", branch);
    }

    @Test
    void testExtractBranchTag() {
        String ref = "refs/tags/v1.0.0";
        String branch = webhookHandler.extractBranch(ref);

        assertEquals("v1.0.0", branch);
    }

    @Test
    void testGetChangedFiles() {
        List<String> expectedFiles = List.of("README.md", "src/main.java");

        assertEquals(expectedFiles, webhookHandler.getChangedFiles(gitlabPushPayload, "gitlab"));
    }

    @Test
    void testMatchesPathFilter() {
        List<String> changedFiles = List.of("src/main.java", "README.md", "test/UnitTest.java");

        assertTrue(webhookHandler.matchesPathFilter(changedFiles, "src/**"));
        assertFalse(webhookHandler.matchesPathFilter(changedFiles, "docs/**"));
        assertTrue(webhookHandler.matchesPathFilter(changedFiles, "**/*.java"));
        assertTrue(webhookHandler.matchesPathFilter(changedFiles, null));
        assertTrue(webhookHandler.matchesPathFilter(changedFiles, ""));
    }
}
