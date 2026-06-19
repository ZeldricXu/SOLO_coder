package middleware

import (
	"context"
	"errors"

	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/model"
	"github.com/enterprise/knowledgebase/internal/pkg/response"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"gorm.io/gorm"
)

func TenantIsolation(db *gorm.DB) gin.HandlerFunc {
	return func(c *gin.Context) {
		tenantIDStr, exists := c.Get(string(TenantIDKey))
		if !exists {
			tenantIDStr = c.GetHeader("X-Tenant-ID")
			if tenantIDStr == "" {
				response.Unauthorized(c, "tenant information missing")
				c.Abort()
				return
			}
			c.Set(string(TenantIDKey), tenantIDStr)
		}

		tidStr, ok := tenantIDStr.(string)
		if !ok || tidStr == "" {
			response.Unauthorized(c, "invalid tenant information")
			c.Abort()
			return
		}

		tenantID, err := uuid.Parse(tidStr)
		if err != nil {
			response.BadRequest(c, "invalid tenant id format")
			c.Abort()
			return
		}

		if db != nil {
			var tenant model.Tenant
			err = db.WithContext(c.Request.Context()).Where("id = ? AND status = ?", tenantID.String(), "active").First(&tenant).Error
			if err != nil {
				if errors.Is(err, gorm.ErrRecordNotFound) {
					response.Forbidden(c, "tenant not found or inactive")
				} else {
					response.InternalError(c, "failed to verify tenant")
				}
				c.Abort()
				return
			}
		}

		ctx := context.WithValue(c.Request.Context(), database.TenantIDKey, tidStr)
		c.Request = c.Request.WithContext(ctx)

		c.Next()
	}
}
