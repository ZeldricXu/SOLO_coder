package renderer

import (
	"fmt"
	"math"
	"pointcloud-platform/pkg/math3d"
	"sync"
)

type PointSizeMode string
type ColorMappingMode string
type ShaderType string

const (
	PointSizeFixed     PointSizeMode = "fixed"
	PointSizeDistance  PointSizeMode = "distance"
	PointSizeDensity   PointSizeMode = "density"

	ColorMappingRGB     ColorMappingMode = "rgb"
	ColorMappingIntensity ColorMappingMode = "intensity"
	ColorMappingHeight  ColorMappingMode = "height"
	ColorMappingClassification ColorMappingMode = "classification"

	ShaderPoint    ShaderType = "point"
	ShaderCircle   ShaderType = "circle"
	ShaderSplat    ShaderType = "splat"
	ShaderHeatmap  ShaderType = "heatmap"
)

type ShaderConfig struct {
	Type           ShaderType        `json:"type"`
	VertexSource   string            `json:"vertex_source,omitempty"`
	FragmentSource string           `json:"fragment_source,omitempty"`
	Uniforms       map[string]interface{} `json:"uniforms,omitempty"`
}

type ColorMap struct {
	Name    string            `json:"name"`
	Stops   []ColorStop       `json:"stops"`
	Min     float64           `json:"min"`
	Max     float64           `json:"max"`
}

type ColorStop struct {
	Position float64 `json:"position"`
	Color    [4]uint8 `json:"color"`
}

type RenderConfig struct {
	PointSize        float64           `json:"point_size"`
	PointSizeMode    PointSizeMode     `json:"point_size_mode"`
	PointSizeMin     float64           `json:"point_size_min"`
	PointSizeMax     float64           `json:"point_size_max"`
	DistanceFalloff  float64           `json:"distance_falloff"`

	ColorMapping     ColorMappingMode  `json:"color_mapping"`
	ColorMap         *ColorMap         `json:"color_map,omitempty"`
	IntensityRange   [2]float64        `json:"intensity_range"`
	HeightRange      [2]float64        `json:"height_range"`

	ShaderType       ShaderType        `json:"shader_type"`
	CustomShader     *ShaderConfig     `json:"custom_shader,omitempty"`

	Opacity          float64           `json:"opacity"`
	Brightness       float64           `json:"brightness"`
	Contrast         float64           `json:"contrast"`
	Gamma            float64           `json:"gamma"`

	BackfaceCulling  bool              `json:"backface_culling"`
	DepthTest        bool              `json:"depth_test"`
	Blending         bool              `json:"blending"`
}

type CameraState struct {
	Position     math3d.Vec3   `json:"position"`
	Target       math3d.Vec3   `json:"target"`
	Up           math3d.Vec3   `json:"up"`
	Fov          float64       `json:"fov"`
	Near         float64       `json:"near"`
	Far          float64       `json:"far"`
	Aspect       float64       `json:"aspect"`
}

type Viewport struct {
	X      int `json:"x"`
	Y      int `json:"y"`
	Width  int `json:"width"`
	Height int `json:"height"`
}

type RendererState struct {
	ID         string       `json:"id"`
	Config     RenderConfig `json:"config"`
	Camera     CameraState `json:"camera"`
	Viewport   Viewport    `json:"viewport"`
	ActiveTiles []string    `json:"active_tiles"`
	Version    int64       `json:"version"`
}

type RenderService struct {
	states     map[string]*RendererState
	statesMu   sync.RWMutex
	shaders    map[ShaderType]ShaderConfig
	colorMaps  map[string]*ColorMap
}

func NewRenderService() *RenderService {
	rs := &RenderService{
		states:    make(map[string]*RendererState),
		shaders:   make(map[ShaderType]ShaderConfig),
		colorMaps: make(map[string]*ColorMap),
	}

	rs.initDefaultShaders()
	rs.initDefaultColorMaps()

	return rs
}

func (s *RenderService) initDefaultShaders() {
	s.shaders[ShaderPoint] = ShaderConfig{
		Type: ShaderPoint,
		Uniforms: map[string]interface{}{
			"pointSize": 2.0,
			"opacity":   1.0,
		},
	}

	s.shaders[ShaderCircle] = ShaderConfig{
		Type: ShaderCircle,
		Uniforms: map[string]interface{}{
			"pointSize": 3.0,
			"edge":      0.1,
		},
	}

	s.shaders[ShaderSplat] = ShaderConfig{
		Type: ShaderSplat,
		Uniforms: map[string]interface{}{
			"splatSize": 5.0,
			"falloff":   0.5,
		},
	}

	s.shaders[ShaderHeatmap] = ShaderConfig{
		Type: ShaderHeatmap,
		Uniforms: map[string]interface{}{
			"pointSize": 4.0,
			"heatScale": 1.0,
		},
	}
}

