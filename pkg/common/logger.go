package common

import (
	"log"
	"os"
)

type LogLevel int

const (
	LevelDebug LogLevel = iota
	LevelInfo
	LevelWarn
	LevelError
)

var currentLevel = LevelInfo

func SetLogLevel(level LogLevel) {
	currentLevel = level
}

var (
	loggerInfo  = log.New(os.Stdout, "[INFO] ", log.LstdFlags|log.Lshortfile)
	loggerWarn  = log.New(os.Stdout, "[WARN] ", log.LstdFlags|log.Lshortfile)
	loggerError = log.New(os.Stderr, "[ERROR] ", log.LstdFlags|log.Lshortfile)
	loggerDebug = log.New(os.Stdout, "[DEBUG] ", log.LstdFlags|log.Lshortfile)
)

func LogDebug(format string, v ...interface{}) {
	if currentLevel <= LevelDebug {
		loggerDebug.Printf(format, v...)
	}
}

func LogInfo(format string, v ...interface{}) {
	if currentLevel <= LevelInfo {
		loggerInfo.Printf(format, v...)
	}
}

func LogWarn(format string, v ...interface{}) {
	if currentLevel <= LevelWarn {
		loggerWarn.Printf(format, v...)
	}
}

func LogError(format string, v ...interface{}) {
	if currentLevel <= LevelError {
		loggerError.Printf(format, v...)
	}
}
