import { useMolStore } from '@/store/useMolStore';
import type { AnnotationOpacityState } from '@/modules/annotation-layer';

interface ToggleConfig {
  label: string;
  value: boolean;
  onChange: (v: boolean) => void;
  opacityKey: keyof AnnotationOpacityState;
  description?: string;
}

const panelClass =
  'bg-[rgba(20,20,40,0.85)] backdrop-blur-md border border-white/10 rounded-lg text-white/90 font-["DM_Sans"]';

const sliderTrackClass = 'w-full h-1.5 rounded-full bg-white/10 appearance-none cursor-pointer';
const sliderThumbClass = '[&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:w-3.5 [&::-webkit-slider-thumb]:h-3.5 [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-[#00d4aa] [&::-webkit-slider-thumb]:cursor-pointer [&::-webkit-slider-thumb]:shadow-lg [&::-webkit-slider-thumb]:shadow-[#00d4aa]/30 [&::-moz-range-thumb]:w-3.5 [&::-moz-range-thumb]:h-3.5 [&::-moz-range-thumb]:rounded-full [&::-moz-range-thumb]:bg-[#00d4aa] [&::-moz-range-thumb]:border-0';

const colorModes: Array<{ value: 'element' | 'bFactor' | 'chain' | 'residue'; label: string }> = [
  { value: 'element', label: 'Element' },
  { value: 'bFactor', label: 'B-Factor' },
  { value: 'chain', label: 'Chain' },
  { value: 'residue', label: 'Residue' },
];

export default function AnnotationPanel() {
  const {
    residueLabelsVisible,
    backboneRibbonVisible,
    hBondIndicatorsVisible,
    partialChargesVisible,
    bFactorHeatmapVisible,
    ligandHBondNetworkVisible,
    setResidueLabelsVisible,
    setBackboneRibbonVisible,
    setHBondIndicatorsVisible,
    setPartialChargesVisible,
    setBFactorHeatmapVisible,
    setLigandHBondNetworkVisible,
    annotationOpacities,
    setAnnotationOpacity,
    colorMode,
    setColorMode,
    chainIsolation,
    setChainIsolation,
  } = useMolStore();

  const toggles: ToggleConfig[] = [
    { label: 'Residue Labels', value: residueLabelsVisible, onChange: setResidueLabelsVisible, opacityKey: 'residueLabels', description: 'Residue name and number' },
    { label: 'Backbone Ribbon', value: backboneRibbonVisible, onChange: setBackboneRibbonVisible, opacityKey: 'backboneRibbon', description: 'Secondary structure trace' },
    { label: 'H-Bond Indicators', value: hBondIndicatorsVisible, onChange: setHBondIndicatorsVisible, opacityKey: 'hBondIndicators', description: 'Donor-acceptor pairs' },
    { label: 'Partial Charges', value: partialChargesVisible, onChange: setPartialChargesVisible, opacityKey: 'partialCharges', description: 'Estimated atomic charges' },
    { label: 'B-Factor Heatmap', value: bFactorHeatmapVisible, onChange: setBFactorHeatmapVisible, opacityKey: 'bFactorHeatmap', description: 'Thermal mobility coloring' },
    { label: 'Ligand H-Bond Network', value: ligandHBondNetworkVisible, onChange: setLigandHBondNetworkVisible, opacityKey: 'ligandHBondNetwork', description: 'Ligand-protein interactions' },
  ];

  return (
    <div className={`${panelClass} fixed left-4 top-[340px] z-20 w-72 p-4 space-y-4`}>
      <h2 className="text-sm font-semibold tracking-wide uppercase">Annotations</h2>

      <div className="space-y-3">
        {toggles.map(({ label, value, onChange, opacityKey, description }) => (
          <div key={label} className="space-y-1.5">
            <label className="flex items-center gap-3 cursor-pointer group">
              <span className="relative flex items-center justify-center w-4 h-4 shrink-0">
                <input
                  type="checkbox"
                  checked={value}
                  onChange={(e) => onChange(e.target.checked)}
                  className="sr-only peer"
                />
                <span className="absolute inset-0 rounded border border-white/20 bg-white/5 transition-colors peer-checked:bg-[#00d4aa]/20 peer-checked:border-[#00d4aa]" />
                <svg
                  className={`w-2.5 h-2.5 text-[#00d4aa] transition-opacity ${
                    value ? 'opacity-100' : 'opacity-0'
                  }`}
                  fill="none"
                  viewBox="0 0 24 24"
                  stroke="currentColor"
                  strokeWidth={3}
                >
                  <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                </svg>
              </span>
              <div className="flex-1">
                <span className="text-sm text-white/80 group-hover:text-white/90 transition-colors">
                  {label}
                </span>
                {description && (
                  <p className="text-[10px] text-white/40">{description}</p>
                )}
              </div>
              <span className="text-[10px] text-white/50 font-mono w-10 text-right">
                {Math.round(annotationOpacities[opacityKey] * 100)}%
              </span>
            </label>
            <input
              type="range"
              min="0"
              max="1"
              step="0.05"
              value={annotationOpacities[opacityKey]}
              onChange={(e) => setAnnotationOpacity(opacityKey, parseFloat(e.target.value))}
              disabled={!value}
              className={`${sliderTrackClass} ${sliderThumbClass} ${!value ? 'opacity-30 cursor-not-allowed' : ''}`}
            />
          </div>
        ))}
      </div>

      <div className="border-t border-white/10 pt-4">
        <h3 className="text-xs font-semibold tracking-wide uppercase mb-3 text-white/60">Color Mode</h3>
        <div className="grid grid-cols-2 gap-2">
          {colorModes.map(({ value, label }) => (
            <button
              key={value}
              onClick={() => setColorMode(value)}
              className={`px-3 py-2 rounded text-xs font-medium transition-all ${
                colorMode === value
                  ? 'bg-[#00d4aa] text-[#1a1a2e] shadow-lg shadow-[#00d4aa]/20'
                  : 'bg-white/5 text-white/70 hover:bg-white/10 border border-white/10'
              }`}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      <div className="border-t border-white/10 pt-4">
        <h3 className="text-xs font-semibold tracking-wide uppercase mb-3 text-white/60">Chain Isolation</h3>
        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-sm text-white/70">Fade Opacity</span>
            <span className="text-[10px] text-white/50 font-mono">
              {Math.round(chainIsolation.fadeOpacity * 100)}%
            </span>
          </div>
          <input
            type="range"
            min="0"
            max="0.5"
            step="0.05"
            value={chainIsolation.fadeOpacity}
            onChange={(e) => setChainIsolation({ fadeOpacity: parseFloat(e.target.value) })}
            className={`${sliderTrackClass} ${sliderThumbClass}`}
          />
          <p className="text-[10px] text-white/40">
            Double-click a chain to isolate. Double-click again to exit.
          </p>
        </div>
      </div>
    </div>
  );
}
