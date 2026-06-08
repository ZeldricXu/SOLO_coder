import React from 'react';
import { Sun, Eye, Sliders, Power, ChevronDown, ChevronUp } from 'lucide-react';
import { useUIStore } from '@/store/useUIStore';
import { GI_QUALITY_PRESETS } from '@/types/gi';
import type { GIRenderSettings } from '@/types/gi';

interface GIConfigPanelProps {
  settings: GIRenderSettings;
  onChange: (settings: Partial<GIRenderSettings>) => void;
}

export const GIConfigPanel: React.FC<GIConfigPanelProps> = ({ settings, onChange }) => {
  const { panels, setPanel } = useUIStore();
  const [expanded, setExpanded] = React.useState(true);

  const handlePresetChange = (preset: keyof typeof GI_QUALITY_PRESETS) => {
    const presetConfig = GI_QUALITY_PRESETS[preset];
    onChange({
      enabled: preset !== 'off',
      quality: preset,
      hemisphereLight: {
        ...settings.hemisphereLight,
        enabled: presetConfig.hemisphereLight,
      },
      ssao: {
        ...settings.ssao,
        enabled: presetConfig.ssao,
        radius: presetConfig.ssaoRadius,
        intensity: presetConfig.ssaoIntensity,
      },
    });
  };

  return (
    <div className="bg-neutral-800 rounded-lg border border-neutral-700 overflow-hidden">
      <button
        onClick={() => setPanel('giPanel', !panels.giPanel)}
        className="w-full flex items-center justify-between px-4 py-3 hover:bg-neutral-700/50 transition-colors"
      >
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 rounded bg-accent-primary/20 flex items-center justify-center">
            <Sun size={16} className="text-accent-primary" />
          </div>
          <div className="text-left">
            <h3 className="text-sm font-semibold text-white">全局光照</h3>
            <p className="text-xs text-neutral-400">
              {GI_QUALITY_PRESETS[settings.quality].label} · {settings.enabled ? '已启用' : '已关闭'}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={(e) => {
              e.stopPropagation();
              onChange({ enabled: !settings.enabled });
            }}
            className={`p-1.5 rounded transition-colors ${
              settings.enabled ? 'bg-accent-primary text-white' : 'bg-neutral-700 text-neutral-400'
            }`}
          >
            <Power size={14} />
          </button>
          {expanded ? <ChevronUp size={16} className="text-neutral-400" /> : <ChevronDown size={16} className="text-neutral-400" />}
        </div>
      </button>

      {expanded && settings.enabled && (
        <div className="px-4 pb-4 space-y-4 border-t border-neutral-700 pt-3">
          <div>
            <label className="text-xs text-neutral-400 mb-2 block">质量预设</label>
            <div className="grid grid-cols-4 gap-1">
              {(Object.keys(GI_QUALITY_PRESETS) as Array<keyof typeof GI_QUALITY_PRESETS>).map(
                (preset) => (
                  <button
                    key={preset}
                    onClick={() => handlePresetChange(preset)}
                    className={`px-2 py-1.5 rounded text-xs transition-colors ${
                      settings.quality === preset
                        ? 'bg-accent-primary text-white'
                        : 'bg-neutral-700 text-neutral-300 hover:bg-neutral-600'
                    }`}
                  >
                    {GI_QUALITY_PRESETS[preset].label}
                  </button>
                )
              )}
            </div>
          </div>

          <div>
            <div className="flex items-center justify-between mb-2">
              <label className="text-xs text-neutral-400 flex items-center gap-1">
                <Sun size={12} />
                半球光强度
              </label>
              <span className="text-xs text-neutral-300">{settings.hemisphereLight.intensity.toFixed(2)}</span>
            </div>
            <input
              type="range"
              min="0"
              max="2"
              step="0.05"
              value={settings.hemisphereLight.intensity}
              onChange={(e) =>
                onChange({
                  hemisphereLight: {
                    ...settings.hemisphereLight,
                    intensity: parseFloat(e.target.value),
                  },
                })
              }
              className="w-full accent-accent-primary"
            />
          </div>

          <div>
            <div className="flex items-center justify-between mb-2">
              <label className="text-xs text-neutral-400 flex items-center gap-1">
                <Eye size={12} />
                SSAO 强度
              </label>
              <span className="text-xs text-neutral-300">{settings.ssao.intensity.toFixed(2)}</span>
            </div>
            <input
              type="range"
              min="0"
              max="1.5"
              step="0.05"
              value={settings.ssao.intensity}
              onChange={(e) =>
                onChange({
                  ssao: {
                    ...settings.ssao,
                    intensity: parseFloat(e.target.value),
                  },
                })
              }
              className="w-full accent-accent-primary"
            />
          </div>

          <div>
            <div className="flex items-center justify-between mb-2">
              <label className="text-xs text-neutral-400 flex items-center gap-1">
                <Sliders size={12} />
                SSAO 采样半径
              </label>
              <span className="text-xs text-neutral-300">{settings.ssao.radius.toFixed(2)}</span>
            </div>
            <input
              type="range"
              min="0.1"
              max="2"
              step="0.05"
              value={settings.ssao.radius}
              onChange={(e) =>
                onChange({
                  ssao: {
                    ...settings.ssao,
                    radius: parseFloat(e.target.value),
                  },
                })
              }
              className="w-full accent-accent-primary"
            />
          </div>

          <div>
            <div className="flex items-center justify-between mb-2">
              <label className="text-xs text-neutral-400">场景曝光</label>
              <span className="text-xs text-neutral-300">{settings.exposure.toFixed(2)}</span>
            </div>
            <input
              type="range"
              min="0.3"
              max="2"
              step="0.05"
              value={settings.exposure}
              onChange={(e) =>
                onChange({ exposure: parseFloat(e.target.value) })
              }
              className="w-full accent-accent-primary"
            />
          </div>
        </div>
      )}
    </div>
  );
};
