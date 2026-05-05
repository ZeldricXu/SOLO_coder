package handler

import (
	"configsync/internal/cli/registry"
)

func RegisterAllHandlers() {
	registry.Register(NewPushHandler())
	registry.Register(NewHistoryHandler())
	registry.Register(NewRollbackHandler())
	registry.Register(NewDiffHandler())
	registry.Register(NewInitHandler())
}
