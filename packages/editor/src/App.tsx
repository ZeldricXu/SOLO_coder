import { useState, useCallback } from 'react';
import { HexGrid } from '@tactics/core';
import type { CombatUnit } from '@tactics/core';
import { HexMapCanvas } from './components/HexMapCanvas';
import { Toolbar } from './components/Toolbar';
import { PropertyPanel } from './components/PropertyPanel';
import { UnitPalette } from './components/UnitPalette';
import { useEditorState } from './hooks/useEditorState';

const GRID_CONFIG = {
  width: 15,
  height: 15,
  orientation: 'pointy' as const,
  defaultTerrain: 'plain' as const,
  tileSize: 40,
};

function App() {
  const [grid] = useState(() => new HexGrid(GRID_CONFIG));
  const [units, setUnits] = useState<Map<string, CombatUnit>>(new Map());
  const [gridVersion, setGridVersion] = useState(0);
  const [activeRightPanel, setActiveRightPanel] = useState<'properties' | 'units'>('properties');

  const editor = useEditorState(grid);

  const handleGridChange = useCallback(() => {
    setGridVersion(v => v + 1);
  }, []);

  return (
    <div style={styles.app}>
      <div style={styles.header}>
        <div style={styles.headerLeft}>
          <h1 style={styles.logo}>🗺️ 战术地图编辑器</h1>
        </div>
        <div style={styles.headerCenter}>
          <div style={styles.zoomControls}>
            <button
              style={styles.iconButton}
              onClick={() => editor.setZoom(editor.zoom * 0.8)}
              title="缩小"
            >
              －
            </button>
            <span style={styles.zoomLevel}>{Math.round(editor.zoom * 100)}%</span>
            <button
              style={styles.iconButton}
              onClick={() => editor.setZoom(editor.zoom * 1.25)}
              title="放大"
            >
              ＋
            </button>
            <button
              style={styles.iconButton}
              onClick={() => {
                editor.setZoom(1);
                editor.setPan(400, 300);
              }}
              title="重置视图"
            >
              ⌂
            </button>
          </div>
        </div>
        <div style={styles.headerRight}>
          <span style={styles.statusText}>
            缩放: {Math.round(editor.zoom * 100)}% | 工具: {getToolLabel(editor.currentTool)}
          </span>
        </div>
      </div>

      <div style={styles.main}>
        <div style={styles.leftPanel}>
          <Toolbar
            currentTool={editor.currentTool}
            currentTerrain={editor.currentTerrain}
            brushSize={editor.brushSize}
            onToolChange={editor.setCurrentTool}
            onTerrainChange={editor.setCurrentTerrain}
            onBrushSizeChange={editor.setBrushSize}
            onGenerateMap={() => {
              setActiveRightPanel('properties');
            }}
            canUndo={editor.canUndo}
            canRedo={editor.canRedo}
            onUndo={editor.undo}
            onRedo={editor.redo}
          />
        </div>

        <div style={styles.canvasWrapper}>
          <HexMapCanvas
            key={gridVersion}
            grid={grid}
            units={units}
            currentTool={editor.currentTool}
            currentTerrain={editor.currentTerrain}
            selectedTile={editor.selectedTile}
            selectedUnitTemplate={editor.selectedUnitTemplate}
            brushSize={editor.brushSize}
            zoom={editor.zoom}
            panX={editor.panX}
            panY={editor.panY}
            setZoom={editor.setZoom}
            setPan={editor.setPan}
            setSelectedTile={editor.setSelectedTile}
            saveSnapshot={editor.saveSnapshot}
            onUnitsChange={setUnits}
            onGridChange={handleGridChange}
          />
        </div>

        <div style={styles.rightPanel}>
          <div style={styles.rightPanelTabs}>
            <button
              style={{
                ...styles.rightPanelTab,
                ...(activeRightPanel === 'properties' ? styles.rightPanelTabActive : {}),
              }}
              onClick={() => setActiveRightPanel('properties')}
            >
              📋 属性
            </button>
            <button
              style={{
                ...styles.rightPanelTab,
                ...(activeRightPanel === 'units' ? styles.rightPanelTabActive : {}),
              }}
              onClick={() => setActiveRightPanel('units')}
            >
              ⚔️ 单位
            </button>
          </div>
          <div style={styles.rightPanelContent}>
            {activeRightPanel === 'properties' ? (
              <PropertyPanel
                grid={grid}
                units={units}
                selectedTile={editor.selectedTile}
                onGridChange={handleGridChange}
                onUnitsChange={setUnits}
                saveSnapshot={editor.saveSnapshot}
              />
            ) : (
              <UnitPalette
                selectedUnitTemplate={editor.selectedUnitTemplate}
                onSelectTemplate={editor.setSelectedUnitTemplate}
              />
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function getToolLabel(tool: string): string {
  const labels: Record<string, string> = {
    select: '选择',
    brush: '画笔',
    eraser: '橡皮',
    placeUnit: '放置单位',
    placeObject: '放置物件',
    generateMap: '生成器',
  };
  return labels[tool] || tool;
}

const styles: Record<string, React.CSSProperties> = {
  app: {
    width: '100%',
    height: '100%',
    display: 'flex',
    flexDirection: 'column',
    backgroundColor: '#0f172a',
  },
  header: {
    height: '52px',
    backgroundColor: '#1e293b',
    borderBottom: '1px solid #334155',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: '0 20px',
    flexShrink: 0,
  },
  headerLeft: {
    display: 'flex',
    alignItems: 'center',
  },
  logo: {
    fontSize: '16px',
    fontWeight: 700,
    color: '#f1f5f9',
    margin: 0,
  },
  headerCenter: {
    display: 'flex',
    alignItems: 'center',
  },
  zoomControls: {
    display: 'flex',
    alignItems: 'center',
    gap: '8px',
    backgroundColor: '#0f172a',
    padding: '4px 8px',
    borderRadius: '8px',
    border: '1px solid #334155',
  },
  iconButton: {
    width: '28px',
    height: '28px',
    backgroundColor: '#334155',
    border: '1px solid #475569',
    borderRadius: '6px',
    color: '#f1f5f9',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: 600,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
  },
  zoomLevel: {
    fontSize: '12px',
    color: '#94a3b8',
    minWidth: '50px',
    textAlign: 'center',
    fontFamily: 'monospace',
  },
  headerRight: {
    display: 'flex',
    alignItems: 'center',
  },
  statusText: {
    fontSize: '12px',
    color: '#64748b',
  },
  main: {
    flex: 1,
    display: 'flex',
    overflow: 'hidden',
  },
  leftPanel: {
    flexShrink: 0,
  },
  canvasWrapper: {
    flex: 1,
    position: 'relative',
    overflow: 'hidden',
    backgroundColor: '#0f172a',
    backgroundImage: `
      radial-gradient(circle at 1px 1px, #334155 1px, transparent 0)
    `,
    backgroundSize: '20px 20px',
  },
  rightPanel: {
    flexShrink: 0,
    width: '320px',
    display: 'flex',
    flexDirection: 'column',
    borderLeft: '1px solid #334155',
    backgroundColor: '#1e293b',
  },
  rightPanelTabs: {
    display: 'flex',
    borderBottom: '1px solid #334155',
    flexShrink: 0,
  },
  rightPanelTab: {
    flex: 1,
    padding: '12px',
    backgroundColor: 'transparent',
    border: 'none',
    color: '#64748b',
    fontSize: '12px',
    fontWeight: 500,
    cursor: 'pointer',
    borderBottom: '2px solid transparent',
    transition: 'all 0.15s ease',
  },
  rightPanelTabActive: {
    color: '#f1f5f9',
    backgroundColor: '#0f172a',
    borderBottomColor: '#3b82f6',
  },
  rightPanelContent: {
    flex: 1,
    overflow: 'hidden',
  },
};

export default App;
