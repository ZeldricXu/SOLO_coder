package trigger

import (
	"encoding/json"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/solocoder/cloudci/internal/common/types"
)

func TestPayloadMapper_Map_SimpleFields(t *testing.T) {
	t.Parallel()
	mapper := NewPayloadMapper()

	payload := []byte(`{
		"ref": "refs/heads/main",
		"head_commit": {
			"id": "abc123def456",
			"message": "feat: new feature",
			"author": {
				"name": "John Doe",
				"email": "john@example.com"
			}
		},
		"repository": {
			"full_name": "org/repo"
		},
		"action": "closed",
		"pull_request": {
			"merged": true,
			"number": 42,
			"base": {"ref": "main"},
			"title": "Add login feature"
		}
	}`)

	mapping := &types.PayloadMapping{
		EventSource: types.EventSourceGitHub,
		EventType:   types.EventTypePullRequest,
		Deduplication: "$.head_commit.id",
		Condition: "$.action == 'closed' && $.pull_request.merged == true",
		Fields: []types.FieldMapping{
			{
				Source:   "$.head_commit.id",
				Target:   "commit",
				Required: true,
			},
			{
				Source:    "$.ref",
				Target:    "ref",
				Transform: "trim_prefix:refs/heads/",
			},
			{
				Source:   "$.head_commit.message",
				Target:   "message",
				Required: true,
			},
			{
				Source: "$.head_commit.author.name",
				Target: "author",
			},
			{
				Source: "$.head_commit.author.email",
				Target: "author_email",
			},
			{
				Source:   "$.repository.full_name",
				Target:   "project_id",
				Required: true,
			},
			{
				Source: "$.pull_request.base.ref",
				Target: "branch",
			},
		},
	}

	event, err := mapper.Map(payload, nil, mapping)
	require.NoError(t, err)

	assert.Equal(t, "abc123def456", event.Commit)
	assert.Equal(t, "main", event.Ref)
	assert.Equal(t, "feat: new feature", event.Message)
	assert.Equal(t, "John Doe", event.Author)
	assert.Equal(t, "john@example.com", event.AuthorEmail)
	assert.Equal(t, "org/repo", event.ProjectID)
	assert.Equal(t, "main", event.Branch)
	assert.Equal(t, types.EventSourceGitHub, event.EventSource)
	assert.Equal(t, types.EventTypePullRequest, event.EventType)
}

func TestPayloadMapper_Map_DefaultValues(t *testing.T) {
	t.Parallel()
	mapper := NewPayloadMapper()

	payload := []byte(`{"name": "test"}`)

	mapping := &types.PayloadMapping{
		EventSource: types.EventSourceWebhook,
		EventType:   types.EventTypePush,
		Fields: []types.FieldMapping{
			{
				Source:  "$.missing_field",
				Target:  "branch",
				Default: "main",
			},
			{
				Source:  "$.nonexistent",
				Target:  "commit",
				Default: "unknown",
			},
		},
	}

	event, err := mapper.Map(payload, nil, mapping)
	require.NoError(t, err)

	assert.Equal(t, "main", event.Branch)
	assert.Equal(t, "unknown", event.Commit)
}

func TestPayloadMapper_Map_RequiredFieldMissing(t *testing.T) {
	t.Parallel()
	mapper := NewPayloadMapper()

	payload := []byte(`{"name": "test"}`)

	mapping := &types.PayloadMapping{
		EventSource: types.EventSourceWebhook,
		EventType:   types.EventTypePush,
		Fields: []types.FieldMapping{
			{
				Source:   "$.commit_sha",
				Target:   "commit",
				Required: true,
			},
		},
	}

	_, err := mapper.Map(payload, nil, mapping)
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "required field")
}

func TestPayloadMapper_Map_Transforms(t *testing.T) {
	t.Parallel()
	mapper := NewPayloadMapper()

	payload := []byte(`{
		"ref": "refs/heads/feature-login",
		"message": "  FEAT: add login  ",
		"name": "Test-Repo"
	}`)

	mapping := &types.PayloadMapping{
		EventSource: types.EventSourceGitLab,
		EventType:   types.EventTypePush,
		Fields: []types.FieldMapping{
			{
				Source:    "$.ref",
				Target:    "branch",
				Transform: "trim_prefix:refs/heads/",
			},
			{
				Source:    "$.message",
				Target:    "message",
				Transform: "lower,trim",
			},
			{
				Source:    "$.name",
				Target:    "project_id",
				Transform: "replace:-:_",
			},
		},
	}

	event, err := mapper.Map(payload, nil, mapping)
	require.NoError(t, err)

	assert.Equal(t, "feature-login", event.Branch)
	assert.Equal(t, "feat: add login", event.Message)
	assert.Equal(t, "Test_Repo", event.ProjectID)
}

func TestPayloadMapper_Map_Condition(t *testing.T) {
	t.Parallel()
	mapper := NewPayloadMapper()

	payload := []byte(`{"action": "closed", "merged": false}`)

	mapping := &types.PayloadMapping{
		EventSource: types.EventSourceGitHub,
		EventType:   types.EventTypePullRequest,
		Condition:   "$.action == 'closed' && $.merged == true",
		Fields: []types.FieldMapping{
			{Source: "$.action", Target: "message"},
		},
	}

	event, err := mapper.Map(payload, nil, mapping)
	require.NoError(t, err)
	assert.Nil(t, event)
}

