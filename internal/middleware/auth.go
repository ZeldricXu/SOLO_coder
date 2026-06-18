package middleware

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/enterprise/knowledgebase/internal/config"
	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/enterprise/knowledgebase/internal/pkg/jwt"
	"github.com/enterprise/knowledgebase/internal/pkg/response"
	"github.com/enterprise/knowledgebase/internal/repository"
	"github.com/gin-gonic/gin"
	"github.com/go-redis/redis/v8"
	"github.com/google/uuid"
)

type ContextKey string

const (
	UserContextKey   ContextKey = "current_user"
	TenantContextKey ContextKey = "current_tenant"
	RoleContextKey   ContextKey = "current_role"
	TokenContextKey  ContextKey = "api_token"
)

func AuthMiddleware(
	jwtCfg config.JWTConfig,
	userRepo *repository.UserRepository,
	tenantRepo *repository.TenantRepository,
) gin.HandlerFunc {
	return func(c *gin.Context) {
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			response.Unauthorized(c, "authorization header required")
			c.Abort()
			return
		}

		parts := strings.SplitN(authHeader, " ", 2)
		if len(parts) != 2 {
			response.Unauthorized(c, "invalid authorization format")
			c.Abort()
			return
		}

		scheme, token := strings.ToLower(parts[0]), parts[1]

		switch scheme {
		case "bearer":
			handleJWTAuth(c, token, jwtCfg, userRepo, tenantRepo)
		case "token":
			handleAPITokenAuth(c, token, userRepo)
		default:
			response.Unauthorized(c, "unsupported auth scheme")
			c.Abort()
			return
		}
	}
}

func handleJWTAuth(c *gin.Context, token string, jwtCfg config.JWTConfig,
	userRepo *repository.UserRepository, tenantRepo *repository.TenantRepository,
) {
	claims, err := jwt.ParseToken(token, jwtCfg.Secret)
	if err != nil {
		response.Unauthorized(c, err.Error())
		c.Abort()
		return
	}

	tenantCtx := database.WithTenant(context.Background(), claims.TenantID)
	user, err := userRepo.GetByID(tenantCtx, claims.UserID)
	if err != nil {
		response.Unauthorized(c, "user not found")
		c.Abort()
		return
	}
	if user.Status != model.UserStatusActive {
		response.Unauthorized(c, "user account is not active")
		c.Abort()
		return
	}

	tenant, err := tenantRepo.GetByID(c.Request.Context(), claims.TenantID)
	if err != nil {
		response.Unauthorized(c, "tenant not found")
		c.Abort()
		return
	}
	if tenant.Status != model.TenantStatusActive {
		response.Unauthorized(c, "tenant is not active")
		c.Abort()
		return
	}

	c.Set(string(UserContextKey), user)
	c.Set(string(TenantContextKey), tenant)
	c.Set(string(RoleContextKey), claims.Role)
	c.Request = c.Request.WithContext(database.WithTenant(c.Request.Context(), claims.TenantID))

	c.Next()
}

