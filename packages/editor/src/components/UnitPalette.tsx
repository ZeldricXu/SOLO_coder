import { useState } from 'react';
import type { UnitTemplate, ThemeCategory } from '../types/editor';

interface UnitPaletteProps {
  selectedUnitTemplate: UnitTemplate | null;
  onSelectTemplate: (template: UnitTemplate | null) => void;
}

const UNIT_TEMPLATES: UnitTemplate[] = [
  {
    id: 'mecha-heavy',
    name: '重型机甲',
    theme: 'scifi',
    icon: '🤖',
    color: '#3b82f6',
    faction: 'player',
    baseStats: {
      maxHp: 200,
      hp: 200,
      attack: 45,
      defense: 35,
      speed: 3,
      moveRange: 3,
      attackRange: 1,
      visionRange: 5,
    },
  },
  {
    id: 'mecha-light',
    name: '轻型机甲',
    theme: 'scifi',
    icon: '🦾',
    color: '#22d3ee',
    faction: 'player',
    baseStats: {
      maxHp: 100,
      hp: 100,
      attack: 30,
      defense: 15,
      speed: 9,
      moveRange: 6,
      attackRange: 1,
      visionRange: 7,
    },
  },
  {
    id: 'mecha-sniper',
    name: '狙击机甲',
    theme: 'scifi',
    icon: '🎯',
    color: '#a855f7',
    faction: 'player',
    baseStats: {
      maxHp: 80,
      hp: 80,
      attack: 60,
      defense: 10,
      speed: 4,
      moveRange: 3,
      attackRange: 5,
      visionRange: 8,
      accuracy: 95,
    },
  },
  {
    id: 'alien-grunt',
    name: '外星步兵',
    theme: 'scifi',
    icon: '👽',
    color: '#ef4444',
    faction: 'enemy',
    baseStats: {
      maxHp: 120,
      hp: 120,
      attack: 35,
      defense: 20,
      speed: 6,
      moveRange: 4,
      attackRange: 1,
      visionRange: 6,
    },
  },
  {
    id: 'alien-tank',
    name: '外星坦克',
    theme: 'scifi',
    icon: '🛸',
    color: '#dc2626',
    faction: 'enemy',
    baseStats: {
      maxHp: 250,
      hp: 250,
      attack: 40,
      defense: 45,
      speed: 2,
      moveRange: 2,
      attackRange: 2,
      visionRange: 5,
    },
  },
  {
    id: 'knight',
    name: '骑士',
    theme: 'fantasy',
    icon: '🗡️',
    color: '#3b82f6',
    faction: 'player',
    baseStats: {
      maxHp: 150,
      hp: 150,
      attack: 40,
      defense: 30,
      speed: 6,
      moveRange: 4,
      attackRange: 1,
      visionRange: 6,
    },
  },
  {
    id: 'warrior',
    name: '步兵战士',
    theme: 'fantasy',
    icon: '⚔️',
    color: '#60a5fa',
    faction: 'player',
    baseStats: {
      maxHp: 100,
      hp: 100,
      attack: 30,
      defense: 20,
      speed: 5,
      moveRange: 4,
      attackRange: 1,
      visionRange: 6,
    },
  },
  {
    id: 'mage',
    name: '法师',
    theme: 'fantasy',
    icon: '🧙',
    color: '#a855f7',
    faction: 'player',
    baseStats: {
      maxHp: 70,
      hp: 70,
      maxMp: 100,
      mp: 100,
      magicAttack: 55,
      defense: 10,
      speed: 4,
      moveRange: 3,
      attackRange: 4,
      visionRange: 7,
    },
  },
  {
    id: 'archer',
    name: '弓箭手',
    theme: 'fantasy',
    icon: '🏹',
    color: '#22c55e',
    faction: 'player',
    baseStats: {
      maxHp: 80,
      hp: 80,
      attack: 45,
      defense: 12,
      speed: 6,
      moveRange: 4,
      attackRange: 4,
      visionRange: 8,
      accuracy: 90,
    },
  },
  {
    id: 'orc',
    name: '兽人',
    theme: 'fantasy',
    icon: '👹',
    color: '#ef4444',
    faction: 'enemy',
    baseStats: {
      maxHp: 140,
      hp: 140,
      attack: 45,
      defense: 18,
      speed: 5,
      moveRange: 3,
      attackRange: 1,
      visionRange: 5,
    },
  },
  {
    id: 'dragon',
    name: '幼龙',
    theme: 'fantasy',
    icon: '🐉',
    color: '#dc2626',
    faction: 'enemy',
    baseStats: {
      maxHp: 220,
      hp: 220,
      attack: 55,
      defense: 35,
      magicAttack: 40,
      speed: 7,
      moveRange: 5,
      attackRange: 2,
      visionRange: 8,
    },
  },
  {
    id: 'soldier',
    name: '突击兵',
    theme: 'modern',
    icon: '🔫',
    color: '#3b82f6',
    faction: 'player',
    baseStats: {
      maxHp: 100,
      hp: 100,
      attack: 35,
      defense: 18,
      speed: 6,
      moveRange: 4,
      attackRange: 3,
      visionRange: 7,
      accuracy: 85,
    },
  },
  {
    id: 'tank-operator',
    name: '坦克兵',
    theme: 'modern',
    icon: '🚛',
    color: '#1e40af',
    faction: 'player',
    baseStats: {
      maxHp: 200,
      hp: 200,
      attack: 55,
      defense: 40,
      speed: 2,
      moveRange: 2,
      attackRange: 4,
      visionRange: 6,
    },
  },
  {
    id: 'medic',
    name: '医疗兵',
    theme: 'modern',
    icon: '💉',
    color: '#10b981',
    faction: 'player',
    baseStats: {
      maxHp: 80,
      hp: 80,
      attack: 15,
      defense: 15,
      magicAttack: 40,
      speed: 7,
      moveRange: 5,
      attackRange: 2,
      visionRange: 6,
    },
  },
  {
    id: 'terrorist',
    name: '恐怖分子',
    theme: 'modern',
    icon: '💣',
    color: '#ef4444',
    faction: 'enemy',
    baseStats: {
      maxHp: 90,
      hp: 90,
      attack: 40,
      defense: 12,
      speed: 6,
      moveRange: 4,
      attackRange: 2,
      visionRange: 6,
    },
  },
  {
    id: 'heavy-gunner',
    name: '重炮手',
    theme: 'modern',
    icon: '💥',
    color: '#dc2626',
    faction: 'enemy',
    baseStats: {
      maxHp: 150,
      hp: 150,
      attack: 60,
      defense: 25,
      speed: 2,
      moveRange: 2,
      attackRange: 5,
      visionRange: 7,
    },
  },
  {
    id: 'drone',
    name: '无人机',
    theme: 'scifi',
    icon: '🛩️',
    color: '#f59e0b',
    faction: 'neutral',
    baseStats: {
      maxHp: 60,
      hp: 60,
      attack: 25,
      defense: 8,
      speed: 10,
      moveRange: 7,
      attackRange: 3,
      visionRange: 10,
    },
  },
  {
    id: 'golem',
    name: '石像守卫',
    theme: 'fantasy',
    icon: '🗿',
    color: '#78716c',
    faction: 'neutral',
    baseStats: {
      maxHp: 300,
      hp: 300,
      attack: 50,
      defense: 50,
      speed: 1,
      moveRange: 1,
      attackRange: 1,
      visionRange: 4,
    },
  },
];

