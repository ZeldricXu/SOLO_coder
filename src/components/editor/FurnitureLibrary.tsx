import React, { useState } from 'react';
import { X, Search, Sofa, Bed, Bath, ChefHat, Tv, Lamp, Armchair, Table } from 'lucide-react';
import { useUIStore } from '@/store/useUIStore';
import { useFloorPlanStore } from '@/store/useFloorPlanStore';
import { FURNITURE_CATEGORIES, FURNITURE_PRESETS } from '@/types/materials';

type CategoryId = 'seating' | 'bedroom' | 'bathroom' | 'kitchen' | 'living' | 'lighting' | 'office' | 'dining';

const categoryIcons: Record<CategoryId, React.ReactNode> = {
  seating: <Sofa size={18} />,
  bedroom: <Bed size={18} />,
  bathroom: <Bath size={18} />,
  kitchen: <ChefHat size={18} />,
  living: <Tv size={18} />,
  lighting: <Lamp size={18} />,
  office: <Armchair size={18} />,
  dining: <Table size={18} />,
};

export const FurnitureLibrary: React.FC = () => {
  const { panels, setPanel } = useUIStore();
  const { addFurniture } = useFloorPlanStore();
  const [activeCategory, setActiveCategory] = useState<CategoryId | 'all'>('all');
  const [searchQuery, setSearchQuery] = useState('');

  const filteredFurniture = FURNITURE_PRESETS.filter((item) => {
    const matchesCategory = activeCategory === 'all' || item.category === activeCategory;
    const matchesSearch = item.name.toLowerCase().includes(searchQuery.toLowerCase());
    return matchesCategory && matchesSearch;
  });

  const handleDragStart = (e: React.DragEvent, furnitureId: string) => {
    e.dataTransfer.setData('furnitureId', furnitureId);
    e.dataTransfer.effectAllowed = 'copy';
  };

  const handleAddFurniture = (furnitureId: string) => {
    const preset = FURNITURE_PRESETS.find((f) => f.id === furnitureId);
    if (preset) {
      addFurniture({
        modelId: preset.id,
        name: preset.name,
        category: preset.category,
        position: { x: 0, y: 0, z: 0 },
        rotation: 0,
        scale: 1,
      });
    }
  };

  if (!panels.furnitureLibrary) return null;

  return (
    <div className="w-72 bg-neutral-800 border-l border-neutral-700 flex flex-col animate-slide-in">
      <div className="flex items-center justify-between px-4 py-3 border-b border-neutral-700">
        <h2 className="text-sm font-semibold text-white">家具库</h2>
        <button
          onClick={() => setPanel('furnitureLibrary', false)}
          className="text-neutral-400 hover:text-white transition-colors"
        >
          <X size={16} />
        </button>
      </div>

      <div className="p-3 border-b border-neutral-700">
        <div className="relative">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-neutral-400" />
          <input
            type="text"
            placeholder="搜索家具..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full pl-9 pr-3 py-2 bg-neutral-700 border border-neutral-600 rounded text-white text-sm placeholder-neutral-500 focus:outline-none focus:border-accent-primary"
          />
        </div>
      </div>

      <div className="flex flex-wrap gap-1 p-3 border-b border-neutral-700">
        <button
          onClick={() => setActiveCategory('all')}
          className={`px-3 py-1 rounded text-xs transition-colors ${
            activeCategory === 'all'
              ? 'bg-accent-primary text-white'
              : 'bg-neutral-700 text-neutral-300 hover:bg-neutral-600'
          }`}
        >
          全部
        </button>
        {FURNITURE_CATEGORIES.map((cat) => (
          <button
            key={cat.id}
            onClick={() => setActiveCategory(cat.id as CategoryId)}
            className={`px-3 py-1 rounded text-xs transition-colors flex items-center gap-1 ${
              activeCategory === cat.id
                ? 'bg-accent-primary text-white'
                : 'bg-neutral-700 text-neutral-300 hover:bg-neutral-600'
            }`}
          >
            {categoryIcons[cat.id as CategoryId]}
            {cat.name}
          </button>
        ))}
      </div>

      <div className="flex-1 overflow-y-auto p-3">
        <div className="grid grid-cols-2 gap-2">
          {filteredFurniture.map((item) => (
            <div
              key={item.id}
              draggable
              onDragStart={(e) => handleDragStart(e, item.id)}
              onClick={() => handleAddFurniture(item.id)}
              className="group bg-neutral-700/50 rounded p-2 cursor-grab hover:bg-neutral-700 hover:border-accent-primary border border-transparent transition-all"
            >
              <div className="aspect-square bg-neutral-800 rounded mb-2 flex items-center justify-center overflow-hidden">
                <div className="text-3xl">{item.thumbnail || '🪑'}</div>
              </div>
              <p className="text-xs text-white truncate">{item.name}</p>
              <p className="text-xs text-neutral-500">{item.dimensions}</p>
            </div>
          ))}
        </div>

        {filteredFurniture.length === 0 && (
          <div className="text-center py-8 text-neutral-500">
            <p className="text-sm">未找到匹配的家具</p>
          </div>
        )}
      </div>
    </div>
  );
};
