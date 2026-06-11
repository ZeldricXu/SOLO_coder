package auth

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"DF1-56/internal/models"
	"DF1-56/internal/testutil"
)

type onionTestMiddleware struct {
	name     string
	priority int
	handleFn func(ctx *models.GatewayContext, next models.HandlerFunc) error
}

func (m *onionTestMiddleware) Name() string {
	return m.name
}

func (m *onionTestMiddleware) Priority() int {
	return m.priority
}

func (m *onionTestMiddleware) Handle(ctx *models.GatewayContext, next models.HandlerFunc) error {
	if m.handleFn != nil {
		return m.handleFn(ctx, next)
	}
	return next(ctx)
}

func TestCompose_ChainsMiddlewaresCorrectly(t *testing.T) {
	var order []string

	mw1 := func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			order = append(order, "A")
			next.ServeHTTP(w, r)
		})
	}

	mw2 := func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			order = append(order, "B")
			next.ServeHTTP(w, r)
		})
	}

	mw3 := func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			order = append(order, "C")
			next.ServeHTTP(w, r)
		})
	}

	composed := Compose(mw1, mw2, mw3)

	finalHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		order = append(order, "handler")
	})

	handler := composed(finalHandler)

	req := httptest.NewRequest("GET", "/test", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	assert.Equal(t, []string{"A", "B", "C", "handler"}, order)
}

func TestCompose_ExecutionOrder(t *testing.T) {
	var order []string

	mw1 := func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			order = append(order, "mw1-before")
			next.ServeHTTP(w, r)
			order = append(order, "mw1-after")
		})
	}

	mw2 := func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			order = append(order, "mw2-before")
			next.ServeHTTP(w, r)
			order = append(order, "mw2-after")
		})
	}

	mw3 := func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			order = append(order, "mw3-before")
			next.ServeHTTP(w, r)
			order = append(order, "mw3-after")
		})
	}

	composed := Compose(mw1, mw2, mw3)

	finalHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		order = append(order, "handler")
	})

	handler := composed(finalHandler)

	req := httptest.NewRequest("GET", "/test", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	assert.Equal(t, []string{
		"mw1-before", "mw2-before", "mw3-before",
		"handler",
		"mw3-after", "mw2-after", "mw1-after",
	}, order)
}

func TestCompose_WithNoMiddlewares(t *testing.T) {
	composed := Compose()

	handlerCalled := false
	finalHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		handlerCalled = true
	})

	handler := composed(finalHandler)

	req := httptest.NewRequest("GET", "/test", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	assert.True(t, handlerCalled)
}

