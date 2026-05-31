package di

import (
	"context"
	"fmt"
	"reflect"
	"sync"
)

type Container struct {
	mu          sync.RWMutex
	services    map[string]interface{}
	factories   map[string]func() (interface{}, error)
	singletons  map[string]interface{}
}

func NewContainer() *Container {
	return &Container{
		services:   make(map[string]interface{}),
		factories:  make(map[string]func() (interface{}, error)),
		singletons: make(map[string]interface{}),
	}
}

func (c *Container) Register(name string, instance interface{}) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.services[name] = instance
}

func (c *Container) RegisterFactory(name string, factory func() (interface{}, error)) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.factories[name] = factory
}

func (c *Container) RegisterSingleton(name string, factory func() (interface{}, error)) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.factories[name] = func() (interface{}, error) {
		if instance, exists := c.singletons[name]; exists {
			return instance, nil
		}
		instance, err := factory()
		if err != nil {
			return nil, err
		}
		c.singletons[name] = instance
		return instance, nil
	}
}

func (c *Container) Get(name string) (interface{}, error) {
	c.mu.RLock()
	if instance, exists := c.services[name]; exists {
		c.mu.RUnlock()
		return instance, nil
	}
	factory, hasFactory := c.factories[name]
	c.mu.RUnlock()

	if hasFactory {
		return factory()
	}

	return nil, fmt.Errorf("service %s not found in container", name)
}

func (c *Container) MustGet(name string) interface{} {
	instance, err := c.Get(name)
	if err != nil {
		panic(err)
	}
	return instance
}

func (c *Container) GetByType(target interface{}) error {
	targetType := reflect.TypeOf(target)
	if targetType.Kind() != reflect.Ptr || targetType.Elem().Kind() != reflect.Interface {
		return fmt.Errorf("target must be a pointer to an interface")
	}

	ifaceType := targetType.Elem()

	c.mu.RLock()
	defer c.mu.RUnlock()

	for _, instance := range c.services {
		if reflect.TypeOf(instance).Implements(ifaceType) {
			reflect.ValueOf(target).Elem().Set(reflect.ValueOf(instance))
			return nil
		}
	}

	for name, factory := range c.factories {
		instance, err := factory()
		if err != nil {
			continue
		}
		if reflect.TypeOf(instance).Implements(ifaceType) {
			reflect.ValueOf(target).Elem().Set(reflect.ValueOf(instance))
			c.services[name] = instance
			return nil
		}
	}

	return fmt.Errorf("no service found implementing %v", ifaceType)
}

func (c *Container) Inject(target interface{}) error {
	targetValue := reflect.ValueOf(target)
	if targetValue.Kind() != reflect.Ptr || targetValue.Elem().Kind() != reflect.Struct {
		return fmt.Errorf("target must be a pointer to a struct")
	}

	targetElem := targetValue.Elem()
	targetType := targetElem.Type()

	for i := 0; i < targetType.NumField(); i++ {
		field := targetType.Field(i)
		fieldValue := targetElem.Field(i)

		if !fieldValue.CanSet() {
			continue
		}

		tag := field.Tag.Get("di")
		if tag == "" && field.Type.Kind() != reflect.Interface {
			continue
		}

		var instance interface{}
		var err error

		if tag != "" {
			instance, err = c.Get(tag)
		} else if field.Type.Kind() == reflect.Interface {
			iface := reflect.New(field.Type).Interface()
			if err := c.GetByType(iface); err == nil {
				instance = reflect.ValueOf(iface).Elem().Interface()
			}
		}

		if instance != nil {
			fieldValue.Set(reflect.ValueOf(instance))
		} else if err != nil && tag != "" {
			return fmt.Errorf("failed to inject field %s: %w", field.Name, err)
		}
	}

	return nil
}

func (c *Container) Cleanup(ctx context.Context) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	for name, instance := range c.services {
		if closer, ok := instance.(interface{ Close() error }); ok {
			if err := closer.Close(); err != nil {
				return fmt.Errorf("failed to close service %s: %w", name, err)
			}
		}
		if stopper, ok := instance.(interface{ Stop(context.Context) error }); ok {
			if err := stopper.Stop(ctx); err != nil {
				return fmt.Errorf("failed to stop service %s: %w", name, err)
			}
		}
	}

	return nil
}
