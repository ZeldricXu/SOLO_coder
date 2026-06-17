import { terrainRegistry } from '@tactics/core';
import type { EditorTool, TerrainType } from '../types/editor';

interface ToolbarProps {
  currentTool: EditorTool;
  currentTerrain: TerrainType;
  brushSize: number;
  onToolChange: (tool: EditorTool) => void;
  onTerrainChange: (terrain: TerrainType) => void;
  onBrushSizeChange: (size: number) => void;
  onGenerateMap: () => void;
  canUndo: boolean;
  canRedo: boolean;
  onUndo: () => void;
  onRedo: () => void;
}

const tools: Array<{ id: EditorTool; label: string; icon: string; description: string }> = [
  { id: 'select', label: '选择', icon: '👆', description: '点击选择瓦片' },
  { id: 'brush', label: '画笔', icon: '🖌️', description: '绘制地形' },
  { id: 'eraser', label: '橡皮', icon: '🧹', description: '清除地形/单位' },
  { id: 'placeUnit', label: '放置单位', icon: '⚔️', description: '在瓦片上放置单位' },
  { id: 'placeObject', label: '放置物件', icon: '📦', description: '在瓦片上放置物件' },
  { id: 'generateMap', label: '生成器', icon: '🎲', description: '随机生成地图' },
];

