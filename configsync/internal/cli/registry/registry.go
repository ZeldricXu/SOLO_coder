package registry

import (
	"sync"

	"configsync/internal/cli"
)

type CommandRegistry struct {
	handlers map[string]cli.CommandHandler
	mu       sync.RWMutex
}

var instance *CommandRegistry
var once sync.Once

func GetRegistry() *CommandRegistry {
	once.Do(func() {
		instance = &CommandRegistry{
			handlers: make(map[string]cli.CommandHandler),
		}
	})
	return instance
}

func (r *CommandRegistry) Register(handler cli.CommandHandler) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.handlers[handler.Name()] = handler
}

func (r *CommandRegistry) Get(name string) (cli.CommandHandler, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	handler, exists := r.handlers[name]
	return handler, exists
}

func (r *CommandRegistry) List() []cli.CommandHandler {
	r.mu.RLock()
	defer r.mu.RUnlock()
	handlers := make([]cli.CommandHandler, 0, len(r.handlers))
	for _, h := range r.handlers {
		handlers = append(handlers, h)
	}
	return handlers
}

func Register(handler cli.CommandHandler) {
	GetRegistry().Register(handler)
}
