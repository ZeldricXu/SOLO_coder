package trigger

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"testing"

	"github.com/solocoder/cloudci/internal/common/types"
	"github.com/solocoder/cloudci/tests/fixtures"
	"github.com/stretchr/testify/assert"
)

func TestGitHubHandler_Handle_Push(t *testing.T) {
	commit := fixtures.RandomCommitSHA()
	branch := "main"
	payload := fixtures.GenerateGitHubWebhookPayload(commit, branch)

	handler := &GitHubHandler{secret: "test-secret"}
	headers := map[string]string{
		"X-GitHub-Event":    "push",
		"X-GitHub-Delivery": "test-delivery-id",
	}

	event, err := handler.Handle(context.Background(), payload, headers)

	assert.NoError(t, err)
	assert.NotNil(t, event)
	assert.Equal(t, types.EventSourceGitHub, event.EventSource)
	assert.Equal(t, types.EventTypePush, event.EventType)
	assert.Equal(t, commit, event.Commit)
	assert.Equal(t, branch, event.Branch)
	assert.Equal(t, "refs/heads/main", event.Ref)
	assert.Equal(t, "org/test-repo", event.ProjectID)
	assert.Equal(t, "test commit message", event.Message)
	assert.Equal(t, "Test User", event.Author)
	assert.Equal(t, "test@example.com", event.AuthorEmail)
	assert.Equal(t, "test-delivery-id", event.DeduplicationKey)
}

func TestGitHubHandler_Handle_PullRequest(t *testing.T) {
	payload := []byte(`{
		"pull_request": {
			"title": "Test PR",
			"head": {
				"ref": "feature-branch",
				"sha": "abc123def4567890"
			},
			"base": {
				"repo": {
					"full_name": "org/test-repo"
				}
			},
			"user": {
				"login": "test-user"
			}
		}
	}`)

	handler := &GitHubHandler{secret: "test-secret"}
	headers := map[string]string{
		"X-GitHub-Event":    "pull_request",
		"X-GitHub-Delivery": "pr-delivery-id",
	}

	event, err := handler.Handle(context.Background(), payload, headers)

	assert.NoError(t, err)
	assert.NotNil(t, event)
	assert.Equal(t, types.EventSourceGitHub, event.EventSource)
	assert.Equal(t, types.EventTypePullRequest, event.EventType)
	assert.Equal(t, "abc123def4567890", event.Commit)
	assert.Equal(t, "feature-branch", event.Branch)
	assert.Equal(t, "feature-branch", event.Ref)
	assert.Equal(t, "org/test-repo", event.ProjectID)
	assert.Equal(t, "Test PR", event.Message)
	assert.Equal(t, "test-user", event.Author)
}

func TestGitLabHandler_Handle_Push(t *testing.T) {
	commit := fixtures.RandomCommitSHA()
	branch := "main"
	payload := fixtures.GenerateGitLabWebhookPayload(commit, branch)

	handler := &GitLabHandler{secret: "test-secret"}
	headers := map[string]string{}

	event, err := handler.Handle(context.Background(), payload, headers)

	assert.NoError(t, err)
	assert.NotNil(t, event)
	assert.Equal(t, types.EventSourceGitLab, event.EventSource)
	assert.Equal(t, types.EventTypePush, event.EventType)
	assert.Equal(t, commit, event.Commit)
	assert.Equal(t, branch, event.Branch)
	assert.Equal(t, "refs/heads/main", event.Ref)
	assert.Equal(t, "test commit message", event.Message)
	assert.Equal(t, "Test User", event.Author)
	assert.Equal(t, "test@example.com", event.AuthorEmail)
}