func TestComposeWithOrder_RuntimeReordering(t *testing.T) {
	var order []string

	mwA := func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			order = append(order, "A")
			next.ServeHTTP(w, r)
		})
	}

	mwB := func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			order = append(order, "B")
			next.ServeHTTP(w, r)
		})
	}

	mwC := func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			order = append(order, "C")
			next.ServeHTTP(w, r)
		})
	}

	t.Run("original order", func(t *testing.T) {
		order = nil
		composed := Compose(mwA, mwB, mwC)
		handler := composed(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
		req := httptest.NewRequest("GET", "/test", nil)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		assert.Equal(t, []string{"A", "B", "C"}, order)
	})

	t.Run("reordered to C A B", func(t *testing.T) {
		order = nil
		middlewares := []OnionMiddleware{mwA, mwB, mwC}
		composed := ComposeWithOrder(middlewares, []int{2, 0, 1})
		handler := composed(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
		req := httptest.NewRequest("GET", "/test", nil)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		assert.Equal(t, []string{"C", "A", "B"}, order)
	})

	t.Run("reversed order", func(t *testing.T) {
		order = nil
		middlewares := []OnionMiddleware{mwA, mwB, mwC}
		composed := ComposeWithOrder(middlewares, []int{2, 1, 0})
		handler := composed(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
		req := httptest.NewRequest("GET", "/test", nil)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		assert.Equal(t, []string{"C", "B", "A"}, order)
	})

	t.Run("mismatched order length falls back to original", func(t *testing.T) {
		order = nil
		middlewares := []OnionMiddleware{mwA, mwB, mwC}
		composed := ComposeWithOrder(middlewares, []int{2, 0})
		handler := composed(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
		req := httptest.NewRequest("GET", "/test", nil)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		assert.Equal(t, []string{"A", "B", "C"}, order)
	})
}

func TestToOnionMiddleware_ConvertsOldToOnion(t *testing.T) {
	t.Run("middleware executes and calls next", func(t *testing.T) {
		var order []string

		oldMw := &onionTestMiddleware{
			name: "test-old",
			handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
				order = append(order, "old-before")
				err := next(ctx)
				order = append(order, "old-after")
				return err
			},
		}

		onionMw := ToOnionMiddleware(oldMw)

		innerHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			order = append(order, "handler")
		})

		handler := onionMw(innerHandler)

		gctx, _ := testutil.NewTestGatewayContext("GET", "/test")
		req := WithGatewayContext(gctx.Request, gctx)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)

		assert.Equal(t, []string{"old-before", "handler", "old-after"}, order)
	})

	t.Run("middleware modifies context before calling next", func(t *testing.T) {
		oldMw := &onionTestMiddleware{
			name: "test-old",
			handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
				ctx.Set("test-key", "test-value")
				return next(ctx)
			},
		}

		onionMw := ToOnionMiddleware(oldMw)

		var capturedValue interface{}
		innerHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			gctx, ok := GatewayContextFromRequest(r)
			if ok {
				capturedValue, _ = gctx.Get("test-key")
			}
		})

		handler := onionMw(innerHandler)

		gctx, _ := testutil.NewTestGatewayContext("GET", "/test")
		req := WithGatewayContext(gctx.Request, gctx)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)

		assert.Equal(t, "test-value", capturedValue)
	})
}

func TestToOnionMiddleware_ErrorStopsChain(t *testing.T) {
	var order []string
	expectedErr := errors.New("auth failed")

	oldMw := &onionTestMiddleware{
		name: "test-old",
		handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
			order = append(order, "old-mw")
			return expectedErr
		},
	}

	onionMw := ToOnionMiddleware(oldMw)

	innerHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		order = append(order, "handler")
	})

	handler := onionMw(innerHandler)

	gctx, _ := testutil.NewTestGatewayContext("GET", "/test")
	req := WithGatewayContext(gctx.Request, gctx)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	assert.Equal(t, []string{"old-mw"}, order)
	assert.NotContains(t, order, "handler")
}

func TestToOnionMiddleware_InComposedChain(t *testing.T) {
	var order []string

	mw1 := &onionTestMiddleware{
		name: "mw1",
		handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
			order = append(order, "mw1-before")
			err := next(ctx)
			order = append(order, "mw1-after")
			return err
		},
	}

	mw2 := &onionTestMiddleware{
		name: "mw2",
		handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
			order = append(order, "mw2-before")
			err := next(ctx)
			order = append(order, "mw2-after")
			return err
		},
	}

	onion1 := ToOnionMiddleware(mw1)
	onion2 := ToOnionMiddleware(mw2)

	composed := Compose(onion1, onion2)

	finalHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		order = append(order, "handler")
	})

	handler := composed(finalHandler)

	gctx, _ := testutil.NewTestGatewayContext("GET", "/test")
	req := WithGatewayContext(gctx.Request, gctx)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	assert.Equal(t, []string{
		"mw1-before", "mw2-before",
		"handler",
		"mw2-after", "mw1-after",
	}, order)
}

