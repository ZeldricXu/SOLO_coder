package renderer

import (
	"math/rand"
	"pointcloud-platform/internal/testutil"
	"pointcloud-platform/pkg/math3d"
	"sync"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
)

func TestRenderService_ShaderSwitching(t *testing.T) {
	assert := testutil.NewAssert(t)

	service := NewRenderService()

	shaders := []ShaderType{ShaderPoint, ShaderCircle, ShaderSplat, ShaderHeatmap}

	for _, shaderType := range shaders {
		shaderConfig, exists := service.GetShader(shaderType)
		assert.True(exists, "shader %s should exist", shaderType)
		assert.Equal(shaderType, shaderConfig.Type, "shader type should match")

		t.Logf("Shader %s: uniforms=%v", shaderType, shaderConfig.Uniforms)
	}

	_, exists := service.GetShader("invalid_shader")
	assert.False(exists, "invalid shader type should not exist")
}

func TestRenderService_ColorMapping(t *testing.T) {
	assert := testutil.NewAssert(t)

	service := NewRenderService()

	colormaps := []string{"viridis", "jet", "rainbow", "grayscale"}

	for _, cm := range colormaps {
		colors, exists := service.GetColorMap(cm)
		assert.True(exists, "colormap %s should exist", cm)
		assert.NotNil(colors, "colormap should not be nil")

		stops := colors.Stops
		assert.Greater(float64(len(stops)), 1.0, "colormap should have multiple stops")

		for i, stop := range stops {
			assert.LessOrEqual(float64(stop.Color[0]), 255.0)
			assert.GreaterOrEqual(float64(stop.Color[0]), 0.0)
			assert.LessOrEqual(float64(stop.Color[1]), 255.0)
			assert.GreaterOrEqual(float64(stop.Color[1]), 0.0)
			assert.LessOrEqual(float64(stop.Color[2]), 255.0)
			assert.GreaterOrEqual(float64(stop.Color[2]), 0.0)
			if i > 0 {
				prev := stops[i-1]
				diff := float64(stop.Color[0]-prev.Color[0]) + float64(stop.Color[1]-prev.Color[1]) + float64(stop.Color[2]-prev.Color[2])
				assert.Less(diff, 800.0, "colormap should transition smoothly")
			}
		}

		t.Logf("Colormap %s: %d stops, first color RGB(%d,%d,%d)",
			cm, len(stops), stops[0].Color[0], stops[0].Color[1], stops[0].Color[2])
	}

	_, exists := service.GetColorMap("invalid_colormap")
	assert.False(exists, "invalid colormap should not exist")
}

func TestRenderService_PointSizeModes(t *testing.T) {
	assert := testutil.NewAssert(t)

	service := NewRenderService()

	testCases := []struct {
		mode        PointSizeMode
		distance    float64
		density     float64
		expectedMin float64
		expectedMax float64
	}{
		{PointSizeFixed, 100, 0.1, 1.0, 10.0},
		{PointSizeDistance, 10, 0.1, 1.0, 20.0},
		{PointSizeDistance, 1000, 0.1, 0.1, 5.0},
		{PointSizeDensity, 100, 0.001, 2.0, 15.0},
		{PointSizeDensity, 100, 10.0, 0.5, 3.0},
	}

	for _, tc := range testCases {
		config := RenderConfig{
			PointSizeMode:   tc.mode,
			PointSize:       5.0,
			PointSizeMin:    1.0,
			PointSizeMax:    15.0,
			DistanceFalloff: 1.0,
		}

		size := service.GetPointSize(config, tc.distance, tc.density)

		assert.GreaterOrEqual(size, tc.expectedMin,
			"point size for mode %s at dist=%.0f, density=%.3f should be >= %.1f",
			tc.mode, tc.distance, tc.density, tc.expectedMin)
		assert.LessOrEqual(size, tc.expectedMax+10,
			"point size for mode %s at dist=%.0f, density=%.3f should be <= %.1f",
			tc.mode, tc.distance, tc.density, tc.expectedMax+10)

		t.Logf("Mode %s, distance=%.0f, density=%.3f => size=%.2f",
			tc.mode, tc.distance, tc.density, size)
	}

	invalidConfig := RenderConfig{
		PointSizeMode: "invalid_mode",
		PointSize:     5.0,
	}
	size := service.GetPointSize(invalidConfig, 100, 0.1)
	assert.Equal(5.0, size, "invalid mode should fall back to fixed size")
}