func TestGitLabHandler_Handle_MergeRequest(t *testing.T) {
	payload := []byte(`{
		"object_kind": "merge_request",
		"object_attributes": {
			"title": "Test MR",
			"source_branch": "feature-branch",
			"last_commit": {
				"id": "abc123def4567890"
			}
		},
		"project": {
			"path_with_namespace": "org/test-repo"
		}
	}`)

	handler := &GitLabHandler{secret: "test-secret"}
	headers := map[string]string{}

	event, err := handler.Handle(context.Background(), payload, headers)

	assert.NoError(t, err)
	assert.NotNil(t, event)
	assert.Equal(t, types.EventSourceGitLab, event.EventSource)
	assert.Equal(t, types.EventTypePullRequest, event.EventType)
	assert.Equal(t, "abc123def4567890", event.Commit)
	assert.Equal(t, "feature-branch", event.Branch)
	assert.Equal(t, "org/test-repo", event.ProjectID)
	assert.Equal(t, "Test MR", event.Message)
}

func TestGitHubSignature_Valid(t *testing.T) {
	secret := "my-secret"
	payload := []byte(`{"test": "data"}`)

	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(payload)
	expectedSignature := "sha256=" + hex.EncodeToString(mac.Sum(nil))

	handler := &GitHubHandler{secret: secret}
	result := handler.ValidateSignature(payload, expectedSignature, secret)

	assert.True(t, result)
}

func TestMatchesTriggers(t *testing.T) {
	ta := &TriggerAdapter{}

	triggers := []types.PipelineTrigger{
		{
			EventSource: types.EventSourceGitHub,
			EventType:   types.EventTypePush,
		},
		{
			EventSource: types.EventSourceGitLab,
			EventType:   types.EventTypePullRequest,
		},
	}

	event1 := &types.InternalEvent{
		EventSource: types.EventSourceGitHub,
		EventType:   types.EventTypePush,
	}
	assert.True(t, ta.matchesTriggers(triggers, event1))

	event2 := &types.InternalEvent{
		EventSource: types.EventSourceGitLab,
		EventType:   types.EventTypePullRequest,
	}
	assert.True(t, ta.matchesTriggers(triggers, event2))

	event3 := &types.InternalEvent{
		EventSource: types.EventSourceGitHub,
		EventType:   types.EventTypePullRequest,
	}
	assert.False(t, ta.matchesTriggers(triggers, event3))
}

func TestMatchesCondition_Branch(t *testing.T) {
	ta := &TriggerAdapter{}

	cond := &types.TriggerCondition{
		Branch: "main",
	}

	event1 := &types.InternalEvent{
		Branch: "main",
	}
	assert.True(t, ta.matchesCondition(cond, event1))

	event2 := &types.InternalEvent{
		Branch: "develop",
	}
	assert.False(t, ta.matchesCondition(cond, event2))

	event3 := &types.InternalEvent{
		Branch: "feature-branch",
	}
	assert.False(t, ta.matchesCondition(cond, event3))
}

func TestHandleMalformedPayload(t *testing.T) {
	payload := fixtures.GenerateMalformedWebhookPayload()

	githubHandler := &GitHubHandler{secret: "test-secret"}
	githubHeaders := map[string]string{
		"X-GitHub-Event": "push",
	}
	event, err := githubHandler.Handle(context.Background(), payload, githubHeaders)

	assert.Error(t, err)
	assert.Nil(t, event)
	assert.NotPanics(t, func() {
		githubHandler.Handle(context.Background(), payload, githubHeaders)
	})

	gitlabHandler := &GitLabHandler{secret: "test-secret"}
	event, err = gitlabHandler.Handle(context.Background(), payload, map[string]string{})

	assert.Error(t, err)
	assert.Nil(t, event)
	assert.NotPanics(t, func() {
		gitlabHandler.Handle(context.Background(), payload, map[string]string{})
	})
}

func TestGitHubSignature_Invalid(t *testing.T) {
	secret := "my-secret"
	payload := []byte(`{"test": "data"}`)
	invalidSignature := "sha256=invalid-signature-here"

	handler := &GitHubHandler{secret: secret}
	result := handler.ValidateSignature(payload, invalidSignature, secret)

	assert.False(t, result)
}

