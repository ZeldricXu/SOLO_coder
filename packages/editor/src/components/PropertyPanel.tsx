import { useState } from 'react';
import {
  HexGrid,
  cubeToOffset,
  terrainRegistry,
  generateId,
  cubeEquals,
  LevelValidator,
  LevelSerializer,
  MapGenerator,
  MapGeneratorConfig,
} from '@tactics/core';
import type {
  CubeCoords,
  HexTile,
  CombatUnit,
  Direction,
  Faction,
} from '@tactics/core';
import type { TerrainType } from '../types/editor';
import { getFactionColor } from '../utils/hexRender';

interface PropertyPanelProps {
  grid: HexGrid;
  units: Map<string, CombatUnit>;
  selectedTile: CubeCoords | null;
  onGridChange: () => void;
  onUnitsChange: (units: Map<string, CombatUnit>) => void;
  saveSnapshot: () => void;
}

export function PropertyPanel({
  grid,
  units,
  selectedTile,
  onGridChange,
  onUnitsChange,
  saveSnapshot,
}: PropertyPanelProps) {
  const [validationResult, setValidationResult] = useState<string | null>(null);
  const [fileName, setFileName] = useState('level.json');
  const [generatorConfig, setGeneratorConfig] = useState<Partial<MapGeneratorConfig>>({
    seed: Date.now(),
    width: 15,
    height: 15,
    forestDensity: 0.2,
    mountainDensity: 0.15,
    waterDensity: 0.1,
    roadProbability: 0.3,
  });

  const tile = selectedTile ? grid.getTile(selectedTile) : null;
  const offset = selectedTile ? cubeToOffset(selectedTile, grid.getConfig().orientation) : null;

  const handleTerrainChange = (terrain: TerrainType) => {
    if (!selectedTile) return;
    saveSnapshot();
    grid.setTileTerrain(selectedTile, terrain as typeof tile extends HexTile ? typeof tile.terrain : any);
    onGridChange();
  };

  const handleHeightChange = (height: number) => {
    if (!selectedTile) return;
    saveSnapshot();
    grid.setTileHeight(selectedTile, height);
    onGridChange();
  };

  const updateUnitProperty = (unitId: string, updates: Partial<CombatUnit>) => {
    const unit = units.get(unitId);
    if (!unit) return;
    saveSnapshot();
    const newUnits = new Map(units);
    const updated: CombatUnit = { ...unit, ...updates };
    if (updates.stats) {
      updated.stats = { ...unit.stats, ...updates.stats };
    }
    if (updates.coords && !cubeEquals(unit.coords, updates.coords)) {
      grid.removeUnit(unit.coords, unitId);
      grid.addUnit(updates.coords, unitId);
    }
    newUnits.set(unitId, updated);
    onUnitsChange(newUnits);
    onGridChange();
  };

  const removeUnit = (unitId: string) => {
    const unit = units.get(unitId);
    if (!unit) return;
    saveSnapshot();
    grid.removeUnit(unit.coords, unitId);
    const newUnits = new Map(units);
    newUnits.delete(unitId);
    onUnitsChange(newUnits);
    onGridChange();
  };

  const handleSave = () => {
    const levelData = LevelSerializer.serializeLevel({
      id: generateId(),
      name: fileName.replace('.json', ''),
      grid: grid.toJSON() as any,
      units: Array.from(units.values()),
      metadata: { createdAt: Date.now() },
    });
    const blob = new Blob([JSON.stringify(levelData, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleLoad = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (event) => {
      try {
        const data = JSON.parse(event.target?.result as string);
        const level = LevelSerializer.deserializeLevel(data);
        saveSnapshot();
        const tiles = level.grid.getAllTiles();
        for (const t of tiles) {
          const originalTile = grid.getTile(t.coords);
          if (originalTile) {
            originalTile.terrain = t.terrain;
            originalTile.height = t.height;
            originalTile.units = [...t.units];
            originalTile.objects = [...t.objects];
          }
        }
        const newUnits = new Map<string, CombatUnit>();
        for (const u of level.units) {
          newUnits.set(u.id, u);
        }
        onUnitsChange(newUnits);
        onGridChange();
      } catch (err) {
        alert('加载失败: ' + (err as Error).message);
      }
    };
    reader.readAsText(file);
    e.target.value = '';
  };

  const handleExport = () => {
    const exportData = {
      grid: grid.toJSON(),
      units: Array.from(units.values()),
      dimensions: grid.getDimensions(),
      exportedAt: new Date().toISOString(),
    };
    const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `export_${Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleValidate = () => {
    const validator = new LevelValidator();
    const levelData = {
      id: generateId(),
      name: 'current',
      grid: grid.toJSON() as any,
      units: Array.from(units.values()),
      metadata: { createdAt: Date.now() },
    };
    const result = validator.validateLevel(levelData);
    if (result.valid) {
      setValidationResult(`✅ 关卡有效！\n瓦片数: ${grid.getTileCount()}\n单位数: ${units.size}\n评分: ${result.score}/100`);
    } else {
      setValidationResult(`❌ 关卡无效:\n${result.errors.join('\n')}`);
    }
  };

  const handleGenerateMap = () => {
    saveSnapshot();
    const generator = new MapGenerator({
      seed: generatorConfig.seed ?? Date.now(),
      width: generatorConfig.width ?? 15,
      height: generatorConfig.height ?? 15,
      scale: 0.1,
      octaves: 4,
      persistence: 0.5,
      lacunarity: 2.0,
      terrainThresholds: [
        { maxHeight: -0.3, terrain: 'water' },
        { maxHeight: 0.0, terrain: 'sand' },
        { maxHeight: 0.3, terrain: 'plain' },
        { maxHeight: 0.6, terrain: 'forest' },
        { maxHeight: 0.85, terrain: 'mountain' },
        { maxHeight: 1.0, terrain: 'snow' },
      ],
      randomFeatures: {
        forestDensity: generatorConfig.forestDensity ?? 0.2,
        mountainDensity: generatorConfig.mountainDensity ?? 0.15,
        waterDensity: generatorConfig.waterDensity ?? 0.1,
        roadProbability: generatorConfig.roadProbability ?? 0.3,
      },
    });
    const generated = generator.generate();
    const tiles = generated.getAllTiles();
    for (const t of tiles) {
      const originalTile = grid.getTile(t.coords);
      if (originalTile) {
        originalTile.terrain = t.terrain;
        originalTile.height = Math.round(t.height * 5);
        originalTile.units = [];
        originalTile.objects = [];
      }
    }
    onGridChange();
  };

  const tileUnits = tile ? tile.units.map(id => units.get(id)).filter(Boolean) as CombatUnit[] : [];
  const terrains = terrainRegistry.getAll();

  return (
    <div style={styles.container}>
      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>地图信息</h3>
        <div style={styles.infoGrid}>
          <div style={styles.infoItem}>
            <span style={styles.infoLabel}>尺寸</span>
            <span style={styles.infoValue}>{grid.getDimensions().width} × {grid.getDimensions().height}</span>
          </div>
          <div style={styles.infoItem}>
            <span style={styles.infoLabel}>瓦片数</span>
            <span style={styles.infoValue}>{grid.getTileCount()}</span>
          </div>
          <div style={styles.infoItem}>
            <span style={styles.infoLabel}>单位数</span>
            <span style={styles.infoValue}>{units.size}</span>
          </div>
        </div>
      </div>

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>瓦片属性</h3>
        {tile && offset ? (
          <div style={styles.formGroup}>
            <div style={styles.field}>
              <label style={styles.label}>立方坐标</label>
              <div style={styles.coordBox}>
                <span>q: {selectedTile!.q}</span>
                <span>r: {selectedTile!.r}</span>
                <span>s: {selectedTile!.s}</span>
              </div>
            </div>
            <div style={styles.field}>
              <label style={styles.label}>偏移坐标</label>
              <div style={styles.coordBox}>
                <span>列: {offset.col}</span>
                <span>行: {offset.row}</span>
              </div>
            </div>
            <div style={styles.field}>
              <label style={styles.label}>地形</label>
              <select
                style={styles.select}
                value={tile.terrain}
                onChange={e => handleTerrainChange(e.target.value as TerrainType)}
              >
                {terrains.map(t => (
                  <option key={t.type} value={t.type}>{t.name}</option>
                ))}
              </select>
              <div style={styles.terrainInfo}>
                <span style={{ ...styles.terrainDot, backgroundColor: terrainRegistry.get(tile.terrain).color }} />
                <span>移动消耗: {terrainRegistry.get(tile.terrain).moveCost}</span>
                <span>防御: +{terrainRegistry.get(tile.terrain).defenseBonus}%</span>
              </div>
            </div>
            <div style={styles.field}>
              <label style={styles.label}>高度: {tile.height}</label>
              <input
                type="range"
                min="-5"
                max="10"
                value={tile.height}
                onChange={e => handleHeightChange(Number(e.target.value))}
                style={styles.slider}
              />
            </div>
            <div style={styles.field}>
              <label style={styles.label}>物件数</label>
              <span style={styles.infoValue}>{tile.objects.length}</span>
            </div>
          </div>
        ) : (
          <p style={styles.emptyText}>请选择一个瓦片查看属性</p>
        )}
      </div>

      {tileUnits.length > 0 && (
        <div style={styles.section}>
          <h3 style={styles.sectionTitle}>瓦片单位 ({tileUnits.length})</h3>
          {tileUnits.map(unit => (
            <div key={unit.id} style={styles.unitCard}>
              <div style={styles.unitHeader}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <span style={{ ...styles.factionBadge, backgroundColor: getFactionColor(unit.faction) }} />
                  <span style={styles.unitName}>{unit.name}</span>
                </div>
                <button
                  style={styles.removeButton}
                  onClick={() => removeUnit(unit.id)}
                  title="删除单位"
                >
                  ✕
                </button>
              </div>
              <div style={styles.unitStats}>
                <div style={styles.statField}>
                  <label>HP</label>
                  <input
                    type="number"
                    value={unit.stats.hp}
                    onChange={e => updateUnitProperty(unit.id, {
                      stats: { ...unit.stats, hp: Number(e.target.value) }
                    })}
                    style={styles.numberInput}
                  />
                </div>
                <div style={styles.statField}>
                  <label>最大HP</label>
                  <input
                    type="number"
                    value={unit.stats.maxHp}
                    onChange={e => updateUnitProperty(unit.id, {
                      stats: { ...unit.stats, maxHp: Number(e.target.value) }
                    })}
                    style={styles.numberInput}
                  />
                </div>
                <div style={styles.statField}>
                  <label>攻击</label>
                  <input
                    type="number"
                    value={unit.stats.attack}
                    onChange={e => updateUnitProperty(unit.id, {
                      stats: { ...unit.stats, attack: Number(e.target.value) }
                    })}
                    style={styles.numberInput}
                  />
                </div>
                <div style={styles.statField}>
                  <label>防御</label>
                  <input
                    type="number"
                    value={unit.stats.defense}
                    onChange={e => updateUnitProperty(unit.id, {
                      stats: { ...unit.stats, defense: Number(e.target.value) }
                    })}
                    style={styles.numberInput}
                  />
                </div>
                <div style={styles.statField}>
                  <label>速度</label>
                  <input
                    type="number"
                    value={unit.stats.speed}
                    onChange={e => updateUnitProperty(unit.id, {
                      stats: { ...unit.stats, speed: Number(e.target.value) }
                    })}
                    style={styles.numberInput}
                  />
                </div>
                <div style={styles.statField}>
                  <label>阵营</label>
                  <select
                    value={unit.faction}
                    onChange={e => updateUnitProperty(unit.id, { faction: e.target.value as Faction })}
                    style={styles.select}
                  >
                    <option value="player">玩家</option>
                    <option value="enemy">敌方</option>
                    <option value="neutral">中立</option>
                  </select>
                </div>
                <div style={styles.statField}>
                  <label>朝向</label>
                  <select
                    value={unit.direction}
                    onChange={e => updateUnitProperty(unit.id, { direction: Number(e.target.value) as Direction })}
                    style={styles.select}
                  >
                    <option value={0}>方向 0 (右上)</option>
                    <option value={1}>方向 1 (右)</option>
                    <option value={2}>方向 2 (右下)</option>
                    <option value={3}>方向 3 (左下)</option>
                    <option value={4}>方向 4 (左)</option>
                    <option value={5}>方向 5 (左上)</option>
                  </select>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>地图生成器</h3>
        <div style={styles.formGroup}>
          <div style={styles.statField}>
            <label>种子</label>
            <input
              type="number"
              value={generatorConfig.seed ?? 0}
              onChange={e => setGeneratorConfig({ ...generatorConfig, seed: Number(e.target.value) })}
              style={styles.numberInput}
            />
          </div>
          <div style={styles.statField}>
            <label>宽度</label>
            <input
              type="number"
              min="5"
              max="50"
              value={generatorConfig.width ?? 15}
              onChange={e => setGeneratorConfig({ ...generatorConfig, width: Number(e.target.value) })}
              style={styles.numberInput}
            />
          </div>
          <div style={styles.statField}>
            <label>高度</label>
            <input
              type="number"
              min="5"
              max="50"
              value={generatorConfig.height ?? 15}
              onChange={e => setGeneratorConfig({ ...generatorConfig, height: Number(e.target.value) })}
              style={styles.numberInput}
            />
          </div>
          <div style={styles.statField}>
            <label>森林密度</label>
            <input
              type="range"
              min="0"
              max="1"
              step="0.05"
              value={generatorConfig.forestDensity ?? 0.2}
              onChange={e => setGeneratorConfig({ ...generatorConfig, forestDensity: Number(e.target.value) })}
              style={styles.slider}
            />
          </div>
          <div style={styles.statField}>
            <label>山地密度</label>
            <input
              type="range"
              min="0"
              max="1"
              step="0.05"
              value={generatorConfig.mountainDensity ?? 0.15}
              onChange={e => setGeneratorConfig({ ...generatorConfig, mountainDensity: Number(e.target.value) })}
              style={styles.slider}
            />
          </div>
          <button style={styles.primaryButton} onClick={handleGenerateMap}>
            🎲 生成地图
          </button>
        </div>
      </div>

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>文件操作</h3>
        <div style={styles.formGroup}>
          <div style={styles.statField}>
            <label>文件名</label>
            <input
              type="text"
              value={fileName}
              onChange={e => setFileName(e.target.value)}
              style={styles.textInput}
            />
          </div>
          <div style={styles.buttonRow}>
            <button style={styles.primaryButton} onClick={handleSave}>💾 保存</button>
            <label style={styles.fileInputLabel}>
              📂 加载
              <input type="file" accept=".json" onChange={handleLoad} style={{ display: 'none' }} />
            </label>
          </div>
          <button style={styles.secondaryButton} onClick={handleExport}>📤 导出JSON</button>
        </div>
      </div>

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>关卡验证</h3>
        <button style={styles.primaryButton} onClick={handleValidate}>
          ✓ 验证关卡
        </button>
        {validationResult && (
          <pre style={styles.validationResult}>{validationResult}</pre>
        )}
      </div>
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  container: {
    width: '320px',
    height: '100%',
    backgroundColor: '#1e293b',
    color: '#f1f5f9',
    borderLeft: '1px solid #334155',
    overflowY: 'auto',
    padding: '16px',
    display: 'flex',
    flexDirection: 'column',
    gap: '20px',
  },
  section: {
    display: 'flex',
    flexDirection: 'column',
    gap: '12px',
  },
  sectionTitle: {
    fontSize: '13px',
    fontWeight: 600,
    color: '#94a3b8',
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
    margin: 0,
    paddingBottom: '6px',
    borderBottom: '1px solid #334155',
  },
  infoGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(3, 1fr)',
    gap: '8px',
  },
  infoItem: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    gap: '4px',
    padding: '8px',
    backgroundColor: '#0f172a',
    borderRadius: '6px',
  },
  infoLabel: {
    fontSize: '10px',
    color: '#64748b',
    textTransform: 'uppercase',
  },
  infoValue: {
    fontSize: '14px',
    fontWeight: 600,
    color: '#f1f5f9',
  },
  formGroup: {
    display: 'flex',
    flexDirection: 'column',
    gap: '12px',
  },
  field: {
    display: 'flex',
    flexDirection: 'column',
    gap: '6px',
  },
  label: {
    fontSize: '12px',
    color: '#94a3b8',
    fontWeight: 500,
  },
  coordBox: {
    display: 'flex',
    justifyContent: 'space-between',
    padding: '8px 12px',
    backgroundColor: '#0f172a',
    borderRadius: '6px',
    fontFamily: 'monospace',
    fontSize: '12px',
  },
  select: {
    padding: '8px 12px',
    backgroundColor: '#334155',
    border: '1px solid #475569',
    borderRadius: '6px',
    color: '#f1f5f9',
    fontSize: '13px',
  },
  textInput: {
    padding: '8px 12px',
    backgroundColor: '#334155',
    border: '1px solid #475569',
    borderRadius: '6px',
    color: '#f1f5f9',
    fontSize: '13px',
  },
  numberInput: {
    padding: '6px 8px',
    backgroundColor: '#334155',
    border: '1px solid #475569',
    borderRadius: '4px',
    color: '#f1f5f9',
    fontSize: '12px',
    width: '60px',
  },
  slider: {
    width: '100%',
    height: '6px',
    accentColor: '#3b82f6',
  },
  terrainInfo: {
    display: 'flex',
    alignItems: 'center',
    gap: '12px',
    fontSize: '11px',
    color: '#94a3b8',
  },
  terrainDot: {
    width: '12px',
    height: '12px',
    borderRadius: '50%',
    border: '1px solid rgba(255,255,255,0.2)',
  },
  emptyText: {
    fontSize: '12px',
    color: '#64748b',
    fontStyle: 'italic',
    padding: '12px',
    textAlign: 'center',
    backgroundColor: '#0f172a',
    borderRadius: '6px',
  },
  unitCard: {
    backgroundColor: '#0f172a',
    borderRadius: '8px',
    padding: '12px',
    display: 'flex',
    flexDirection: 'column',
    gap: '10px',
  },
  unitHeader: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingBottom: '8px',
    borderBottom: '1px solid #334155',
  },
  unitName: {
    fontSize: '13px',
    fontWeight: 600,
  },
  factionBadge: {
    width: '10px',
    height: '10px',
    borderRadius: '50%',
  },
  removeButton: {
    width: '24px',
    height: '24px',
    borderRadius: '4px',
    backgroundColor: '#dc2626',
    border: 'none',
    color: '#fff',
    cursor: 'pointer',
    fontSize: '12px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
  },
  unitStats: {
    display: 'grid',
    gridTemplateColumns: 'repeat(2, 1fr)',
    gap: '8px',
  },
  statField: {
    display: 'flex',
    flexDirection: 'column',
    gap: '4px',
  },
  buttonRow: {
    display: 'flex',
    gap: '8px',
  },
  primaryButton: {
    padding: '10px 16px',
    backgroundColor: '#3b82f6',
    border: 'none',
    borderRadius: '6px',
    color: '#fff',
    fontSize: '13px',
    fontWeight: 600,
    cursor: 'pointer',
    flex: 1,
  },
  secondaryButton: {
    padding: '10px 16px',
    backgroundColor: '#334155',
    border: '1px solid #475569',
    borderRadius: '6px',
    color: '#f1f5f9',
    fontSize: '13px',
    fontWeight: 500,
    cursor: 'pointer',
  },
  fileInputLabel: {
    padding: '10px 16px',
    backgroundColor: '#334155',
    border: '1px solid #475569',
    borderRadius: '6px',
    color: '#f1f5f9',
    fontSize: '13px',
    fontWeight: 500,
    cursor: 'pointer',
    flex: 1,
    textAlign: 'center',
  },
  validationResult: {
    padding: '12px',
    backgroundColor: '#0f172a',
    borderRadius: '6px',
    fontSize: '12px',
    fontFamily: 'monospace',
    whiteSpace: 'pre-wrap',
    lineHeight: 1.6,
    color: '#e2e8f0',
    border: '1px solid #334155',
  },
};