func handleAPITokenAuth(c *gin.Context, rawToken string, userRepo *repository.UserRepository) {
	tenantHeader := c.GetHeader("X-Tenant-ID")
	if tenantHeader == "" {
		response.Unauthorized(c, "X-Tenant-ID header required for API token auth")
		c.Abort()
		return
	}
	tenantID, err := uuid.Parse(tenantHeader)
	if err != nil {
		response.Unauthorized(c, "invalid X-Tenant-ID")
		c.Abort()
		return
	}

	if len(rawToken) < 12 {
		response.Unauthorized(c, "invalid token format")
		c.Abort()
		return
	}

	sum := sha256.Sum256([]byte(rawToken))
	tokenHash := hex.EncodeToString(sum[:])

	tenantCtx := database.WithTenant(context.Background(), tenantID)
	apiToken, err := userRepo.GetAPITokenByHash(tenantCtx, tokenHash)
	if err != nil {
		response.Unauthorized(c, "invalid token")
		c.Abort()
		return
	}

	if !apiToken.IsActive() {
		response.Unauthorized(c, "token is not active")
		c.Abort()
		return
	}

	if len(apiToken.IPWhitelist) > 0 {
		clientIP := c.ClientIP()
		allowed := false
		for _, ip := range apiToken.IPWhitelist {
			if ip == clientIP {
				allowed = true
				break
			}
		}
		if !allowed {
			response.Forbidden(c, "IP not allowed")
			c.Abort()
			return
		}
	}

	user, err := userRepo.GetByID(tenantCtx, apiToken.UserID)
	if err != nil {
		response.Unauthorized(c, "user not found")
		c.Abort()
		return
	}

	tenant := &model.Tenant{BaseModel: model.BaseModel{ID: tenantID}}

	c.Set(string(UserContextKey), user)
	c.Set(string(TenantContextKey), tenant)
	c.Set(string(TokenContextKey), apiToken)
	c.Request = c.Request.WithContext(database.WithTenant(c.Request.Context(), tenantID))

	clientIP := c.ClientIP()
	_ = userRepo.UpdateAPITokenUsage(tenantCtx, apiToken.ID, clientIP)

	c.Next()
}

func TenantMiddleware(tenantRepo *repository.TenantRepository) gin.HandlerFunc {
	return func(c *gin.Context) {
		if _, exists := c.Get(string(TenantContextKey)); exists {
			c.Next()
			return
		}

		tenantHeader := c.GetHeader("X-Tenant-ID")
		namespaceHeader := c.GetHeader("X-Tenant-Namespace")
		host := c.Request.Host

		var tenant *model.Tenant
		var err error
		ctx := c.Request.Context()

		if tenantHeader != "" {
			var tenantID uuid.UUID
			tenantID, err = uuid.Parse(tenantHeader)
			if err == nil {
				tenant, err = tenantRepo.GetByID(ctx, tenantID)
			}
		}
		if (err != nil || tenant == nil) && namespaceHeader != "" {
			tenant, err = tenantRepo.GetByNamespace(ctx, namespaceHeader)
		}
		if (err != nil || tenant == nil) && host != "" {
			tenant, err = tenantRepo.GetByDomain(ctx, host)
		}

		if tenant == nil {
			response.Unauthorized(c, "tenant not found")
			c.Abort()
			return
		}
		if tenant.Status != model.TenantStatusActive {
			response.Unauthorized(c, "tenant is not active")
			c.Abort()
			return
		}

		c.Set(string(TenantContextKey), tenant)
		c.Request = c.Request.WithContext(database.WithTenant(c.Request.Context(), tenant.ID))
		c.Next()
	}
}

func CORSMiddleware(cfg config.CORSConfig) gin.HandlerFunc {
	allowOrigins := strings.Split(cfg.AllowOrigins, ",")
	allowMethods := strings.Split(cfg.AllowMethods, ",")
	allowHeaders := strings.Split(cfg.AllowHeaders, ",")

	return func(c *gin.Context) {
		origin := c.Request.Header.Get("Origin")
		allowedOrigin := "*"
		for _, o := range allowOrigins {
			if o == "*" || strings.TrimSpace(o) == origin {
				allowedOrigin = origin
				break
			}
		}

		c.Writer.Header().Set("Access-Control-Allow-Origin", allowedOrigin)
		c.Writer.Header().Set("Access-Control-Allow-Methods", strings.Join(allowMethods, ","))
		c.Writer.Header().Set("Access-Control-Allow-Headers", strings.Join(allowHeaders, ","))
		c.Writer.Header().Set("Access-Control-Allow-Credentials", "true")
		c.Writer.Header().Set("Access-Control-Max-Age", "86400")

		if c.Request.Method == http.MethodOptions {
			c.AbortWithStatus(http.StatusNoContent)
			return
		}
		c.Next()
	}
}