func TestGitHubSignature_EmptySecret(t *testing.T) {
	payload := []byte(`{"test": "data"}`)
	signature := "sha256=some-signature"

	handler := &GitHubHandler{}
	result := handler.ValidateSignature(payload, signature, "")

	assert.True(t, result)

	result2 := handler.ValidateSignature(payload, "", "my-secret")
	assert.True(t, result2)
}

func TestMatchesTriggers_EmptyTriggers(t *testing.T) {
	ta := &TriggerAdapter{}

	event := &types.InternalEvent{
		EventSource: types.EventSourceGitHub,
		EventType:   types.EventTypePush,
	}

	assert.True(t, ta.matchesTriggers(nil, event))
	assert.True(t, ta.matchesTriggers([]types.PipelineTrigger{}, event))
}

func TestMatchesTriggers_WrongSource(t *testing.T) {
	ta := &TriggerAdapter{}

	triggers := []types.PipelineTrigger{
		{
			EventSource: types.EventSourceGitHub,
			EventType:   types.EventTypePush,
		},
	}

	event := &types.InternalEvent{
		EventSource: types.EventSourceGitLab,
		EventType:   types.EventTypePush,
	}

	assert.False(t, ta.matchesTriggers(triggers, event))
}

func TestMatchesTriggers_WrongType(t *testing.T) {
	ta := &TriggerAdapter{}

	triggers := []types.PipelineTrigger{
		{
			EventSource: types.EventSourceGitHub,
			EventType:   types.EventTypePush,
		},
	}

	event := &types.InternalEvent{
		EventSource: types.EventSourceGitHub,
		EventType:   types.EventTypePullRequest,
	}

	assert.False(t, ta.matchesTriggers(triggers, event))
}

func TestGitHubHandler_Handle_Tag(t *testing.T) {
	payload := []byte(fmt.Sprintf(`{
		"ref": "refs/tags/v1.0.0",
		"repository": {
			"full_name": "org/test-repo"
		},
		"head_commit": {
			"id": "%s",
			"message": "tag commit"
		}
	}`, fixtures.RandomCommitSHA()))

	handler := &GitHubHandler{secret: "test-secret"}
	headers := map[string]string{
		"X-GitHub-Event":    "push",
		"X-GitHub-Delivery": "tag-delivery-id",
	}

	event, err := handler.Handle(context.Background(), payload, headers)

	assert.NoError(t, err)
	assert.NotNil(t, event)
	assert.Equal(t, types.EventTypeTag, event.EventType)
	assert.Equal(t, "v1.0.0", event.Tag)
	assert.Equal(t, "refs/tags/v1.0.0", event.Ref)
	assert.Equal(t, "org/test-repo", event.ProjectID)
}

func TestGitLabHandler_Handle_TagPush(t *testing.T) {
	payload := []byte(`{
		"object_kind": "tag_push",
		"ref": "refs/tags/v1.0.0",
		"project": {
			"path_with_namespace": "org/test-repo"
		}
	}`)

	handler := &GitLabHandler{secret: "test-secret"}
	event, err := handler.Handle(context.Background(), payload, map[string]string{})

	assert.NoError(t, err)
	assert.NotNil(t, event)
	assert.Equal(t, types.EventTypeTag, event.EventType)
	assert.Equal(t, "v1.0.0", event.Tag)
	assert.Equal(t, "refs/tags/v1.0.0", event.Ref)
	assert.Equal(t, "org/test-repo", event.ProjectID)
}

func TestMatchesCondition_Tags(t *testing.T) {
	ta := &TriggerAdapter{}

	cond := &types.TriggerCondition{
		Tags: []string{"v1.0.0", "v2.0.0"},
	}

	event1 := &types.InternalEvent{Tag: "v1.0.0"}
	assert.True(t, ta.matchesCondition(cond, event1))

	event2 := &types.InternalEvent{Tag: "v2.0.0"}
	assert.True(t, ta.matchesCondition(cond, event2))

	event3 := &types.InternalEvent{Tag: "v3.0.0"}
	assert.False(t, ta.matchesCondition(cond, event3))
}