func TestToOnionMiddleware_ErrorInComposedChain(t *testing.T) {
	var order []string

	mw1 := &onionTestMiddleware{
		name: "mw1",
		handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
			order = append(order, "mw1-before")
			err := next(ctx)
			order = append(order, "mw1-after")
			return err
		},
	}

	mw2 := &onionTestMiddleware{
		name: "mw2-fail",
		handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
			order = append(order, "mw2-before")
			return errors.New("auth failed")
		},
	}

	mw3 := &onionTestMiddleware{
		name: "mw3",
		handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
			order = append(order, "mw3")
			return next(ctx)
		},
	}

	onion1 := ToOnionMiddleware(mw1)
	onion2 := ToOnionMiddleware(mw2)
	onion3 := ToOnionMiddleware(mw3)

	composed := Compose(onion1, onion2, onion3)

	finalHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		order = append(order, "handler")
	})

	handler := composed(finalHandler)

	gctx, _ := testutil.NewTestGatewayContext("GET", "/test")
	req := WithGatewayContext(gctx.Request, gctx)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	assert.Equal(t, []string{"mw1-before", "mw2-before", "mw1-after"}, order)
	assert.NotContains(t, order, "mw3")
	assert.NotContains(t, order, "handler")
}

func TestFromOnionMiddleware_ConvertsOnionToOld(t *testing.T) {
	t.Run("middleware executes and calls next", func(t *testing.T) {
		var order []string

		onionMw := func(next http.Handler) http.Handler {
			return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				order = append(order, "onion-before")
				next.ServeHTTP(w, r)
				order = append(order, "onion-after")
			})
		}

		oldMw := FromOnionMiddleware("test-onion", onionMw)

		assert.Equal(t, "test-onion", oldMw.Name())

		gctx, _ := testutil.NewTestGatewayContext("GET", "/test")

		nextCalled := false
		next := func(ctx *models.GatewayContext) error {
			nextCalled = true
			order = append(order, "next")
			return nil
		}

		err := oldMw.Handle(gctx, next)
		require.NoError(t, err)
		assert.True(t, nextCalled)
		assert.Equal(t, []string{"onion-before", "next", "onion-after"}, order)
	})

	t.Run("onion middleware can access GatewayContext", func(t *testing.T) {
		onionMw := func(next http.Handler) http.Handler {
			return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				gctx, ok := GatewayContextFromRequest(r)
				if ok {
					gctx.Set("from-onion", "yes")
				}
				next.ServeHTTP(w, r)
			})
		}

		oldMw := FromOnionMiddleware("test-onion", onionMw)

		gctx, _ := testutil.NewTestGatewayContext("GET", "/test")

		next := func(ctx *models.GatewayContext) error {
			return nil
		}

		err := oldMw.Handle(gctx, next)
		require.NoError(t, err)

		val, ok := gctx.Get("from-onion")
		assert.True(t, ok)
		assert.Equal(t, "yes", val)
	})
}

func TestFromOnionMiddleware_UsedInChain(t *testing.T) {
	var order []string

	onionMw := func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			order = append(order, "onion-before")
			next.ServeHTTP(w, r)
			order = append(order, "onion-after")
		})
	}

	oldMw := FromOnionMiddleware("onion-in-chain", onionMw)

	nativeMw := &onionTestMiddleware{
		name: "native",
		handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
			order = append(order, "native-before")
			err := next(ctx)
			order = append(order, "native-after")
			return err
		},
	}

	chain := NewMiddlewareChain(nativeMw, oldMw)

	gctx, _ := testutil.NewTestGatewayContext("GET", "/test")
	err := chain.Handle(gctx)
	require.NoError(t, err)

	assert.Equal(t, []string{
		"native-before", "onion-before",
		"onion-after", "native-after",
	}, order)
}