func RateLimitMiddleware(redisClient *database.RedisClient, userRepo *repository.UserRepository) gin.HandlerFunc {
	return func(c *gin.Context) {
		var limitKey string
		var limit int

		if tokenVal, exists := c.Get(string(TokenContextKey)); exists {
			token, ok := tokenVal.(*model.ApiToken)
			if ok {
				limitKey = "ratelimit:token:" + token.ID.String()
				limit = token.RateLimit
			}
		}

		if limitKey == "" {
			if userVal, exists := c.Get(string(UserContextKey)); exists {
				user, ok := userVal.(*model.User)
				if ok {
					tenantID, _ := database.GetTenantID(c.Request.Context())
					limitKey = fmtKey("ratelimit:user:%s:%s", tenantID, user.ID)
					limit = 5000
				}
			}
		}

		if limitKey == "" {
			ip := c.ClientIP()
			limitKey = fmtKey("ratelimit:ip:%s", ip)
			limit = 500
		}

		if limit <= 0 {
			limit = 1000
		}

		ctx := c.Request.Context()
		pipe := redisClient.TxPipeline()
		incr := pipe.Incr(ctx, limitKey)
		pipe.Expire(ctx, limitKey, time.Minute)
		_, _ = pipe.Exec(ctx)

		count := incr.Val()
		if count == 1 {
			pipe2 := redisClient.TxPipeline()
			pipe2.Expire(ctx, limitKey, time.Minute)
			_, _ = pipe2.Exec(ctx)
		}

		if count > int64(limit) {
			response.TooManyRequests(c, "rate limit exceeded")
			c.Abort()
			return
		}

		c.Writer.Header().Set("X-RateLimit-Limit", fmt.Sprintf("%d", limit))
		c.Writer.Header().Set("X-RateLimit-Remaining", fmt.Sprintf("%d", int64(limit)-count))
		c.Writer.Header().Set("X-RateLimit-Reset", fmt.Sprintf("%d", time.Now().Add(time.Minute).Unix()))

		c.Next()
	}
}

func RateLimitMiddlewareV2(rdb *redis.Client, userRepo *repository.UserRepository) gin.HandlerFunc {
	_ = rdb
	_ = userRepo
	return RateLimitMiddleware(nil, userRepo)
}

func QuotaMiddleware(tenantRepo *repository.TenantRepository, resourceType string) gin.HandlerFunc {
	return func(c *gin.Context) {
		tenant, exists := c.Get(string(TenantContextKey))
		if !exists {
			c.Next()
			return
		}
		t, ok := tenant.(*model.Tenant)
		if !ok {
			c.Next()
			return
		}

		ok, err := tenantRepo.CheckQuotaAndIncrement(c.Request.Context(), t.ID, resourceType, 1)
		if err != nil {
			c.Next()
			return
		}
		if !ok {
			response.ErrorWithStatus(c, http.StatusPaymentRequired, 402,
				resourceType+" quota exceeded, please upgrade your plan")
			c.Abort()
			return
		}
		c.Next()
	}
}