func TestMatchesCondition_EventTypes(t *testing.T) {
	ta := &TriggerAdapter{}

	cond := &types.TriggerCondition{
		EventTypes: []string{"push", "pull_request"},
	}

	event1 := &types.InternalEvent{EventType: types.EventTypePush}
	assert.True(t, ta.matchesCondition(cond, event1))

	event2 := &types.InternalEvent{EventType: types.EventTypePullRequest}
	assert.True(t, ta.matchesCondition(cond, event2))

	event3 := &types.InternalEvent{EventType: types.EventTypeTag}
	assert.False(t, ta.matchesCondition(cond, event3))
}

func TestMatchesTriggers_WithCondition(t *testing.T) {
	ta := &TriggerAdapter{}

	triggers := []types.PipelineTrigger{
		{
			EventSource: types.EventSourceGitHub,
			EventType:   types.EventTypePush,
			Condition: &types.TriggerCondition{
				Branch: "main",
			},
		},
	}

	event1 := &types.InternalEvent{
		EventSource: types.EventSourceGitHub,
		EventType:   types.EventTypePush,
		Branch:      "main",
	}
	assert.True(t, ta.matchesTriggers(triggers, event1))

	event2 := &types.InternalEvent{
		EventSource: types.EventSourceGitHub,
		EventType:   types.EventTypePush,
		Branch:      "develop",
	}
	assert.False(t, ta.matchesTriggers(triggers, event2))
}

func TestGitLabSignature_Valid(t *testing.T) {
	secret := "my-secret"
	payload := []byte(`{"test": "data"}`)

	handler := &GitLabHandler{secret: secret}
	result := handler.ValidateSignature(payload, secret, secret)

	assert.True(t, result)
}

func TestGitLabSignature_Invalid(t *testing.T) {
	secret := "my-secret"
	wrongSecret := "wrong-secret"
	payload := []byte(`{"test": "data"}`)

	handler := &GitLabHandler{secret: secret}
	result := handler.ValidateSignature(payload, wrongSecret, secret)

	assert.False(t, result)
}

func TestGitLabSignature_EmptySecret(t *testing.T) {
	payload := []byte(`{"test": "data"}`)

	handler := &GitLabHandler{}
	result := handler.ValidateSignature(payload, "any-token", "")

	assert.True(t, result)
}

func TestGitHubHandler_Handle_Release(t *testing.T) {
	payload := []byte(`{
		"release": {
			"tag_name": "v1.0.0",
			"name": "Release v1.0.0"
		},
		"repository": {
			"full_name": "org/test-repo"
		}
	}`)

	handler := &GitHubHandler{secret: "test-secret"}
	headers := map[string]string{
		"X-GitHub-Event":    "release",
		"X-GitHub-Delivery": "release-delivery-id",
	}

	event, err := handler.Handle(context.Background(), payload, headers)

	assert.NoError(t, err)
	assert.NotNil(t, event)
	assert.Equal(t, types.EventTypeRelease, event.EventType)
	assert.Equal(t, "v1.0.0", event.Tag)
	assert.Equal(t, "Release v1.0.0", event.Message)
	assert.Equal(t, "org/test-repo", event.ProjectID)
}

func TestGitHubHandler_Handle_CreateTag(t *testing.T) {
	payload := []byte(`{
		"ref_type": "tag",
		"ref": "v1.0.0",
		"repository": {
			"full_name": "org/test-repo"
		}
	}`)

	handler := &GitHubHandler{secret: "test-secret"}
	headers := map[string]string{
		"X-GitHub-Event":    "create",
		"X-GitHub-Delivery": "create-delivery-id",
	}

	event, err := handler.Handle(context.Background(), payload, headers)

	assert.NoError(t, err)
	assert.NotNil(t, event)
	assert.Equal(t, types.EventTypeTag, event.EventType)
	assert.Equal(t, "v1.0.0", event.Tag)
	assert.Equal(t, "org/test-repo", event.ProjectID)
}