func TestMiddlewareChain_ComposeOnion(t *testing.T) {
	t.Run("composes chain into onion middleware", func(t *testing.T) {
		var order []string

		mw1 := &onionTestMiddleware{
			name:     "mw1",
			priority: 1,
			handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
				order = append(order, "mw1")
				return next(ctx)
			},
		}

		mw2 := &onionTestMiddleware{
			name:     "mw2",
			priority: 2,
			handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
				order = append(order, "mw2")
				return next(ctx)
			},
		}

		chain := NewMiddlewareChain(mw1, mw2)
		onion := chain.ComposeOnion()

		finalHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			order = append(order, "handler")
		})

		handler := onion(finalHandler)

		gctx, _ := testutil.NewTestGatewayContext("GET", "/test")
		req := WithGatewayContext(gctx.Request, gctx)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)

		assert.Equal(t, []string{"mw1", "mw2", "handler"}, order)
	})

	t.Run("empty chain composes to pass-through", func(t *testing.T) {
		chain := NewMiddlewareChain()
		onion := chain.ComposeOnion()

		handlerCalled := false
		finalHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			handlerCalled = true
		})

		handler := onion(finalHandler)

		gctx, _ := testutil.NewTestGatewayContext("GET", "/test")
		req := WithGatewayContext(gctx.Request, gctx)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)

		assert.True(t, handlerCalled)
	})

	t.Run("respects priority ordering", func(t *testing.T) {
		var order []string

		mw1 := &onionTestMiddleware{
			name:     "low-priority",
			priority: 10,
			handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
				order = append(order, "low")
				return next(ctx)
			},
		}

		mw2 := &onionTestMiddleware{
			name:     "high-priority",
			priority: 1,
			handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
				order = append(order, "high")
				return next(ctx)
			},
		}

		chain := NewMiddlewareChain(mw1, mw2)
		onion := chain.ComposeOnion()

		finalHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			order = append(order, "handler")
		})

		handler := onion(finalHandler)

		gctx, _ := testutil.NewTestGatewayContext("GET", "/test")
		req := WithGatewayContext(gctx.Request, gctx)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)

		assert.Equal(t, []string{"high", "low", "handler"}, order)
	})
}

func TestMiddlewareChain_WithOrder(t *testing.T) {
	t.Run("reorders middlewares", func(t *testing.T) {
		var order []string

		mw1 := &onionTestMiddleware{
			name:     "mw1",
			priority: 1,
			handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
				order = append(order, "mw1")
				return next(ctx)
			},
		}

		mw2 := &onionTestMiddleware{
			name:     "mw2",
			priority: 2,
			handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
				order = append(order, "mw2")
				return next(ctx)
			},
		}

		mw3 := &onionTestMiddleware{
			name:     "mw3",
			priority: 3,
			handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
				order = append(order, "mw3")
				return next(ctx)
			},
		}

		chain := NewMiddlewareChain(mw1, mw2, mw3)
		reordered := chain.WithOrder([]int{2, 0, 1})

		gctx, _ := testutil.NewTestGatewayContext("GET", "/test")
		err := reordered.Handle(gctx)
		require.NoError(t, err)

		assert.Equal(t, []string{"mw3", "mw1", "mw2"}, order)
	})

	t.Run("does not modify original chain", func(t *testing.T) {
		mw1 := &onionTestMiddleware{name: "mw1", priority: 1}
		mw2 := &onionTestMiddleware{name: "mw2", priority: 2}

		chain := NewMiddlewareChain(mw1, mw2)
		_ = chain.WithOrder([]int{1, 0})

		gctx, _ := testutil.NewTestGatewayContext("GET", "/test")
		_ = chain.Handle(gctx)

		assert.Equal(t, "mw1", chain.middlewares[0].Name())
		assert.Equal(t, "mw2", chain.middlewares[1].Name())
	})

	t.Run("mismatched order length returns copy", func(t *testing.T) {
		mw1 := &onionTestMiddleware{name: "mw1", priority: 1}
		mw2 := &onionTestMiddleware{name: "mw2", priority: 2}

		chain := NewMiddlewareChain(mw1, mw2)
		reordered := chain.WithOrder([]int{1})

		assert.Len(t, reordered.middlewares, 2)
		assert.Equal(t, "mw1", reordered.middlewares[0].Name())
		assert.Equal(t, "mw2", reordered.middlewares[1].Name())
	})

	t.Run("WithOrder followed by ComposeOnion", func(t *testing.T) {
		var order []string

		mw1 := &onionTestMiddleware{
			name:     "A",
			priority: 1,
			handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
				order = append(order, "A")
				return next(ctx)
			},
		}

		mw2 := &onionTestMiddleware{
			name:     "B",
			priority: 2,
			handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
				order = append(order, "B")
				return next(ctx)
			},
		}

		chain := NewMiddlewareChain(mw1, mw2)
		reordered := chain.WithOrder([]int{1, 0})

		onion := reordered.ComposeOnion()
		finalHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			order = append(order, "handler")
		})
		handler := onion(finalHandler)

		gctx, _ := testutil.NewTestGatewayContext("GET", "/test")
		req := WithGatewayContext(gctx.Request, gctx)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)

		assert.Equal(t, []string{"B", "A", "handler"}, order)
	})
}

