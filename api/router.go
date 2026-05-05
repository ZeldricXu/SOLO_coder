package api

import (
	"socialfeed/api/handler"

	"github.com/gin-gonic/gin"
)

type Router struct {
	postHandler *handler.PostHandler
	feedHandler *handler.FeedHandler
}

func NewRouter(
	postHandler *handler.PostHandler,
	feedHandler *handler.FeedHandler,
) *Router {
	return &Router{
		postHandler: postHandler,
		feedHandler: feedHandler,
	}
}

func (r *Router) SetupRoutes(engine *gin.Engine) {
	api := engine.Group("/api/v1")
	{
		posts := api.Group("/posts")
		{
			posts.POST("/create", r.postHandler.CreatePost)
			posts.GET("/:post_id", r.postHandler.GetPost)
			posts.POST("/interact", r.postHandler.Interact)
		}

		feed := api.Group("/feed")
		{
			feed.GET("/list", r.feedHandler.GetFeedList)
			feed.POST("/:post_id/read", r.feedHandler.MarkAsRead)
			feed.POST("/cache/precompute", r.feedHandler.PrecomputeCache)
			feed.GET("/cache/stats", r.feedHandler.GetCacheStats)
		}
	}
}
