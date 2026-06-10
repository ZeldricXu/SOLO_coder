package common

import "errors"

var (
	ErrRoomNotFound        = errors.New("room not found")
	ErrRoomFull            = errors.New("room is full")
	ErrRoomNotJoinable     = errors.New("room is not joinable")
	ErrPlayerNotFound      = errors.New("player not found")
	ErrPlayerAlreadyInRoom = errors.New("player already in room")
	ErrNotHost             = errors.New("operation requires host privilege")
	ErrNotYourTurn         = errors.New("not your turn")
	ErrInvalidAction       = errors.New("invalid action")
	ErrInvalidCards        = errors.New("invalid cards")
	ErrGameNotStarted      = errors.New("game not started")
	ErrGameAlreadyStarted  = errors.New("game already started")
	ErrInvalidInviteCode   = errors.New("invalid invite code")
	ErrMatchTimeout        = errors.New("match timeout")
	ErrObserverNotAllowed  = errors.New("observer not allowed")
)

type GameError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
	Err     error  `json:"-"`
}

func (e *GameError) Error() string {
	if e.Err != nil {
		return e.Message + ": " + e.Err.Error()
	}
	return e.Message
}

func NewGameError(code int, message string, err error) *GameError {
	return &GameError{Code: code, Message: message, Err: err}
}
