package ratelimit

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"

	"DF1-56/internal/models"
)

type KeyBuilder struct{}

func NewKeyBuilder() *KeyBuilder {
	return &KeyBuilder{}
}

func (kb *KeyBuilder) BuildKey(ctx *models.GatewayContext, builder models.RateLimitKeyBuilder, dimension models.RateLimitDimension, customKey string) string {
	var parts []string

	parts = append(parts, string(dimension))

	if builder.IncludeAPI && ctx.Route != nil {
		parts = append(parts, ctx.Route.ID)
	}

	if builder.IncludeUser && ctx.UserID != "" {
		parts = append(parts, ctx.UserID)
	}

	if builder.IncludeIP && ctx.ClientIP != "" {
		parts = append(parts, ctx.ClientIP)
	}

	for _, header := range builder.CustomHeaders {
		if ctx.Request != nil {
			val := ctx.Request.Header.Get(header)
			if val != "" {
				parts = append(parts, fmt.Sprintf("%s:%s", header, val))
			}
		}
	}

	if dimension == models.DimensionCustom && customKey != "" {
		parts = append(parts, customKey)
	}

	key := strings.Join(parts, ":")

	if len(key) > 128 {
		hash := sha256.Sum256([]byte(key))
		key = hex.EncodeToString(hash[:])
	}

	return key
}

func (kb *KeyBuilder) BuildRuleKey(ctx *models.GatewayContext, rule models.RateLimitRule, builder models.RateLimitKeyBuilder) string {
	return kb.BuildKey(ctx, builder, rule.Dimension, rule.CustomKey)
}
