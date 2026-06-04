import { useState } from 'react';
import { Ruler, Triangle, RotateCcw, X, ChevronRight } from 'lucide-react';
import { useMolStore } from '@/store/useMolStore';
import type { MeasurementType } from '@/modules/measurement-tools';

type ToolDef = { type: MeasurementType; icon: typeof Ruler; label: string };

const tools: ToolDef[] = [
  { type: 'distance', icon: Ruler, label: 'Distance' },
  { type: 'angle', icon: Triangle, label: 'Angle' },
  { type: 'dihedral', icon: RotateCcw, label: 'Dihedral' },
];

const unitMap: Record<MeasurementType, string> = {
  distance: 'Å',
  angle: '°',
  dihedral: '°',
};

const panelClass =
  'bg-[rgba(20,20,40,0.85)] backdrop-blur-md border border-white/10 rounded-lg text-white/90 font-["DM_Sans"]';

export default function MeasurementPanel() {
  const { currentTool, setCurrentTool, selectedAtoms, measurements, removeMeasurement } =
    useMolStore();

  const [collapsed, setCollapsed] = useState(false);

  const toggleTool = (type: MeasurementType) => {
    setCurrentTool(currentTool === type ? null : type);
  };

  if (collapsed) {
    return (
      <div className={`${panelClass} fixed right-4 top-20 z-20 p-2`}>
        <button
          onClick={() => setCollapsed(false)}
          className="p-1.5 hover:bg-white/10 rounded transition-colors"
        >
          <ChevronRight size={18} className="rotate-180" />
        </button>
      </div>
    );
  }

  return (
    <div className={`${panelClass} fixed right-4 top-20 z-20 w-64 p-4 space-y-3`}>
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold tracking-wide uppercase">Measure</h2>
        <button
          onClick={() => setCollapsed(true)}
          className="p-1 hover:bg-white/10 rounded transition-colors"
        >
          <X size={14} />
        </button>
      </div>

      <div className="flex gap-1">
        {tools.map(({ type, icon: Icon, label }) => {
          const active = currentTool === type;
          return (
            <button
              key={type}
              onClick={() => toggleTool(type)}
              title={label}
              className={`flex-1 flex flex-col items-center gap-0.5 py-2 rounded-md text-xs transition-colors ${
                active
                  ? 'bg-[#00d4aa]/20 text-[#00d4aa]'
                  : 'text-white/60 hover:bg-white/10'
              }`}
            >
              <Icon size={16} />
              {label}
            </button>
          );
        })}
      </div>

      {currentTool && selectedAtoms.length > 0 && (
        <div className="text-xs text-white/60">
          Picked atoms: {selectedAtoms.join(', ')}
        </div>
      )}

      {measurements.length > 0 && (
        <div className="space-y-1.5 max-h-48 overflow-y-auto">
          {measurements.map((m, i) => (
            <div
              key={i}
              className="flex items-center justify-between bg-white/5 rounded px-2 py-1.5 text-xs"
            >
              <div className="flex flex-col gap-0.5 min-w-0 flex-1">
                <span className="text-white/60 capitalize">{m.type}</span>
                <span className="text-white/40 text-[10px]">
                  [{m.atomIndices.join(', ')}]
                </span>
              </div>
              <span className="text-white/90 font-medium mx-2">
                {m.value.toFixed(2)} {unitMap[m.type]}
              </span>
              <button
                onClick={() => removeMeasurement(i)}
                className="p-0.5 hover:bg-white/10 rounded shrink-0 text-white/40 hover:text-white/80 transition-colors"
              >
                <X size={12} />
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