export function Toolbar({
  currentTool,
  currentTerrain,
  brushSize,
  onToolChange,
  onTerrainChange,
  onBrushSizeChange,
  onGenerateMap,
  canUndo,
  canRedo,
  onUndo,
  onRedo,
}: ToolbarProps) {
  const terrains = terrainRegistry.getAll();

  return (
    <div style={styles.container}>
      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>工具</h3>
        <div style={styles.toolsGrid}>
          {tools.map(tool => (
            <button
              key={tool.id}
              style={{
                ...styles.toolButton,
                ...(currentTool === tool.id ? styles.toolButtonActive : {}),
              }}
              onClick={() => {
                onToolChange(tool.id);
                if (tool.id === 'generateMap') {
                  onGenerateMap();
                }
              }}
              title={tool.description}
            >
              <span style={styles.toolIcon}>{tool.icon}</span>
              <span style={styles.toolLabel}>{tool.label}</span>
            </button>
          ))}
        </div>
      </div>

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>撤销/重做</h3>
        <div style={styles.inlineButtons}>
          <button
            style={{
              ...styles.actionButton,
              ...(!canUndo ? styles.actionButtonDisabled : {}),
            }}
            onClick={onUndo}
            disabled={!canUndo}
            title="撤销 (Ctrl+Z)"
          >
            ↩️ 撤销
          </button>
          <button
            style={{
              ...styles.actionButton,
              ...(!canRedo ? styles.actionButtonDisabled : {}),
            }}
            onClick={onRedo}
            disabled={!canRedo}
            title="重做 (Ctrl+Shift+Z)"
          >
            ↪️ 重做
          </button>
        </div>
      </div>

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>地形选择</h3>
        <select
          style={styles.select}
          value={currentTerrain}
          onChange={e => onTerrainChange(e.target.value as TerrainType)}
        >
          {terrains.map(t => (
            <option key={t.type} value={t.type}>
              {t.name}
            </option>
          ))}
        </select>
        <div style={styles.terrainPalette}>
          {terrains.map(t => (
            <button
              key={t.type}
              style={{
                ...styles.terrainSwatch,
                backgroundColor: t.color,
                ...(currentTerrain === t.type ? styles.terrainSwatchActive : {}),
              }}
              onClick={() => onTerrainChange(t.type)}
              title={`${t.name} (移动消耗: ${t.moveCost}, 防御: +${t.defenseBonus}%)`}
            >
              <span style={styles.terrainSwatchLabel}>{t.name}</span>
            </button>
          ))}
        </div>
      </div>

      {(currentTool === 'brush' || currentTool === 'eraser') && (
        <div style={styles.section}>
          <h3 style={styles.sectionTitle}>画笔大小: {brushSize}</h3>
          <input
            type="range"
            min="1"
            max="10"
            value={brushSize}
            onChange={e => onBrushSizeChange(Number(e.target.value))}
            style={styles.slider}
          />
          <div style={styles.brushSizeLabels}>
            <span>小</span>
            <span>大</span>
          </div>
        </div>
      )}

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>操作提示</h3>
        <div style={styles.hints}>
          {currentTool === 'select' && (
            <>
              <p>• 左键: 选择瓦片</p>
              <p>• Alt+左键/中键: 平移画布</p>
              <p>• 滚轮: 缩放</p>
            </>
          )}
          {currentTool === 'brush' && (
            <>
              <p>• 左键拖动: 绘制地形</p>
              <p>• 选择地形: 下方调色板</p>
            </>
          )}
          {currentTool === 'eraser' && (
            <>
              <p>• 左键拖动: 清除内容</p>
              <p>• 恢复为平原地形</p>
            </>
          )}
          {currentTool === 'placeUnit' && (
            <>
              <p>• 选择单位模板后</p>
              <p>• 左键: 在瓦片放置单位</p>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  container: {
    width: '240px',
    height: '100%',
    backgroundColor: '#1e293b',
    color: '#f1f5f9',
    borderRight: '1px solid #334155',
    overflowY: 'auto',
    padding: '16px',
    display: 'flex',
    flexDirection: 'column',
    gap: '20px',
  },
  section: {
    display: 'flex',
    flexDirection: 'column',
    gap: '10px',
  },
  sectionTitle: {
    fontSize: '13px',
    fontWeight: 600,
    color: '#94a3b8',
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
    margin: 0,
    paddingBottom: '4px',
    borderBottom: '1px solid #334155',
  },
  toolsGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(2, 1fr)',
    gap: '8px',
  },
  toolButton: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    gap: '4px',
    padding: '12px 8px',
    backgroundColor: '#334155',
    border: '2px solid transparent',
    borderRadius: '8px',
    color: '#f1f5f9',
    cursor: 'pointer',
    transition: 'all 0.15s ease',
  },
  toolButtonActive: {
    backgroundColor: '#3b82f6',
    borderColor: '#60a5fa',
  },
  toolIcon: {
    fontSize: '24px',
  },
  toolLabel: {
    fontSize: '11px',
    fontWeight: 500,
  },
  inlineButtons: {
    display: 'flex',
    gap: '8px',
  },
  actionButton: {
    flex: 1,
    padding: '10px',
    backgroundColor: '#334155',
    border: '1px solid #475569',
    borderRadius: '6px',
    color: '#f1f5f9',
    cursor: 'pointer',
    fontSize: '12px',
    fontWeight: 500,
  },
  actionButtonDisabled: {
    opacity: 0.4,
    cursor: 'not-allowed',
  },
  select: {
    padding: '8px 12px',
    backgroundColor: '#334155',
    border: '1px solid #475569',
    borderRadius: '6px',
    color: '#f1f5f9',
    fontSize: '13px',
    cursor: 'pointer',
  },
  terrainPalette: {
    display: 'grid',
    gridTemplateColumns: 'repeat(3, 1fr)',
    gap: '6px',
  },
  terrainSwatch: {
    aspectRatio: '1',
    border: '2px solid transparent',
    borderRadius: '6px',
    cursor: 'pointer',
    display: 'flex',
    alignItems: 'flex-end',
    justifyContent: 'center',
    padding: '4px',
    transition: 'all 0.15s ease',
  },
  terrainSwatchActive: {
    borderColor: '#fbbf24',
    transform: 'scale(1.05)',
    boxShadow: '0 0 0 2px rgba(251, 191, 36, 0.3)',
  },
  terrainSwatchLabel: {
    fontSize: '10px',
    color: '#fff',
    textShadow: '0 1px 2px rgba(0,0,0,0.8)',
    fontWeight: 600,
  },
  slider: {
    width: '100%',
    height: '6px',
    accentColor: '#3b82f6',
  },
  brushSizeLabels: {
    display: 'flex',
    justifyContent: 'space-between',
    fontSize: '11px',
    color: '#64748b',
  },
  hints: {
    backgroundColor: '#0f172a',
    padding: '10px 12px',
    borderRadius: '6px',
    fontSize: '12px',
    color: '#94a3b8',
    lineHeight: 1.8,
  },
};