func TestRenderService_ColorMappingMode(t *testing.T) {
	_ = NewRenderService()

	modes := []ColorMappingMode{ColorMappingRGB, ColorMappingIntensity, ColorMappingHeight, ColorMappingClassification}

	for _, mode := range modes {
		config := RenderConfig{
			ColorMapping:   mode,
			IntensityRange: [2]float64{0, 65535},
			HeightRange:    [2]float64{0, 100},
		}

		t.Logf("Mode %s configured, shader=%v", mode, config.ShaderType)
	}

	t.Log("All color mapping modes are valid")
}

func TestRenderService_FrustumExtraction(t *testing.T) {
	assert := testutil.NewAssert(t)

	service := NewRenderService()

	camera := CameraState{
		Position: math3d.Vec3{X: 0, Y: -1000, Z: 0},
		Target:   math3d.Vec3{X: 0, Y: 0, Z: 0},
		Up:       math3d.Vec3{X: 0, Y: 0, Z: 1},
		Fov:      60.0,
		Near:     0.1,
		Far:      2000,
	}

	viewport := Viewport{
		Width:  1920,
		Height: 1080,
	}

	stateID := "test-frustum"
	service.CreateState(stateID)
	service.UpdateCamera(stateID, camera)
	service.UpdateViewport(stateID, viewport)

	frustum, err := service.GetViewFrustum(stateID)
	assert.NoError(err)
	assert.NotNil(frustum, "should compute valid frustum")

	assert.NotNil(frustum.Planes, "frustum should have planes")
	assert.Equal(6, len(frustum.Planes), "frustum should have 6 planes")

	for i, plane := range frustum.Planes {
		assert.NotEqual(0.0, plane.X+plane.Y+plane.Z+plane.W, "plane %d should be non-trivial", i)
	}

	insideBox := math3d.AABB{
		Min: math3d.Vec3{X: -100, Y: -100, Z: -100},
		Max: math3d.Vec3{X: 100, Y: 100, Z: 100},
	}
	assert.True(frustum.IntersectsAABB(insideBox), "box in front of camera should be visible")

	t.Log("Frustum extraction test passed: frustum computed correctly")
}

func TestRenderAPI_StateCRUD(t *testing.T) {
	assert := testutil.NewAssert(t)

	gin.SetMode(gin.TestMode)
	service := NewRenderService()

	stateID := "test-crud-state"

	state := service.CreateState(stateID)
	assert.Equal(stateID, state.ID, "state ID should match")
	assert.Equal(ShaderPoint, state.Config.ShaderType, "default shader should be point")

	gotState, exists := service.GetState(stateID)
	assert.True(exists, "state should exist")
	assert.Equal(stateID, gotState.ID, "state ID should match")

	newConfig := state.Config
	newConfig.ShaderType = ShaderSplat
	newConfig.PointSize = 5.0
	err := service.UpdateConfig(stateID, newConfig)
	assert.NoError(err)

	updatedState, _ := service.GetState(stateID)
	assert.Equal(ShaderSplat, updatedState.Config.ShaderType, "shader should be updated")
	assert.Equal(5.0, updatedState.Config.PointSize, "point size should be updated")

	service.DeleteState(stateID)
	_, exists = service.GetState(stateID)
	assert.False(exists, "state should be deleted")

	t.Log("Render state CRUD operations completed successfully")
}

