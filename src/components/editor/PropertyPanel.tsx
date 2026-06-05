import React, { useMemo } from 'react';
import { X, Trash2, Copy, RotateCw, Move } from 'lucide-react';
import { useFloorPlanStore } from '@/store/useFloorPlanStore';
import { useUIStore } from '@/store/useUIStore';
import { formatLength } from '@/utils/math';
import { distance } from '@/utils/geometry';
import type { Wall, Opening, FurnitureItem, LightSource } from '@/types/floorplan';

export const PropertyPanel: React.FC = () => {
  const { selectedIds, floorPlan, updateWall, removeWall, updateOpening, removeOpening, updateFurniture, removeFurniture } =
    useFloorPlanStore();
  const { panels, setPanel } = useUIStore();

  const selectedObject = useMemo(() => {
    if (selectedIds.length === 0) return null;
    const id = selectedIds[0];

    const wall = floorPlan.walls.find((w) => w.id === id);
    if (wall) return { type: 'wall' as const, data: wall };

    const opening = floorPlan.openings.find((o) => o.id === id);
    if (opening) return { type: 'opening' as const, data: opening };

    const furniture = floorPlan.furniture.find((f) => f.id === id);
    if (furniture) return { type: 'furniture' as const, data: furniture };

    const light = floorPlan.lights.find((l) => l.id === id);
    if (light) return { type: 'light' as const, data: light };

    return null;
  }, [selectedIds, floorPlan]);

  if (!panels.propertyPanel) return null;

  return (
    <div className="w-72 bg-neutral-800 border-l border-neutral-700 flex flex-col animate-slide-in">
      <div className="flex items-center justify-between px-4 py-3 border-b border-neutral-700">
        <h2 className="text-sm font-semibold text-white">属性面板</h2>
        <button
          onClick={() => setPanel('propertyPanel', false)}
          className="text-neutral-400 hover:text-white transition-colors"
        >
          <X size={16} />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto p-4">
        {selectedObject ? (
          <div className="space-y-4">
            {selectedObject.type === 'wall' && (
              <WallProperties
                wall={selectedObject.data}
                onUpdate={(updates) => updateWall(selectedObject.data.id, updates)}
                onRemove={() => removeWall(selectedObject.data.id)}
              />
            )}
            {selectedObject.type === 'opening' && (
              <OpeningProperties
                opening={selectedObject.data}
                onUpdate={(updates) => updateOpening(selectedObject.data.id, updates)}
                onRemove={() => removeOpening(selectedObject.data.id)}
              />
            )}
            {selectedObject.type === 'furniture' && (
              <FurnitureProperties
                furniture={selectedObject.data}
                onUpdate={(updates) => updateFurniture(selectedObject.data.id, updates)}
                onRemove={() => removeFurniture(selectedObject.data.id)}
              />
            )}
            {selectedObject.type === 'light' && (
              <LightProperties light={selectedObject.data} />
            )}
          </div>
        ) : (
          <div className="text-center py-8 text-neutral-500">
            <Move size={32} className="mx-auto mb-2 opacity-50" />
            <p className="text-sm">选择一个对象查看属性</p>
          </div>
        )}

        <div className="mt-6 pt-4 border-t border-neutral-700">
          <h3 className="text-xs font-semibold text-neutral-400 uppercase mb-3">项目信息</h3>
          <div className="space-y-2 text-sm">
            <div className="flex justify-between">
              <span className="text-neutral-400">墙体数量</span>
              <span className="text-white font-mono">{floorPlan.walls.length}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-neutral-400">房间数量</span>
              <span className="text-white font-mono">{floorPlan.rooms.length}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-neutral-400">家具数量</span>
              <span className="text-white font-mono">{floorPlan.furniture.length}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-neutral-400">灯光数量</span>
              <span className="text-white font-mono">{floorPlan.lights.length}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

interface WallPropertiesProps {
  wall: Wall;
  onUpdate: (updates: Partial<Wall>) => void;
  onRemove: () => void;
}

const WallProperties: React.FC<WallPropertiesProps> = ({ wall, onUpdate, onRemove }) => {
  const length = distance(wall.start, wall.end);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-sm font-medium text-white">墙体</h3>
          <p className="text-xs text-neutral-400">ID: {wall.id.slice(0, 8)}</p>
        </div>
        <div className="flex gap-1">
          <ActionButton icon={<Copy size={14} />} label="复制" />
          <ActionButton icon={<Trash2 size={14} />} label="删除" onClick={onRemove} danger />
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <PropertyInput label="厚度 (m)" value={wall.thickness} onChange={(v) => onUpdate({ thickness: v })} step={0.05} />
        <PropertyInput label="高度 (m)" value={wall.height} onChange={(v) => onUpdate({ height: v })} step={0.1} />
      </div>

      <div className="p-3 bg-neutral-700/50 rounded">
        <div className="flex justify-between text-sm">
          <span className="text-neutral-400">长度</span>
          <span className="text-accent-secondary font-mono">{formatLength(length)}</span>
        </div>
        <div className="flex justify-between text-sm mt-1">
          <span className="text-neutral-400">类型</span>
          <span className="text-white">{wall.type === 'straight' ? '直线墙' : '弧形墙'}</span>
        </div>
      </div>

      <div>
        <label className="text-xs text-neutral-400 block mb-1">材质</label>
        <select
          value={wall.materialId}
          onChange={(e) => onUpdate({ materialId: e.target.value })}
          className="w-full px-3 py-2 bg-neutral-700 border border-neutral-600 rounded text-white text-sm focus:outline-none focus:border-accent-primary"
        >
          <option value="mat-wall-white">白色墙面</option>
          <option value="mat-wall-concrete">清水混凝土</option>
          <option value="mat-floor-wood">实木</option>
        </select>
      </div>
    </div>
  );
};

interface OpeningPropertiesProps {
  opening: Opening;
  onUpdate: (updates: Partial<Opening>) => void;
  onRemove: () => void;
}

const OpeningProperties: React.FC<OpeningPropertiesProps> = ({ opening, onUpdate, onRemove }) => {
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-sm font-medium text-white">{opening.type === 'door' ? '门' : '窗'}</h3>
          <p className="text-xs text-neutral-400">ID: {opening.id.slice(0, 8)}</p>
        </div>
        <div className="flex gap-1">
          <ActionButton icon={<Copy size={14} />} label="复制" />
          <ActionButton icon={<Trash2 size={14} />} label="删除" onClick={onRemove} danger />
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <PropertyInput label="宽度 (m)" value={opening.width} onChange={(v) => onUpdate({ width: v })} step={0.1} />
        <PropertyInput label="高度 (m)" value={opening.height} onChange={(v) => onUpdate({ height: v })} step={0.1} />
      </div>

      <PropertyInput
        label="位置 (m)"
        value={opening.positionX}
        onChange={(v) => onUpdate({ positionX: v })}
        step={0.1}
      />

      {opening.type === 'door' && (
        <PropertyInput
          label="开启角度 (°)"
          value={opening.swingAngle || 90}
          onChange={(v) => onUpdate({ swingAngle: v })}
          step={5}
          min={0}
          max={180}
        />
      )}

      {opening.type === 'window' && (
        <PropertyInput
          label="窗台高度 (m)"
          value={opening.sillHeight || 0.9}
          onChange={(v) => onUpdate({ sillHeight: v })}
          step={0.1}
        />
      )}
    </div>
  );
};

