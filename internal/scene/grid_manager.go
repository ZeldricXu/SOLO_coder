package scene

import (
	"errors"
	"sync"

	"pixelrealm/pkg/models"
)

var (
	ErrGridOutOfBounds = errors.New("grid coordinate out of bounds")
	ErrPlayerNotFound  = errors.New("player not found in grid")
)

type GridCoord struct {
	X int
	Y int
}

type Grid struct {
	coord    GridCoord
	players  map[models.PlayerID]struct{}
	mu       sync.RWMutex
}

func NewGrid(coord GridCoord) *Grid {
	return &Grid{
		coord:   coord,
		players: make(map[models.PlayerID]struct{}),
	}
}

func (g *Grid) AddPlayer(playerID models.PlayerID) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.players[playerID] = struct{}{}
}

func (g *Grid) RemovePlayer(playerID models.PlayerID) {
	g.mu.Lock()
	defer g.mu.Unlock()
	delete(g.players, playerID)
}

func (g *Grid) GetPlayers() []models.PlayerID {
	g.mu.RLock()
	defer g.mu.RUnlock()
	
	players := make([]models.PlayerID, 0, len(g.players))
	for pid := range g.players {
		players = append(players, pid)
	}
	return players
}

func (g *Grid) GetPlayerCount() int {
	g.mu.RLock()
	defer g.mu.RUnlock()
	return len(g.players)
}

func (g *Grid) HasPlayer(playerID models.PlayerID) bool {
	g.mu.RLock()
	defer g.mu.RUnlock()
	_, exists := g.players[playerID]
	return exists
}

type GridShard struct {
	grids      map[GridCoord]*Grid
	gridSize   float64
	minX       float64
	maxX       float64
	minY       float64
	maxY       float64
	gridCountX int
	gridCountY int
}

func NewGridShard(gridSize float64, minX, maxX, minY, maxY float64) *GridShard {
	width := maxX - minX
	height := maxY - minY
	
	gridCountX := int(width/gridSize) + 1
	gridCountY := int(height/gridSize) + 1
	
	gs := &GridShard{
		grids:      make(map[GridCoord]*Grid),
		gridSize:   gridSize,
		minX:       minX,
		maxX:       maxX,
		minY:       minY,
		maxY:       maxY,
		gridCountX: gridCountX,
		gridCountY: gridCountY,
	}
	
	gs.preallocateGrids()
	
	return gs
}

func (gs *GridShard) preallocateGrids() {
	for x := 0; x < gs.gridCountX; x++ {
		for y := 0; y < gs.gridCountY; y++ {
			coord := GridCoord{X: x, Y: y}
			gs.grids[coord] = NewGrid(coord)
		}
	}
}

func (gs *GridShard) PositionToGrid(x, y float64) (GridCoord, error) {
	if x < gs.minX || x > gs.maxX || y < gs.minY || y > gs.maxY {
		return GridCoord{}, ErrGridOutOfBounds
	}
	
	gridX := int((x - gs.minX) / gs.gridSize)
	gridY := int((y - gs.minY) / gs.gridSize)
	
	if gridX >= gs.gridCountX {
		gridX = gs.gridCountX - 1
	}
	if gridY >= gs.gridCountY {
		gridY = gs.gridCountY - 1
	}
	
	return GridCoord{X: gridX, Y: gridY}, nil
}

func (gs *GridShard) GetGrid(coord GridCoord) (*Grid, bool) {
	grid, exists := gs.grids[coord]
	return grid, exists
}

func (gs *GridShard) GetNineGrids(center GridCoord) []*Grid {
	var grids []*Grid
	
	for dx := -1; dx <= 1; dx++ {
		for dy := -1; dy <= 1; dy++ {
			coord := GridCoord{
				X: center.X + dx,
				Y: center.Y + dy,
			}
			
			if grid, exists := gs.grids[coord]; exists {
				grids = append(grids, grid)
			}
		}
	}
	
	return grids
}

func (gs *GridShard) GetPlayersInNineGrids(center GridCoord) []models.PlayerID {
	grids := gs.GetNineGrids(center)
	
	var allPlayers []models.PlayerID
	
	for _, grid := range grids {
		players := grid.GetPlayers()
		allPlayers = append(allPlayers, players...)
	}
	
	return allPlayers
}

func (gs *GridShard) GetPlayersInNineGridsByPosition(x, y float64) ([]models.PlayerID, error) {
	coord, err := gs.PositionToGrid(x, y)
	if err != nil {
		return nil, err
	}
	
	return gs.GetPlayersInNineGrids(coord), nil
}

func (gs *GridShard) AddPlayerToGrid(playerID models.PlayerID, x, y float64) (GridCoord, error) {
	coord, err := gs.PositionToGrid(x, y)
	if err != nil {
		return GridCoord{}, err
	}
	
	grid, exists := gs.grids[coord]
	if !exists {
		return GridCoord{}, ErrGridOutOfBounds
	}
	
	grid.AddPlayer(playerID)
	return coord, nil
}

func (gs *GridShard) RemovePlayerFromGrid(playerID models.PlayerID, coord GridCoord) error {
	grid, exists := gs.grids[coord]
	if !exists {
		return ErrGridOutOfBounds
	}
	
	grid.RemovePlayer(playerID)
	return nil
}

func (gs *GridShard) MovePlayer(playerID models.PlayerID, oldX, oldY, newX, newY float64) (GridCoord, GridCoord, error) {
	oldCoord, err := gs.PositionToGrid(oldX, oldY)
	if err != nil {
		return GridCoord{}, GridCoord{}, err
	}
	
	newCoord, err := gs.PositionToGrid(newX, newY)
	if err != nil {
		return GridCoord{}, GridCoord{}, err
	}
	
	if oldCoord == newCoord {
		return oldCoord, newCoord, nil
	}
	
	oldGrid, exists := gs.grids[oldCoord]
	if exists {
		oldGrid.RemovePlayer(playerID)
	}
	
	newGrid, exists := gs.grids[newCoord]
	if !exists {
		return oldCoord, newCoord, ErrGridOutOfBounds
	}
	
	newGrid.AddPlayer(playerID)
	
	return oldCoord, newCoord, nil
}

func (gs *GridShard) GetGridSize() float64 {
	return gs.gridSize
}

func (gs *GridShard) GetGridCount() int {
	return len(gs.grids)
}

func (gs *GridShard) GetTotalPlayers() int {
	count := 0
	for _, grid := range gs.grids {
		count += grid.GetPlayerCount()
	}
	return count
}

type AOIStrategy int

const (
	AOINineGrids AOIStrategy = iota
	AOIDistance
	AOIHybrid
)

const (
	DefaultGridSize      = 200.0
	DefaultAOIRadius     = 400.0
	NineGridMultiplier   = 3
)