func PermissionMiddleware(
	permRepo *repository.PermissionRepository,
	userRepo *repository.UserRepository,
	resourceType model.ResourceType,
	requiredAction model.PermissionAction,
) gin.HandlerFunc {
	return func(c *gin.Context) {
		userVal, exists := c.Get(string(UserContextKey))
		if !exists {
			response.Forbidden(c, "authentication required")
			c.Abort()
			return
		}
		user, ok := userVal.(*model.User)
		if !ok {
			response.Forbidden(c, "invalid user context")
			c.Abort()
			return
		}
		if user.IsSuperAdmin {
			c.Next()
			return
		}

		roleVal, _ := c.Get(string(RoleContextKey))
		if roleStr, ok := roleVal.(string); ok {
			if model.HasSufficientRole(model.RoleViewer, model.Role(roleStr)) {
				can, _ := checkActionByRole(model.Role(roleStr), requiredAction)
				if can {
					c.Next()
					return
				}
			}
		}

		tenantCtx := c.Request.Context()
		groups, _ := userRepo.GetUserGroups(tenantCtx, user.ID)
		groupIDs := make([]uuid.UUID, 0, len(groups))
		for _, g := range groups {
			groupIDs = append(groupIDs, g.ID)
		}
		var deptIDs []uuid.UUID
		if user.DepartmentID != nil {
			deptIDs = append(deptIDs, *user.DepartmentID)
		}

		var resourceID uuid.UUID
		switch resourceType {
		case model.ResourceTypeSpace:
			idStr := c.Param("space_id")
			resourceID, _ = uuid.Parse(idStr)
		case model.ResourceTypeDocument:
			idStr := c.Param("doc_id")
			resourceID, _ = uuid.Parse(idStr)
		case model.ResourceTypeDirectory:
			idStr := c.Param("dir_id")
			resourceID, _ = uuid.Parse(idStr)
		}

		allowed, err := permRepo.CheckPermission(tenantCtx, user.ID, groupIDs, deptIDs, resourceType, resourceID, requiredAction)
		if err != nil || !allowed {
			response.Forbidden(c, "insufficient permissions")
			c.Abort()
			return
		}
		c.Next()
	}
}

func checkActionByRole(role model.Role, action model.PermissionAction) (bool, error) {
	return model.RoleCan(role, action), nil
}

func APITokenScopeMiddleware(requiredScope string) gin.HandlerFunc {
	return func(c *gin.Context) {
		tokenVal, exists := c.Get(string(TokenContextKey))
		if !exists {
			c.Next()
			return
		}
		token, ok := tokenVal.(*model.ApiToken)
		if !ok {
			c.Next()
			return
		}
		if !token.HasScope(requiredScope) {
			response.Forbidden(c, "token does not have required scope: "+requiredScope)
			c.Abort()
			return
		}
		c.Next()
	}
}

func GetCurrentUser(c *gin.Context) *model.User {
	val, exists := c.Get(string(UserContextKey))
	if !exists {
		return nil
	}
	user, ok := val.(*model.User)
	if !ok {
		return nil
	}
	return user
}

func GetCurrentTenant(c *gin.Context) *model.Tenant {
	val, exists := c.Get(string(TenantContextKey))
	if !exists {
		return nil
	}
	tenant, ok := val.(*model.Tenant)
	if !ok {
		return nil
	}
	return tenant
}

func GetCurrentRole(c *gin.Context) string {
	val, exists := c.Get(string(RoleContextKey))
	if !exists {
		return ""
	}
	role, _ := val.(string)
	return role
}

func GetAPIToken(c *gin.Context) *model.ApiToken {
	val, exists := c.Get(string(TokenContextKey))
	if !exists {
		return nil
	}
	token, ok := val.(*model.ApiToken)
	if !ok {
		return nil
	}
	return token
}

func fmtKey(format string, args ...interface{}) string {
	return joinStrings(args, format)
}

func joinStrings(args []interface{}, format string) string {
	result := format
	for i, arg := range args {
		_ = i
		switch v := arg.(type) {
		case uuid.UUID:
			idx := indexOfUUIDPlaceholder(result)
			if idx != -1 {
				result = result[:idx] + "%s" + result[idx+2:]
			}
			result = replaceFirst(result, v.String())
		case string:
			result = replaceFirst(result, v)
		case int:
			result = replaceFirst(result, string(rune(v)))
		}
	}
	return result
}

func replaceFirst(s, repl string) string {
	for i := 0; i < len(s)-1; i++ {
		if s[i] == '%' && s[i+1] == 's' {
			return s[:i] + repl + s[i+2:]
		}
		if s[i] == '%' && s[i+1] == 'd' {
			return s[:i] + repl + s[i+2:]
		}
	}
	return s
}

func indexOfUUIDPlaceholder(s string) int {
	return -1
}