interface FurniturePropertiesProps {
  furniture: FurnitureItem;
  onUpdate: (updates: Partial<FurnitureItem>) => void;
  onRemove: () => void;
}

const FurnitureProperties: React.FC<FurniturePropertiesProps> = ({ furniture, onUpdate, onRemove }) => {
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-sm font-medium text-white">{furniture.name}</h3>
          <p className="text-xs text-neutral-400">{furniture.category}</p>
        </div>
        <div className="flex gap-1">
          <ActionButton icon={<Copy size={14} />} label="复制" />
          <ActionButton icon={<Trash2 size={14} />} label="删除" onClick={onRemove} danger />
        </div>
      </div>

      <div className="space-y-3">
        <div>
          <label className="text-xs text-neutral-400 block mb-1">位置 X (m)</label>
          <NumberInput value={furniture.position.x} onChange={(v) => onUpdate({ position: { ...furniture.position, x: v } })} />
        </div>
        <div>
          <label className="text-xs text-neutral-400 block mb-1">位置 Z (m)</label>
          <NumberInput value={furniture.position.z} onChange={(v) => onUpdate({ position: { ...furniture.position, z: v } })} />
        </div>
        <div>
          <label className="text-xs text-neutral-400 block mb-1">旋转 (°)</label>
          <div className="flex items-center gap-2">
            <NumberInput
              value={+(furniture.rotation * (180 / Math.PI)).toFixed(0)}
              onChange={(v) => onUpdate({ rotation: v * (Math.PI / 180) })}
              min={0}
              max={360}
            />
            <button
              onClick={() => onUpdate({ rotation: furniture.rotation + Math.PI / 4 })}
              className="p-2 bg-neutral-700 hover:bg-neutral-600 rounded text-neutral-300 transition-colors"
            >
              <RotateCw size={14} />
            </button>
          </div>
        </div>
        <div>
          <label className="text-xs text-neutral-400 block mb-1">缩放</label>
          <input
            type="range"
            min={0.5}
            max={2}
            step={0.1}
            value={furniture.scale}
            onChange={(e) => onUpdate({ scale: +e.target.value })}
            className="w-full accent-accent-primary"
          />
          <span className="text-xs text-neutral-400 font-mono">{furniture.scale.toFixed(1)}</span>
        </div>
      </div>
    </div>
  );
};

