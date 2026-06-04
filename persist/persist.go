//go:build js && wasm

package persist

import (
	"encoding/json"
	"fmt"
	"syscall/js"
)

type ProjectState struct {
	ID              string        `json:"id"`
	Name            string        `json:"name"`
	CreatedAt       int64         `json:"createdAt"`
	UpdatedAt       int64         `json:"updatedAt"`
	DataSource      DataSourceRef `json:"dataSource"`
	Filters         []FilterState `json:"filters"`
	Charts          []ChartState  `json:"charts"`
	PivotTables     []PivotState  `json:"pivotTables"`
	QueryHistory    []string      `json:"queryHistory"`
	SelectedColumns []string      `json:"selectedColumns"`
	SortColumn      string        `json:"sortColumn"`
	SortAscending   bool          `json:"sortAscending"`
	LimitValue      int           `json:"limitValue"`
}

type DataSourceRef struct {
	FileName     string `json:"fileName"`
	FileSize     int64  `json:"fileSize"`
	FileType     string `json:"fileType"`
	FileHash     string `json:"fileHash"`
	LastModified int64  `json:"lastModified"`
}

type FilterState struct {
	Column   string        `json:"column"`
	Operator string        `json:"operator"`
	Value    interface{}   `json:"value"`
	Value2   interface{}   `json:"value2"`
	Values   []interface{} `json:"values"`
	Enabled  bool          `json:"enabled"`
}

type ChartState struct {
	ID         string `json:"id"`
	Type       string `json:"type"`
	XField     string `json:"xField"`
	YField     string `json:"yField"`
	ColorField string `json:"colorField"`
	Title      string `json:"title"`
	Aggregate  string `json:"aggregate"`
	Width      int    `json:"width"`
	Height     int    `json:"height"`
}

type PivotState struct {
	ID         string   `json:"id"`
	RowDims    []string `json:"rowDims"`
	ColDims    []string `json:"colDims"`
	ValueField string   `json:"valueField"`
	AggMethod  string   `json:"aggMethod"`
	Expanded   bool     `json:"expanded"`
}

func SaveProject(state ProjectState) error {
	data, err := json.Marshal(state)
	if err != nil {
		return fmt.Errorf("failed to marshal project state: %w", err)
	}
	saveFn := js.Global().Get("saveToIndexedDB")
	if saveFn.IsUndefined() {
		return fmt.Errorf("saveToIndexedDB is not defined in the global scope")
	}
	saveFn.Invoke(string(data))
	return nil
}

func LoadProject(id string) (*ProjectState, error) {
	loadFn := js.Global().Get("requestLoadFromIndexedDB")
	if loadFn.IsUndefined() {
		return nil, fmt.Errorf("requestLoadFromIndexedDB is not defined in the global scope")
	}
	loadFn.Invoke(id)
	resultVar := js.Global().Get("_lastLoadedProject")
	if resultVar.IsUndefined() {
		return nil, nil
	}
	return DeserializeState(resultVar.String())
}

func ListProjects() ([]ProjectState, error) {
	listFn := js.Global().Get("listProjectsFromDB")
	if listFn.IsUndefined() {
		return nil, fmt.Errorf("listProjectsFromDB is not defined in the global scope")
	}
	result := listFn.Invoke()
	if result.IsUndefined() || result.IsNull() {
		return nil, nil
	}
	var projects []ProjectState
	if err := json.Unmarshal([]byte(result.String()), &projects); err != nil {
		return nil, fmt.Errorf("failed to parse project list: %w", err)
	}
	return projects, nil
}

func DeleteProject(id string) error {
	deleteFn := js.Global().Get("deleteProject")
	if deleteFn.IsUndefined() {
		return fmt.Errorf("deleteProject is not defined in the global scope")
	}
	deleteFn.Invoke(id)
	return nil
}

func SerializeState(state ProjectState) (string, error) {
	data, err := json.Marshal(state)
	if err != nil {
		return "", fmt.Errorf("failed to serialize project state: %w", err)
	}
	return string(data), nil
}

func DeserializeState(data string) (*ProjectState, error) {
	var state ProjectState
	if err := json.Unmarshal([]byte(data), &state); err != nil {
		return nil, fmt.Errorf("failed to deserialize project state: %w", err)
	}
	return &state, nil
}
