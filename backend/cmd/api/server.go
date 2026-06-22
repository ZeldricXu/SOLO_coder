package api

import (
	"meeting-system/internal/config"
	"meeting-system/internal/handler"
	"meeting-system/internal/middleware"

	"github.com/gin-contrib/cors"
	"github.com/gin-gonic/gin"
)

type Server struct {
	cfg    *config.Config
	router *gin.Engine
}

func NewServer(cfg *config.Config) *Server {
	router := gin.Default()

	router.Use(cors.New(cors.Config{
		AllowOrigins:     []string{"*"},
		AllowMethods:     []string{"GET", "POST", "PUT", "DELETE", "OPTIONS"},
		AllowHeaders:     []string{"*"},
		ExposeHeaders:    []string{"Content-Length"},
		AllowCredentials: true,
	}))

	server := &Server{
		cfg:    cfg,
		router: router,
	}

	server.setupRoutes()
	return server
}

func (s *Server) setupRoutes() {
	authHandler := handler.NewAuthHandler(s.cfg)
	roomHandler := handler.NewRoomHandler()
	bookingHandler := handler.NewBookingHandler()
	docHandler := handler.NewMeetingDocHandler()
	todoHandler := handler.NewTodoHandler()
	checkInHandler := handler.NewCheckInHandler()
	notificationHandler := handler.NewNotificationHandler()
	statsHandler := handler.NewStatsHandler()
	userHandler := handler.NewUserHandler()

	api := s.router.Group("/api")
	{
		api.POST("/auth/login", authHandler.Login)
		api.GET("/auth/me", middleware.AuthMiddleware(s.cfg), authHandler.Me)

		rooms := api.Group("/rooms")
		rooms.Use(middleware.AuthMiddleware(s.cfg))
		{
			rooms.GET("", roomHandler.List)
			rooms.GET("/:id", roomHandler.Get)
			rooms.POST("", middleware.AdminMiddleware(), roomHandler.Create)
			rooms.PUT("/:id", middleware.AdminMiddleware(), roomHandler.Update)
			rooms.DELETE("/:id", middleware.AdminMiddleware(), roomHandler.Delete)
			rooms.GET("/:id/bookings", roomHandler.GetBookings)
			rooms.GET("/:id/calendar", roomHandler.Calendar)
			rooms.GET("/:id/display", roomHandler.DisplayInfo)
		}

		bookings := api.Group("/bookings")
		bookings.Use(middleware.AuthMiddleware(s.cfg))
		{
			bookings.GET("", bookingHandler.List)
			bookings.GET("/my", bookingHandler.MyBookings)
			bookings.GET("/:id", bookingHandler.Get)
			bookings.POST("", bookingHandler.Create)
			bookings.PUT("/:id", bookingHandler.Update)
			bookings.DELETE("/:id", bookingHandler.Cancel)
			bookings.POST("/check-conflict", bookingHandler.CheckConflict)
			bookings.POST("/:id/approve", bookingHandler.Approve)
			bookings.POST("/:id/reject", bookingHandler.Reject)
		}

		docs := api.Group("/meeting-docs")
		docs.Use(middleware.AuthMiddleware(s.cfg))
		{
			docs.GET("/booking/:bookingId", docHandler.GetByBooking)
			docs.PUT("/:id", docHandler.Update)
			docs.POST("/:id/archive", docHandler.Archive)
			docs.GET("/:id/todos", todoHandler.ListByDoc)
			docs.POST("/:id/todos", todoHandler.Create)
		}

		todos := api.Group("/todos")
		todos.Use(middleware.AuthMiddleware(s.cfg))
		{
			todos.GET("/my", todoHandler.MyTodos)
			todos.PUT("/:id", todoHandler.Update)
			todos.DELETE("/:id", todoHandler.Delete)
		}

		checkin := api.Group("/check-in")
		checkin.Use(middleware.AuthMiddleware(s.cfg))
		{
			checkin.POST("", checkInHandler.CheckIn)
			checkin.GET("/qr/:bookingId", checkInHandler.GetQRCode)
			checkin.GET("/booking/:bookingId", checkInHandler.GetCheckInList)
		}

		notifications := api.Group("/notifications")
		notifications.Use(middleware.AuthMiddleware(s.cfg))
		{
			notifications.GET("", notificationHandler.List)
			notifications.POST("/read/:id", notificationHandler.MarkRead)
			notifications.POST("/read-all", notificationHandler.MarkAllRead)
			notifications.GET("/preferences", notificationHandler.GetPreferences)
			notifications.PUT("/preferences", notificationHandler.UpdatePreferences)
		}

		stats := api.Group("/stats")
		stats.Use(middleware.AuthMiddleware(s.cfg))
		{
			stats.GET("/room-usage", statsHandler.RoomUsage)
			stats.GET("/meeting-hours", statsHandler.MeetingHours)
			stats.GET("/attendance", statsHandler.Attendance)
			stats.GET("/heatmap", statsHandler.Heatmap)
			stats.GET("/efficiency", statsHandler.Efficiency)
		}

		users := api.Group("/users")
		users.Use(middleware.AuthMiddleware(s.cfg))
		{
			users.GET("", userHandler.List)
			users.GET("/:id", userHandler.Get)
		}
	}
}

func (s *Server) Run() error {
	return s.router.Run(":" + s.cfg.ServerPort)
}