const THEMES: Array<{ id: ThemeCategory | 'all'; label: string; icon: string }> = [
  { id: 'all', label: '全部', icon: '📋' },
  { id: 'scifi', label: '科幻', icon: '🚀' },
  { id: 'fantasy', label: '奇幻', icon: '🐲' },
  { id: 'modern', label: '现代', icon: '🎖️' },
];

export function UnitPalette({
  selectedUnitTemplate,
  onSelectTemplate,
}: UnitPaletteProps) {
  const [activeTheme, setActiveTheme] = useState<ThemeCategory | 'all'>('all');
  const [searchTerm, setSearchTerm] = useState('');

  const filteredTemplates = UNIT_TEMPLATES.filter(t => {
    const themeMatch = activeTheme === 'all' || t.theme === activeTheme;
    const searchMatch = searchTerm === '' ||
      t.name.toLowerCase().includes(searchTerm.toLowerCase());
    return themeMatch && searchMatch;
  });

  const templatesByTheme = THEMES.filter(t => t.id !== 'all').map(theme => ({
    ...theme,
    units: filteredTemplates.filter(t => t.theme === theme.id),
  }));

  return (
    <div style={styles.container}>
      <div style={styles.header}>
        <h3 style={styles.title}>单位模板</h3>
        <p style={styles.subtitle}>选择单位后点击画布放置</p>
      </div>

      <div style={styles.searchBox}>
        <span style={styles.searchIcon}>🔍</span>
        <input
          type="text"
          placeholder="搜索单位..."
          value={searchTerm}
          onChange={e => setSearchTerm(e.target.value)}
          style={styles.searchInput}
        />
      </div>

      <div style={styles.themeTabs}>
        {THEMES.map(theme => (
          <button
            key={theme.id}
            style={{
              ...styles.themeTab,
              ...(activeTheme === theme.id ? styles.themeTabActive : {}),
            }}
            onClick={() => setActiveTheme(theme.id)}
          >
            <span>{theme.icon}</span>
            <span>{theme.label}</span>
          </button>
        ))}
      </div>

      <div style={styles.selectedInfo}>
        {selectedUnitTemplate ? (
          <div style={styles.selectedCard}>
            <span style={{ fontSize: '24px' }}>{selectedUnitTemplate.icon}</span>
            <div style={{ flex: 1 }}>
              <div style={{ fontWeight: 600, fontSize: '13px' }}>
                {selectedUnitTemplate.name}
              </div>
              <div style={{ fontSize: '11px', color: '#94a3b8' }}>
                已选择，点击画布放置
              </div>
            </div>
            <button
              style={styles.clearButton}
              onClick={() => onSelectTemplate(null)}
              title="取消选择"
            >
              ✕
            </button>
          </div>
        ) : (
          <p style={styles.emptySelected}>未选择单位</p>
        )}
      </div>

      <div style={styles.unitsContainer}>
        {activeTheme === 'all' ? (
          templatesByTheme.map(theme =>
            theme.units.length > 0 && (
              <div key={theme.id} style={styles.themeGroup}>
                <h4 style={styles.themeGroupTitle}>
                  <span>{theme.icon}</span> {theme.label}
                  <span style={styles.themeGroupCount}>{theme.units.length}</span>
                </h4>
                <div style={styles.unitsGrid}>
                  {theme.units.map(unit => (
                    <UnitCard
                      key={unit.id}
                      unit={unit}
                      isSelected={selectedUnitTemplate?.id === unit.id}
                      onClick={() => onSelectTemplate(
                        selectedUnitTemplate?.id === unit.id ? null : unit
                      )}
                    />
                  ))}
                </div>
              </div>
            )
          )
        ) : (
          <div style={styles.unitsGrid}>
            {filteredTemplates.map(unit => (
              <UnitCard
                key={unit.id}
                unit={unit}
                isSelected={selectedUnitTemplate?.id === unit.id}
                onClick={() => onSelectTemplate(
                  selectedUnitTemplate?.id === unit.id ? null : unit
                )}
              />
            ))}
          </div>
        )}
        {filteredTemplates.length === 0 && (
          <p style={styles.noResults}>没有找到匹配的单位</p>
        )}
      </div>
    </div>
  );
}