func (s *RenderService) initDefaultColorMaps() {
	s.colorMaps["viridis"] = &ColorMap{
		Name: "viridis",
		Min:  0,
		Max:  1,
		Stops: []ColorStop{
			{0.0, [4]uint8{68, 1, 84, 255}},
			{0.25, [4]uint8{59, 82, 139, 255}},
			{0.5, [4]uint8{33, 145, 140, 255}},
			{0.75, [4]uint8{94, 201, 98, 255}},
			{1.0, [4]uint8{253, 231, 37, 255}},
		},
	}

	s.colorMaps["jet"] = &ColorMap{
		Name: "jet",
		Min:  0,
		Max:  1,
		Stops: []ColorStop{
			{0.0, [4]uint8{0, 0, 128, 255}},
			{0.25, [4]uint8{0, 0, 255, 255}},
			{0.5, [4]uint8{0, 255, 255, 255}},
			{0.75, [4]uint8{255, 255, 0, 255}},
			{1.0, [4]uint8{255, 0, 0, 255}},
		},
	}

	s.colorMaps["rainbow"] = &ColorMap{
		Name: "rainbow",
		Min:  0,
		Max:  1,
		Stops: []ColorStop{
			{0.0, [4]uint8{148, 0, 211, 255}},
			{0.17, [4]uint8{75, 0, 130, 255}},
			{0.33, [4]uint8{0, 0, 255, 255}},
			{0.5, [4]uint8{0, 255, 0, 255}},
			{0.67, [4]uint8{255, 255, 0, 255}},
			{0.83, [4]uint8{255, 127, 0, 255}},
			{1.0, [4]uint8{255, 0, 0, 255}},
		},
	}

	s.colorMaps["grayscale"] = &ColorMap{
		Name: "grayscale",
		Min:  0,
		Max:  1,
		Stops: []ColorStop{
			{0.0, [4]uint8{0, 0, 0, 255}},
			{1.0, [4]uint8{255, 255, 255, 255}},
		},
	}
}

func (s *RenderService) DefaultConfig() RenderConfig {
	return RenderConfig{
		PointSize:        2.0,
		PointSizeMode:    PointSizeFixed,
		PointSizeMin:     1.0,
		PointSizeMax:     10.0,
		DistanceFalloff:  1.0,

		ColorMapping:     ColorMappingRGB,
		ColorMap:         s.colorMaps["viridis"],
		IntensityRange:   [2]float64{0, 65535},
		HeightRange:      [2]float64{0, 100},

		ShaderType:       ShaderPoint,

		Opacity:          1.0,
		Brightness:       1.0,
		Contrast:         1.0,
		Gamma:            1.0,

		BackfaceCulling:  false,
		DepthTest:        true,
		Blending:         false,
	}
}

func (s *RenderService) CreateState(id string) *RendererState {
	s.statesMu.Lock()
	defer s.statesMu.Unlock()

	state := &RendererState{
		ID:      id,
		Config:  s.DefaultConfig(),
		Camera: CameraState{
			Up:  math3d.Vec3{Y: 1},
			Fov: 60,
			Near: 0.1,
			Far:  10000,
		},
		Viewport: Viewport{
			Width:  800,
			Height: 600,
		},
		Version: 1,
	}

	s.states[id] = state
	return state
}

func (s *RenderService) GetState(id string) (*RendererState, bool) {
	s.statesMu.RLock()
	defer s.statesMu.RUnlock()

	state, exists := s.states[id]
	return state, exists
}

func (s *RenderService) UpdateConfig(id string, config RenderConfig) error {
	s.statesMu.Lock()
	defer s.statesMu.Unlock()

	state, exists := s.states[id]
	if !exists {
		return fmt.Errorf("renderer state %s not found", id)
	}

	state.Config = config
	state.Version++
	return nil
}

func (s *RenderService) UpdateCamera(id string, camera CameraState) error {
	s.statesMu.Lock()
	defer s.statesMu.Unlock()

	state, exists := s.states[id]
	if !exists {
		return fmt.Errorf("renderer state %s not found", id)
	}

	state.Camera = camera
	state.Version++
	return nil
}

func (s *RenderService) UpdateViewport(id string, viewport Viewport) error {
	s.statesMu.Lock()
	defer s.statesMu.Unlock()

	state, exists := s.states[id]
	if !exists {
		return fmt.Errorf("renderer state %s not found", id)
	}

	state.Viewport = viewport
	state.Camera.Aspect = float64(viewport.Width) / float64(viewport.Height)
	state.Version++
	return nil
}

