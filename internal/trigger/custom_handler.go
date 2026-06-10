package trigger

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"

	"github.com/solocoder/cloudci/internal/common/types"
)

type CustomWebhookHandler struct {
	mapping *types.PayloadMapping
	secret  string
	mapper  *PayloadMapper
}

func NewCustomWebhookHandler(mapping *types.PayloadMapping, secret string) *CustomWebhookHandler {
	return &CustomWebhookHandler{
		mapping: mapping,
		secret:  secret,
		mapper:  NewPayloadMapper(),
	}
}

func (h *CustomWebhookHandler) Handle(ctx context.Context, payload []byte, headers map[string]string) (*types.InternalEvent, error) {
	if h.mapping == nil {
		return nil, fmt.Errorf("no payload mapping configured")
	}

	if err := h.mapper.ValidateMapping(h.mapping); err != nil {
		return nil, fmt.Errorf("invalid payload mapping: %w", err)
	}

	return h.mapper.Map(payload, headers, h.mapping)
}

func (h *CustomWebhookHandler) ValidateSignature(payload []byte, signature string, secret string) bool {
	if secret == "" {
		return true
	}

	secretPrefix := "sha256="

	if h.mapping != nil && h.mapping.SecretPrefix != "" {
		secretPrefix = h.mapping.SecretPrefix
	}

	if signature == "" {
		return true
	}

	if secretPrefix != "" && !strings.HasPrefix(signature, secretPrefix) {
		return false
	}

	providedSig := signature
	if secretPrefix != "" {
		providedSig = strings.TrimPrefix(signature, secretPrefix)
	}

	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(payload)
	expectedSig := hex.EncodeToString(mac.Sum(nil))

	return hmac.Equal([]byte(providedSig), []byte(expectedSig))
}

func (h *CustomWebhookHandler) GetMapping() *types.PayloadMapping {
	return h.mapping
}

func BuildGitHubPullRequestMergeMapping() *types.PayloadMapping {
	return &types.PayloadMapping{
		EventSource: types.EventSourceGitHub,
		EventType:   types.EventTypePullRequest,
		EventHeader: "X-GitHub-Event",
		Deduplication: "pull_request.id",
		Fields: []types.FieldMapping{
			{
				Source:    "repository.full_name",
				Target:    "project_id",
				Required:  true,
			},
			{
				Source:    "pull_request.merge_commit_sha",
				Target:    "commit",
				Required:  true,
			},
			{
				Source:    "pull_request.base.ref",
				Target:    "branch",
				Transform: "trim_prefix:refs/heads/",
			},
			{
				Source:    "pull_request.title",
				Target:    "message",
			},
			{
				Source:    "pull_request.user.login",
				Target:    "author",
			},
			{
				Source:    "pull_request.merged_by.email",
				Target:    "author_email",
			},
			{
				Source:    "pull_request.base.ref",
				Target:    "ref",
			},
		},
		Condition:    "action == 'closed' && pull_request.merged == true",
		SecretHeader: "X-Hub-Signature-256",
		SecretPrefix: "sha256=",
	}
}

func BuildGitLabMergeRequestMergeMapping() *types.PayloadMapping {
	return &types.PayloadMapping{
		EventSource: types.EventSourceGitLab,
		EventType:   types.EventTypePullRequest,
		Fields: []types.FieldMapping{
			{
				Source:    "project.path_with_namespace",
				Target:    "project_id",
				Required:  true,
			},
			{
				Source:    "merge_request.merge_commit_sha",
				Target:    "commit",
				Required:  true,
			},
			{
				Source:    "merge_request.target_branch",
				Target:    "branch",
			},
			{
				Source:    "merge_request.title",
				Target:    "message",
			},
			{
				Source:    "user.username",
				Target:    "author",
			},
			{
				Source:    "user.email",
				Target:    "author_email",
			},
		},
		Condition:    "object_kind == 'merge_request' && merge_request.state == 'merged'",
		SecretHeader: "X-Gitlab-Token",
		SecretPrefix: "",
	}
}

func BuildGenericWebhookMapping(eventSource types.EventSource, eventType types.EventType) *types.PayloadMapping {
	return &types.PayloadMapping{
		EventSource: eventSource,
		EventType:   eventType,
		Deduplication: "id",
		Fields: []types.FieldMapping{
			{
				Source: "project",
				Target: "project_id",
			},
			{
				Source: "commit",
				Target: "commit",
			},
			{
				Source: "branch",
				Target: "branch",
			},
			{
				Source: "message",
				Target: "message",
			},
			{
				Source: "author",
				Target: "author",
			},
		},
	}
}