function UnitCard({
  unit,
  isSelected,
  onClick,
}: {
  unit: UnitTemplate;
  isSelected: boolean;
  onClick: () => void;
}) {
  const factionLabels: Record<string, string> = {
    player: '玩家',
    enemy: '敌方',
    neutral: '中立',
  };
  const factionColors: Record<string, string> = {
    player: '#3b82f6',
    enemy: '#ef4444',
    neutral: '#f59e0b',
  };

  return (
    <button
      style={{
        ...styles.unitCard,
        ...(isSelected ? styles.unitCardSelected : {}),
        borderColor: isSelected ? factionColors[unit.faction] : undefined,
      }}
      onClick={onClick}
    >
      <div
        style={{
          ...styles.unitIconBg,
          backgroundColor: unit.color + '30',
          borderColor: unit.color + '60',
        }}
      >
        <span style={{ fontSize: '28px' }}>{unit.icon}</span>
      </div>
      <div style={styles.unitName}>{unit.name}</div>
      <div
        style={{
          ...styles.factionTag,
          backgroundColor: factionColors[unit.faction] + '30',
          color: factionColors[unit.faction],
        }}
      >
        {factionLabels[unit.faction] || unit.faction}
      </div>
      <div style={styles.statsPreview}>
        <span title="HP">❤️{unit.baseStats.hp ?? '?'}</span>
        <span title="攻击">⚔️{unit.baseStats.attack ?? '?'}</span>
        <span title="防御">🛡️{unit.baseStats.defense ?? '?'}</span>
      </div>
    </button>
  );
}

