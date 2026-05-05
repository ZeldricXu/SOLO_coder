package handler

import (
	"net/http"
	"socialfeed/models"
	"socialfeed/modules/feed"
	"strconv"

	"github.com/gin-gonic/gin"
)

type FeedHandler struct {
	feedService      *feed.FeedService
	feedCacheService *feed.FeedCacheService
}

func NewFeedHandler(feedService *feed.FeedService, feedCacheService *feed.FeedCacheService) *FeedHandler {
	return &FeedHandler{
		feedService:      feedService,
		feedCacheService: feedCacheService,
	}
}

func (h *FeedHandler) GetFeedList(c *gin.Context) {
	userID := c.Query("user_id")
	if userID == "" {
		userID = c.GetHeader("X-User-ID")
	}
	if userID == "" {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": "User ID not provided",
		})
		return
	}

	pageStr := c.Query("page")
	page, _ := strconv.ParseInt(pageStr, 10, 64)
	if page < 1 {
		page = 1
	}

	pageSizeStr := c.Query("page_size")
	pageSize, _ := strconv.ParseInt(pageSizeStr, 10, 64)
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}

	useCache := c.Query("use_cache") != "false"

	if useCache && h.feedCacheService != nil {
		cachedItems, totalCount, err := h.feedCacheService.GetCachedFeed(
			c.Request.Context(),
			userID,
			int(page),
			int(pageSize),
		)

		if err == nil && cachedItems != nil && len(cachedItems) > 0 {
			resp := feed.ConvertCachedFeedToFeedResponse(
				cachedItems,
				totalCount,
				int(page),
				int(pageSize),
			)
			c.JSON(http.StatusOK, gin.H{
				"code":       200,
				"data":       resp,
				"cache_hit":  true,
			})
			return
		}
	}

	req := &models.FeedListRequest{
		UserID:   userID,
		Page:     page,
		PageSize: pageSize,
	}

	resp, err := h.feedService.GetFeedList(c.Request.Context(), req)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":       200,
		"data":       resp,
		"cache_hit":  false,
	})
}

func (h *FeedHandler) MarkAsRead(c *gin.Context) {
	userID := c.GetHeader("X-User-ID")
	if userID == "" {
		c.JSON(http.StatusUnauthorized, gin.H{
			"code":  401,
			"error": "User ID not provided",
		})
		return
	}

	postID := c.Param("post_id")
	if postID == "" {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": "Post ID not provided",
		})
		return
	}

	err := h.feedService.MarkAsRead(c.Request.Context(), userID, postID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	if h.feedCacheService != nil {
		_ = h.feedCacheService.InvalidateFeedCache(c.Request.Context(), userID)
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{"success": true},
	})
}

func (h *FeedHandler) PrecomputeCache(c *gin.Context) {
	userID := c.Query("user_id")
	if userID == "" {
		userID = c.GetHeader("X-User-ID")
	}
	if userID == "" {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": "User ID not provided",
		})
		return
	}

	if h.feedCacheService == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{
			"code":  503,
			"error": "Cache service not available",
		})
		return
	}

	err := h.feedCacheService.PrecomputeAndCacheFeed(c.Request.Context(), userID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":  500,
			"error": err.Error(),
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code":    200,
		"message": "Cache precomputed successfully",
	})
}

func (h *FeedHandler) GetCacheStats(c *gin.Context) {
	userID := c.Query("user_id")
	if userID == "" {
		userID = c.GetHeader("X-User-ID")
	}
	if userID == "" {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":  400,
			"error": "User ID not provided",
		})
		return
	}

	if h.feedCacheService == nil {
		c.JSON(http.StatusServiceUnavailable, gin.H{
			"code":  503,
			"error": "Cache service not available",
		})
		return
	}

	exists, itemCount, ttl := h.feedCacheService.GetFeedCacheStats(c.Request.Context(), userID)

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"cache_exists":   exists,
			"item_count":     itemCount,
			"ttl_seconds":    ttl.Seconds(),
			"ttl_formatted":  ttl.String(),
		},
	})
}
