package handler

import (
	"net/http"
	"socialfeed/models"
	"socialfeed/modules/feed"
	"socialfeed/modules/interaction"
	"socialfeed/modules/post"

	"github.com/gin-gonic/gin"
)

type PostHandler struct {
	postService        *post.PostService
	feedService        *feed.FeedService
	interactionService *interaction.InteractionService
}

func NewPostHandler(
	postService *post.PostService,
	feedService *feed.FeedService,
	interactionService *interaction.InteractionService,
) *PostHandler {
	return &PostHandler{
		postService:        postService,
		feedService:        feedService,
		interactionService: interactionService,
	}
}

func (h *PostHandler) CreatePost(c *gin.Context) {
	var req models.CreatePostRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": "Invalid request body",
		})
		return
	}

	userID := c.GetHeader("X-User-ID")
	if userID == "" {
		c.JSON(http.StatusUnauthorized, gin.H{
			"code":  401,
			"error": "User ID not provided",
		})
		return
	}

	resp, err := h.postService.CreatePost(c.Request.Context(), userID, &req)
	if err != nil {
		if err == post.ErrPostContentEmpty {
			c.JSON(http.StatusBadRequest, gin.H{
				"code":  400,
				"error": err.Error(),
			})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": resp,
	})
}

func (h *PostHandler) GetPost(c *gin.Context) {
	postID := c.Param("post_id")
	if postID == "" {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": "Post ID not provided",
		})
		return
	}

	post, err := h.postService.GetPostByID(c.Request.Context(), postID)
	if err != nil {
		if err == post.ErrPostNotFound {
			c.JSON(http.StatusNotFound, gin.H{
				"code":  404,
				"error": "Post not found",
			})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": post,
	})
}

func (h *PostHandler) Interact(c *gin.Context) {
	var req models.InteractRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": "Invalid request body",
		})
		return
	}

	userID := c.GetHeader("X-User-ID")
	if userID == "" {
		c.JSON(http.StatusUnauthorized, gin.H{
			"code":  401,
			"error": "User ID not provided",
		})
		return
	}

	resp, err := h.interactionService.HandleInteraction(c.Request.Context(), userID, &req)
	if err != nil {
		if err == interaction.ErrPostNotPublished || err == interaction.ErrAlreadyLiked {
			c.JSON(http.StatusBadRequest, gin.H{
				"code":  400,
				"error": err.Error(),
			})
			return
		}
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": resp,
	})
}
