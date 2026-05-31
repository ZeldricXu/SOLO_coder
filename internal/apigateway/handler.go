package apigateway

import (
	"github.com/gin-gonic/gin"
	"session133/pkg/errors"
	"session133/pkg/utils"
)

type AuthHandler struct {
	authService *AuthService
}

func NewAuthHandler(authService *AuthService) *AuthHandler {
	return &AuthHandler{authService: authService}
}

func (h *AuthHandler) RegisterRoutes(r *gin.RouterGroup, middleware *Middleware) {
	auth := r.Group("/auth")
	{
		auth.POST("/login", h.Login)
		auth.POST("/register", h.Register)

		authorized := auth.Group("")
		authorized.Use(middleware.AuthRequired())
		{
			authorized.GET("/me", h.GetCurrentUser)
			authorized.POST("/logout", h.Logout)
		}
	}
}

func (h *AuthHandler) Login(c *gin.Context) {
	var req LoginRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	resp, err := h.authService.Login(c.Request.Context(), &req)
	if err != nil {
		utils.Error(c, errors.Unauthorized(err.Error()))
		return
	}

	utils.Success(c, resp)
}

type RegisterRequest struct {
	Username string `json:"username" binding:"required"`
	Email    string `json:"email" binding:"required,email"`
	Password string `json:"password" binding:"required,min=6"`
	Role     Role   `json:"role" binding:"required"`
}

func (h *AuthHandler) Register(c *gin.Context) {
	var req RegisterRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, errors.InvalidParams(err.Error()))
		return
	}

	if req.Role != RoleUser && req.Role != RoleViewer {
		utils.Error(c, errors.InvalidParams("角色不合法"))
		return
	}

	user, err := h.authService.CreateUser(c.Request.Context(), req.Username, req.Email, req.Password, req.Role)
	if err != nil {
		utils.Error(c, errors.Internal(err.Error()))
		return
	}

	utils.SuccessCreated(c, user)
}

func (h *AuthHandler) GetCurrentUser(c *gin.Context) {
	userID := c.GetString("user_id")
	user, err := h.authService.GetUserByID(c.Request.Context(), userID)
	if err != nil {
		utils.Error(c, errors.NotFound("用户"))
		return
	}

	utils.Success(c, user)
}

func (h *AuthHandler) Logout(c *gin.Context) {
	utils.Success(c, gin.H{"message": "登出成功"})
}