const styles: Record<string, React.CSSProperties> = {
  container: {
    display: 'flex',
    flexDirection: 'column',
    height: '100%',
    backgroundColor: '#1e293b',
    color: '#f1f5f9',
    overflow: 'hidden',
  },
  header: {
    padding: '12px 16px',
    borderBottom: '1px solid #334155',
    backgroundColor: '#0f172a',
  },
  title: {
    fontSize: '14px',
    fontWeight: 600,
    margin: 0,
    color: '#f1f5f9',
  },
  subtitle: {
    fontSize: '11px',
    color: '#64748b',
    margin: '4px 0 0 0',
  },
  searchBox: {
    position: 'relative',
    padding: '12px 16px',
    borderBottom: '1px solid #334155',
  },
  searchIcon: {
    position: 'absolute',
    left: '28px',
    top: '50%',
    transform: 'translateY(-50%)',
    fontSize: '14px',
    color: '#64748b',
  },
  searchInput: {
    width: '100%',
    padding: '8px 12px 8px 36px',
    backgroundColor: '#334155',
    border: '1px solid #475569',
    borderRadius: '6px',
    color: '#f1f5f9',
    fontSize: '12px',
    boxSizing: 'border-box',
  },
  themeTabs: {
    display: 'flex',
    padding: '8px 16px',
    gap: '6px',
    borderBottom: '1px solid #334155',
    overflowX: 'auto',
  },
  themeTab: {
    display: 'flex',
    alignItems: 'center',
    gap: '4px',
    padding: '6px 12px',
    backgroundColor: '#334155',
    border: '1px solid transparent',
    borderRadius: '6px',
    color: '#94a3b8',
    fontSize: '11px',
    fontWeight: 500,
    cursor: 'pointer',
    whiteSpace: 'nowrap',
  },
  themeTabActive: {
    backgroundColor: '#3b82f6',
    color: '#fff',
    borderColor: '#60a5fa',
  },
  selectedInfo: {
    padding: '12px 16px',
    borderBottom: '1px solid #334155',
    backgroundColor: '#0f172a',
  },
  selectedCard: {
    display: 'flex',
    alignItems: 'center',
    gap: '10px',
    padding: '8px 12px',
    backgroundColor: '#1e3a5f',
    borderRadius: '8px',
    border: '1px solid #3b82f6',
  },
  clearButton: {
    width: '24px',
    height: '24px',
    borderRadius: '4px',
    backgroundColor: '#475569',
    border: 'none',
    color: '#f1f5f9',
    cursor: 'pointer',
    fontSize: '12px',
  },
  emptySelected: {
    margin: 0,
    fontSize: '12px',
    color: '#64748b',
    textAlign: 'center',
    fontStyle: 'italic',
  },
  unitsContainer: {
    flex: 1,
    overflowY: 'auto',
    padding: '12px 16px',
  },
  themeGroup: {
    marginBottom: '20px',
  },
  themeGroupTitle: {
    display: 'flex',
    alignItems: 'center',
    gap: '6px',
    fontSize: '12px',
    fontWeight: 600,
    color: '#94a3b8',
    margin: '0 0 10px 0',
    textTransform: 'uppercase',
    letterSpacing: '0.3px',
  },
  themeGroupCount: {
    marginLeft: 'auto',
    backgroundColor: '#334155',
    padding: '2px 8px',
    borderRadius: '10px',
    fontSize: '10px',
    color: '#64748b',
  },
  unitsGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(2, 1fr)',
    gap: '8px',
  },
  unitCard: {
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    gap: '6px',
    padding: '10px',
    backgroundColor: '#0f172a',
    border: '2px solid #334155',
    borderRadius: '8px',
    cursor: 'pointer',
    transition: 'all 0.15s ease',
  },
  unitCardSelected: {
    borderColor: '#3b82f6',
    transform: 'translateY(-1px)',
    boxShadow: '0 4px 12px rgba(59, 130, 246, 0.3)',
  },
  unitIconBg: {
    width: '48px',
    height: '48px',
    borderRadius: '8px',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    border: '1px solid',
  },
  unitName: {
    fontSize: '11px',
    fontWeight: 600,
    color: '#f1f5f9',
    textAlign: 'center',
  },
  factionTag: {
    fontSize: '10px',
    padding: '2px 8px',
    borderRadius: '10px',
    fontWeight: 500,
  },
  statsPreview: {
    display: 'flex',
    gap: '6px',
    fontSize: '10px',
    color: '#94a3b8',
  },
  noResults: {
    textAlign: 'center',
    color: '#64748b',
    fontSize: '12px',
    padding: '40px 0',
    fontStyle: 'italic',
  },
};