func TestErrorPropagation_ThroughOnionLayers(t *testing.T) {
	t.Run("error in inner old-style middleware stops outer", func(t *testing.T) {
		var order []string

		outerMw := func(next http.Handler) http.Handler {
			return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				order = append(order, "outer-before")
				next.ServeHTTP(w, r)
				order = append(order, "outer-after")
			})
		}

		failingOldMw := &onionTestMiddleware{
			name: "failing",
			handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
				order = append(order, "failing")
				return errors.New("auth error")
			},
		}

		innerMw := func(next http.Handler) http.Handler {
			return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				order = append(order, "inner")
				next.ServeHTTP(w, r)
			})
		}

		composed := Compose(
			outerMw,
			ToOnionMiddleware(failingOldMw),
			innerMw,
		)

		finalHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			order = append(order, "handler")
		})

		handler := composed(finalHandler)

		gctx, _ := testutil.NewTestGatewayContext("GET", "/test")
		req := WithGatewayContext(gctx.Request, gctx)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)

		assert.Equal(t, []string{"outer-before", "failing", "outer-after"}, order)
		assert.NotContains(t, order, "inner")
		assert.NotContains(t, order, "handler")
	})

	t.Run("error in native onion middleware stops chain", func(t *testing.T) {
		var order []string

		mw1 := func(next http.Handler) http.Handler {
			return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				order = append(order, "mw1-before")
				next.ServeHTTP(w, r)
				order = append(order, "mw1-after")
			})
		}

		mw2 := func(next http.Handler) http.Handler {
			return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				order = append(order, "mw2-error")
				w.WriteHeader(http.StatusForbidden)
			})
		}

		mw3 := func(next http.Handler) http.Handler {
			return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				order = append(order, "mw3")
				next.ServeHTTP(w, r)
			})
		}

		composed := Compose(mw1, mw2, mw3)

		finalHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			order = append(order, "handler")
		})

		handler := composed(finalHandler)

		req := httptest.NewRequest("GET", "/test", nil)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)

		assert.Equal(t, []string{"mw1-before", "mw2-error", "mw1-after"}, order)
		assert.NotContains(t, order, "mw3")
		assert.NotContains(t, order, "handler")
		assert.Equal(t, http.StatusForbidden, rec.Code)
	})
}

func TestOnionMiddleware_HandlesNoGatewayContext(t *testing.T) {
	oldMw := &onionTestMiddleware{
		name: "test",
		handleFn: func(ctx *models.GatewayContext, next models.HandlerFunc) error {
			return next(ctx)
		},
	}

	onionMw := ToOnionMiddleware(oldMw)

	handlerCalled := false
	innerHandler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		handlerCalled = true
	})

	handler := onionMw(innerHandler)

	req := httptest.NewRequest("GET", "/test", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	assert.True(t, handlerCalled)
}