func (s *RenderService) SetActiveTiles(id string, tiles []string) error {
	s.statesMu.Lock()
	defer s.statesMu.Unlock()

	state, exists := s.states[id]
	if !exists {
		return fmt.Errorf("renderer state %s not found", id)
	}

	state.ActiveTiles = tiles
	state.Version++
	return nil
}

func (s *RenderService) GetShader(shaderType ShaderType) (ShaderConfig, bool) {
	shader, exists := s.shaders[shaderType]
	return shader, exists
}

func (s *RenderService) RegisterShader(config ShaderConfig) {
	s.statesMu.Lock()
	defer s.statesMu.Unlock()
	s.shaders[config.Type] = config
}

func (s *RenderService) GetColorMap(name string) (*ColorMap, bool) {
	cm, exists := s.colorMaps[name]
	return cm, exists
}

func (s *RenderService) RegisterColorMap(cm *ColorMap) {
	s.statesMu.Lock()
	defer s.statesMu.Unlock()
	s.colorMaps[cm.Name] = cm
}

func (s *RenderService) ListColorMaps() []string {
	s.statesMu.RLock()
	defer s.statesMu.RUnlock()

	names := make([]string, 0, len(s.colorMaps))
	for name := range s.colorMaps {
		names = append(names, name)
	}
	return names
}

func (s *RenderService) GetViewFrustum(id string) (*math3d.Frustum, error) {
	s.statesMu.RLock()
	defer s.statesMu.RUnlock()

	state, exists := s.states[id]
	if !exists {
		return nil, fmt.Errorf("renderer state %s not found", id)
	}

	return s.computeFrustum(state.Camera, state.Viewport), nil
}

func (s *RenderService) computeFrustum(camera CameraState, viewport Viewport) *math3d.Frustum {
	aspect := float64(viewport.Width) / float64(viewport.Height)
	proj := math3d.Mat4Perspective(camera.Fov, aspect, camera.Near, camera.Far)
	view := math3d.Mat4LookAt(camera.Position, camera.Target, camera.Up)
	vp := proj.Mul(view)

	var frustum math3d.Frustum

	frustum.Planes[0] = math3d.Vec4{
		X: vp[3] + vp[0],
		Y: vp[7] + vp[4],
		Z: vp[11] + vp[8],
		W: vp[15] + vp[12],
	}

	frustum.Planes[1] = math3d.Vec4{
		X: vp[3] - vp[0],
		Y: vp[7] - vp[4],
		Z: vp[11] - vp[8],
		W: vp[15] - vp[12],
	}

	frustum.Planes[2] = math3d.Vec4{
		X: vp[3] + vp[1],
		Y: vp[7] + vp[5],
		Z: vp[11] + vp[9],
		W: vp[15] + vp[13],
	}

	frustum.Planes[3] = math3d.Vec4{
		X: vp[3] - vp[1],
		Y: vp[7] - vp[5],
		Z: vp[11] - vp[9],
		W: vp[15] - vp[13],
	}

	frustum.Planes[4] = math3d.Vec4{
		X: vp[3] + vp[2],
		Y: vp[7] + vp[6],
		Z: vp[11] + vp[10],
		W: vp[15] + vp[14],
	}

	frustum.Planes[5] = math3d.Vec4{
		X: vp[3] - vp[2],
		Y: vp[7] - vp[6],
		Z: vp[11] - vp[10],
		W: vp[15] - vp[14],
	}

	for i := 0; i < 6; i++ {
		normal := math3d.Vec3{X: frustum.Planes[i].X, Y: frustum.Planes[i].Y, Z: frustum.Planes[i].Z}
		length := normal.Length()
		if length > 0 {
			frustum.Planes[i].X /= length
			frustum.Planes[i].Y /= length
			frustum.Planes[i].Z /= length
			frustum.Planes[i].W /= length
		}
	}

	return &frustum
}

func (s *RenderService) DeleteState(id string) {
	s.statesMu.Lock()
	defer s.statesMu.Unlock()
	delete(s.states, id)
}

func (s *RenderService) GetPointSize(config RenderConfig, distance float64, density float64) float64 {
	switch config.PointSizeMode {
	case PointSizeDistance:
		size := config.PointSize * config.DistanceFalloff / (distance + 0.001)
		if size < config.PointSizeMin {
			size = config.PointSizeMin
		}
		if size > config.PointSizeMax {
			size = config.PointSizeMax
		}
		return size
	case PointSizeDensity:
		size := config.PointSize / math.Sqrt(density+0.001)
		if size < config.PointSizeMin {
			size = config.PointSizeMin
		}
		if size > config.PointSizeMax {
			size = config.PointSizeMax
		}
		return size
	default:
		return config.PointSize
	}
}
