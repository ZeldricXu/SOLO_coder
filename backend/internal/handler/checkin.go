package handler

import (
	"meeting-system/internal/model"
	"meeting-system/pkg/database"
	"meeting-system/pkg/utils"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

type CheckInHandler struct{}

func NewCheckInHandler() *CheckInHandler {
	return &CheckInHandler{}
}

type CheckInRequest struct {
	Token    string `json:"token" binding:"required"`
	BookingID string `json:"booking_id"`
}

func (h *CheckInHandler) GetQRCode(c *gin.Context) {
	bookingID, err := uuid.Parse(c.Param("bookingId"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid booking ID"})
		return
	}

	var booking model.Booking
	if err := database.DB.First(&booking, bookingID).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "Booking not found"})
		return
	}

	token := utils.GenerateRandomToken(32)
	expiresAt := time.Now().Add(5 * time.Minute)

	qrToken := model.QRCodeToken{
		ID:        uuid.New(),
		BookingID: bookingID,
		Token:     token,
		ExpiresAt: expiresAt,
	}
	database.DB.Create(&qrToken)

	c.JSON(http.StatusOK, gin.H{
		"token":     token,
		"expires_at": expiresAt,
		"booking_id": bookingID,
	})
}

func (h *CheckInHandler) CheckIn(c *gin.Context) {
	userID := getUserID(c)

	var req CheckInRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	var qrToken model.QRCodeToken
	if err := database.DB.Where("token = ?", req.Token).First(&qrToken).Error; err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid QR code token"})
		return
	}

	if time.Now().After(qrToken.ExpiresAt) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "QR code has expired"})
		return
	}

	var existingCheckIn model.CheckIn
	result := database.DB.Where("booking_id = ? AND user_id = ?", qrToken.BookingID, userID).First(&existingCheckIn)
	if result.Error == nil {
		c.JSON(http.StatusOK, gin.H{
			"message":  "Already checked in",
			"check_in": existingCheckIn,
		})
		return
	}

	checkIn := model.CheckIn{
		ID:        uuid.New(),
		BookingID: qrToken.BookingID,
		UserID:    userID,
		CheckInAt: time.Now(),
		QRCode:    req.Token,
		Status:    "checked_in",
	}

	if err := database.DB.Create(&checkIn).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"message":  "Check-in successful",
		"check_in": checkIn,
	})
}

func (h *CheckInHandler) GetCheckInList(c *gin.Context) {
	bookingID, err := uuid.Parse(c.Param("bookingId"))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "Invalid booking ID"})
		return
	}

	var checkIns []model.CheckIn
	if err := database.DB.Where("booking_id = ?", bookingID).Preload("User").Order("check_in_at").Find(&checkIns).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, checkIns)
}