interface LightPropertiesProps {
  light: LightSource;
}

const LightProperties: React.FC<LightPropertiesProps> = ({ light }) => {
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-sm font-medium text-white">{light.name}</h3>
          <p className="text-xs text-neutral-400">
            {light.type === 'point' && '点光源'}
            {light.type === 'spot' && '聚光灯'}
            {light.type === 'area' && '面光源'}
            {light.type === 'ambient' && '环境光'}
          </p>
        </div>
      </div>

      <div>
        <label className="text-xs text-neutral-400 block mb-1">强度</label>
        <input
          type="range"
          min={0}
          max={3}
          step={0.1}
          value={light.intensity}
          className="w-full accent-accent-primary"
        />
        <span className="text-xs text-neutral-400 font-mono">{light.intensity.toFixed(1)}</span>
      </div>

      <div>
        <label className="text-xs text-neutral-400 block mb-1">颜色</label>
        <div className="flex items-center gap-2">
          <input
            type="color"
            value={`#${((1 << 24) + (light.color.r * 255 << 16) + (light.color.g * 255 << 8) + light.color.b * 255).toString(16).slice(1)}`}
            className="w-10 h-10 rounded cursor-pointer bg-transparent"
          />
          <span className="text-xs text-neutral-400 font-mono">
            RGB({Math.round(light.color.r * 255)}, {Math.round(light.color.g * 255)}, {Math.round(light.color.b * 255)})
          </span>
        </div>
      </div>

      <div className="flex items-center gap-2">
        <input
          type="checkbox"
          id="castShadow"
          checked={light.castShadow}
          className="accent-accent-primary"
        />
        <label htmlFor="castShadow" className="text-sm text-neutral-300">投射阴影</label>
      </div>
    </div>
  );
};

interface PropertyInputProps {
  label: string;
  value: number;
  onChange: (value: number) => void;
  step?: number;
  min?: number;
  max?: number;
}

const PropertyInput: React.FC<PropertyInputProps> = ({ label, value, onChange, step = 0.1, min, max }) => (
  <div>
    <label className="text-xs text-neutral-400 block mb-1">{label}</label>
    <NumberInput value={value} onChange={onChange} step={step} min={min} max={max} />
  </div>
);

const NumberInput: React.FC<{ value: number; onChange: (v: number) => void; step?: number; min?: number; max?: number }> = ({
  value,
  onChange,
  step = 0.1,
  min,
  max,
}) => (
  <input
    type="number"
    value={value}
    step={step}
    min={min}
    max={max}
    onChange={(e) => onChange(+e.target.value)}
    className="w-full px-3 py-2 bg-neutral-700 border border-neutral-600 rounded text-white text-sm font-mono focus:outline-none focus:border-accent-primary"
  />
);

interface ActionButtonProps {
  icon: React.ReactNode;
  label: string;
  onClick?: () => void;
  danger?: boolean;
}

const ActionButton: React.FC<ActionButtonProps> = ({ icon, label, onClick, danger }) => (
  <button
    onClick={onClick}
    className={`p-2 rounded transition-colors ${
      danger ? 'text-red-400 hover:text-red-300 hover:bg-red-500/10' : 'text-neutral-400 hover:text-white hover:bg-neutral-700'
    }`}
    title={label}
  >
    {icon}
  </button>
);