func TestPayloadMapper_ValidateMapping(t *testing.T) {
	t.Parallel()
	mapper := NewPayloadMapper()

	tests := []struct {
		name    string
		mapping *types.PayloadMapping
		wantErr bool
	}{
		{
			name: "valid mapping",
			mapping: &types.PayloadMapping{
				EventSource: types.EventSourceGitHub,
				EventType:   types.EventTypePush,
				Fields: []types.FieldMapping{
					{Source: "$.commit", Target: "commit"},
				},
			},
			wantErr: false,
		},
		{
			name: "missing event source",
			mapping: &types.PayloadMapping{
				EventType: types.EventTypePush,
				Fields: []types.FieldMapping{
					{Source: "$.commit", Target: "commit"},
				},
			},
			wantErr: true,
		},
		{
			name: "missing fields",
			mapping: &types.PayloadMapping{
				EventSource: types.EventSourceGitHub,
				EventType:   types.EventTypePush,
				Fields:      []types.FieldMapping{},
			},
			wantErr: true,
		},
		{
			name: "field missing source",
			mapping: &types.PayloadMapping{
				EventSource: types.EventSourceGitHub,
				EventType:   types.EventTypePush,
				Fields: []types.FieldMapping{
					{Target: "commit"},
				},
			},
			wantErr: true,
		},
		{
			name: "field missing target",
			mapping: &types.PayloadMapping{
				EventSource: types.EventSourceGitHub,
				EventType:   types.EventTypePush,
				Fields: []types.FieldMapping{
					{Source: "$.commit"},
				},
			},
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := mapper.ValidateMapping(tt.mapping)
			if tt.wantErr {
				assert.Error(t, err)
			} else {
				assert.NoError(t, err)
			}
		})
	}
}

func TestPayloadMapper_Map_Headers(t *testing.T) {
	t.Parallel()
	mapper := NewPayloadMapper()

	payload := []byte(`{"data": "test"}`)
	headers := map[string]string{
		"X-GitHub-Event": "pull_request",
		"X-Request-ID":   "req-12345",
	}

	mapping := &types.PayloadMapping{
		EventSource: types.EventSourceGitHub,
		EventType:   types.EventTypePullRequest,
		EventHeader: "X-GitHub-Event",
		Fields: []types.FieldMapping{
			{
				Source:   "$.data",
				Target:   "message",
				Required: true,
			},
		},
	}

	event, err := mapper.Map(payload, headers, mapping)
	require.NoError(t, err)

	assert.Equal(t, "test", event.Message)
	assert.Equal(t, types.EventTypePullRequest, event.EventType)
	assert.NotNil(t, event.Payload)
	assert.Equal(t, "pull_request", event.Payload["headers_X-GitHub-Event"])
}

func TestBuildGitHubPullRequestMergeMapping(t *testing.T) {
	t.Parallel()
	mapping := BuildGitHubPullRequestMergeMapping()

	assert.Equal(t, types.EventSourceGitHub, mapping.EventSource)
	assert.Equal(t, types.EventTypePullRequest, mapping.EventType)
	assert.Equal(t, "X-GitHub-Event", mapping.EventHeader)
	assert.NotEmpty(t, mapping.Fields)
	assert.Contains(t, mapping.Condition, "closed")
	assert.Contains(t, mapping.Condition, "merged")

	payload := []byte(`{
		"action": "closed",
		"pull_request": {
			"merged": true,
			"merge_commit_sha": "abc123",
			"title": "Fix bug #123",
			"user": {"login": "johndoe"},
			"base": {"ref": "main"},
			"head": {"ref": "fix-bug"},
			"merged_by": {"email": "john@example.com"}
		},
		"repository": {"full_name": "org/repo"},
		"number": 42
	}`)

	mapper := NewPayloadMapper()
	event, err := mapper.Map(payload, map[string]string{"X-GitHub-Event": "pull_request"}, mapping)
	require.NoError(t, err)

	assert.Equal(t, "abc123", event.Commit)
	assert.Equal(t, "Fix bug #123", event.Message)
	assert.Equal(t, "johndoe", event.Author)
	assert.Equal(t, "john@example.com", event.AuthorEmail)
	assert.Equal(t, "main", event.Branch)
	assert.Equal(t, "org/repo", event.ProjectID)
}

func TestBuildGitLabMergeRequestMergeMapping(t *testing.T) {
	t.Parallel()
	mapping := BuildGitLabMergeRequestMergeMapping()

	assert.Equal(t, types.EventSourceGitLab, mapping.EventSource)
	assert.Equal(t, types.EventTypePullRequest, mapping.EventType)
	assert.NotEmpty(t, mapping.Fields)
}

func TestPayloadMapper_Map_PayloadPreservation(t *testing.T) {
	t.Parallel()
	mapper := NewPayloadMapper()

	payload := []byte(`{
		"commit": "abc123",
		"custom_field": "custom_value",
		"nested": {"key": "value"}
	}`)

	mapping := &types.PayloadMapping{
		EventSource: types.EventSourceWebhook,
		EventType:   types.EventTypePush,
		Fields: []types.FieldMapping{
			{Source: "$.commit", Target: "commit"},
		},
	}

	event, err := mapper.Map(payload, nil, mapping)
	require.NoError(t, err)

	assert.NotNil(t, event.Payload)

	var originalPayload map[string]interface{}
	err = json.Unmarshal(payload, &originalPayload)
	require.NoError(t, err)

	for k, v := range originalPayload {
		assert.Equal(t, v, event.Payload[k], "payload field %s should be preserved", k)
	}
}