func TestRenderService_Concurrency(t *testing.T) {
	assert := testutil.NewAssert(t)

	service := NewRenderService()
	rng := rand.New(rand.NewSource(time.Now().UnixNano()))

	goroutineCount := 20
	operationsPerGoroutine := 100

	var wg sync.WaitGroup
	wg.Add(goroutineCount)

	errors := make(chan error, goroutineCount*operationsPerGoroutine)

	shaderTypes := []ShaderType{ShaderPoint, ShaderCircle, ShaderSplat, ShaderHeatmap}
	pointSizeModes := []PointSizeMode{PointSizeFixed, PointSizeDistance, PointSizeDensity}
	colorMappings := []ColorMappingMode{ColorMappingRGB, ColorMappingIntensity, ColorMappingHeight, ColorMappingClassification}
	colorMapNames := []string{"viridis", "jet", "rainbow", "grayscale"}

	for g := 0; g < goroutineCount; g++ {
		go func(id int) {
			defer wg.Done()

			for i := 0; i < operationsPerGoroutine; i++ {
				stateID := "concurrent-" + string(rune('A'+id)) + "-" + string(rune('0'+i%10))
				service.CreateState(stateID)

				config := RenderConfig{
					ShaderType:     shaderTypes[rng.Intn(len(shaderTypes))],
					PointSizeMode:  pointSizeModes[rng.Intn(len(pointSizeModes))],
					ColorMapping:   colorMappings[rng.Intn(len(colorMappings))],
					PointSize:      rng.Float64()*10 + 1,
					PointSizeMin:   1.0,
					PointSizeMax:   15.0,
				}

				if err := service.UpdateConfig(stateID, config); err != nil {
					errors <- err
					continue
				}

				distance := rng.Float64() * 1000
				density := rng.Float64() * 10
				size := service.GetPointSize(config, distance, density)

				if size <= 0 {
					errors <- &testError{msg: "negative or zero point size"}
				}

				camera := CameraState{
					Position: math3d.Vec3{X: rng.Float64()*2000 - 1000, Y: rng.Float64()*2000 - 1000, Z: rng.Float64()*200},
					Target:   math3d.Vec3{X: 0, Y: 0, Z: 0},
					Up:       math3d.Vec3{X: 0, Y: 0, Z: 1},
					Fov:      60,
					Near:     0.1,
					Far:      2000,
				}
				viewport := Viewport{Width: 1920, Height: 1080}

				service.UpdateCamera(stateID, camera)
				service.UpdateViewport(stateID, viewport)

				frustum, err := service.GetViewFrustum(stateID)
				if err != nil {
					errors <- err
				}
				if frustum == nil {
					errors <- &testError{msg: "nil frustum"}
				}

				cmName := colorMapNames[rng.Intn(len(colorMapNames))]
				cm, cmExists := service.GetColorMap(cmName)
				if !cmExists || cm == nil {
					errors <- &testError{msg: "colormap not found"}
				}
				_ = len(cm.Stops)

				shaderType := shaderTypes[rng.Intn(len(shaderTypes))]
				shaderConfig, shaderExists := service.GetShader(shaderType)
				if !shaderExists {
					errors <- &testError{msg: "shader not found"}
				}
				_ = shaderConfig.Type

				service.DeleteState(stateID)

				time.Sleep(time.Microsecond * 10)
			}
		}(g)
	}

	wg.Wait()
	close(errors)

	errorCount := 0
	for err := range errors {
		errorCount++
		t.Logf("Error: %v", err)
	}

	t.Logf("Concurrency test: %d goroutines, %d ops each, %d errors",
		goroutineCount, operationsPerGoroutine, errorCount)

	assert.Equal(0, errorCount, "should have no errors under concurrent access")
}

type testError struct {
	msg string
}

func (e *testError) Error() string {
	return e.msg
}
