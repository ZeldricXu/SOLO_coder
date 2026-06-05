import React, { useState, useRef } from 'react';
import { X, Camera, Download, Settings, Sun, Moon, Zap, Image, Clock } from 'lucide-react';
import { useUIStore } from '@/store/useUIStore';

type RenderQuality = 'preview' | 'medium' | 'high' | 'ultra';
type RenderPreset = 'daylight' | 'sunset' | 'night' | 'studio';

interface RenderSettings {
  quality: RenderQuality;
  resolution: { width: number; height: number };
  samples: number;
  bounces: number;
  exposure: number;
  preset: RenderPreset;
  denoise: boolean;
  transparent: boolean;
}

const qualityPresets: Record<RenderQuality, { label: string; samples: number; bounces: number }> = {
  preview: { label: '预览', samples: 16, bounces: 2 },
  medium: { label: '中等', samples: 64, bounces: 4 },
  high: { label: '高质量', samples: 256, bounces: 8 },
  ultra: { label: '超高清', samples: 1024, bounces: 16 },
};

const lightingPresets: Record<RenderPreset, { label: string; icon: React.ReactNode; exposure: number }> = {
  daylight: { label: '日间', icon: <Sun size={16} />, exposure: 1.0 },
  sunset: { label: '日落', icon: <Sun size={16} />, exposure: 0.8 },
  night: { label: '夜间', icon: <Moon size={16} />, exposure: 0.5 },
  studio: { label: '影棚', icon: <Zap size={16} />, exposure: 1.2 },
};

const resolutionOptions = [
  { label: '1280 × 720 (HD)', width: 1280, height: 720 },
  { label: '1920 × 1080 (Full HD)', width: 1920, height: 1080 },
  { label: '2560 × 1440 (2K)', width: 2560, height: 1440 },
  { label: '3840 × 2160 (4K)', width: 3840, height: 2160 },
];

export const RenderDialog: React.FC = () => {
  const { panels, setPanel } = useUIStore();
  const [settings, setSettings] = useState<RenderSettings>({
    quality: 'high',
    resolution: { width: 1920, height: 1080 },
    samples: 256,
    bounces: 8,
    exposure: 1.0,
    preset: 'daylight',
    denoise: true,
    transparent: false,
  });
  const [isRendering, setIsRendering] = useState(false);
  const [progress, setProgress] = useState(0);
  const [renderedImage, setRenderedImage] = useState<string | null>(null);
  const progressIntervalRef = useRef<NodeJS.Timeout | null>(null);

  const handleQualityChange = (quality: RenderQuality) => {
    const preset = qualityPresets[quality];
    setSettings((s) => ({
      ...s,
      quality,
      samples: preset.samples,
      bounces: preset.bounces,
    }));
  };

  const handlePresetChange = (preset: RenderPreset) => {
    setSettings((s) => ({
      ...s,
      preset,
      exposure: lightingPresets[preset].exposure,
    }));
  };

  const handleStartRender = () => {
    setIsRendering(true);
    setProgress(0);
    setRenderedImage(null);

    progressIntervalRef.current = setInterval(() => {
      setProgress((p) => {
        const next = p + Math.random() * 5;
        if (next >= 100) {
          if (progressIntervalRef.current) {
            clearInterval(progressIntervalRef.current);
          }
          setIsRendering(false);
          setRenderedImage(
            'data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iMTkyMCIgaGVpZ2h0PSIxMDgwIiB2aWV3Qm94PSIwIDAgMTkyMCAxMDgwIiBmaWxsPSJub25lIiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPgo8cmVjdCB3aWR0aD0iMTkyMCIgaGVpZ2h0PSIxMDgwIiBmaWxsPSIjMWExZjJlIi8+Cjx0ZXh0IHg9Ijk2MCIgeT0iNTQwIiB0ZXh0LWFuY2hvcj0ibWlkZGxlIiBmaWxsPSIjZmY2YjM1IiBmb250LWZhbWlseT0iU3BhY2UgR3JvdGVzayIgZm9udC1zaXplPSI0OCIgZm9udC13ZWlnaHQ9IjcwMCI+QXJjaFBsYW4gU3R1ZGlvPC90ZXh0Pgo8dGV4dCB4PSI5NjAiIHk9IjYwMCIgdGV4dC1hbmNob3I9Im1pZGRsZSIgZmlsbD0iIzAwZDRmZiIgZm9udC1mYW1pbHk9IkludGVyIiBmb250LXNpemU9IjI0Ij7pu5DmnIfooYzmgLvph4/np5jmnIc8L3RleHQ+Cjwvc3ZnPg=='
          );
          return 100;
        }
        return next;
      });
    }, 100);
  };

  const handleDownload = () => {
    if (!renderedImage) return;
    const link = document.createElement('a');
    link.href = renderedImage;
    link.download = `render_${Date.now()}.png`;
    link.click();
  };

  const handleClose = () => {
    if (progressIntervalRef.current) {
      clearInterval(progressIntervalRef.current);
    }
    setPanel('renderDialog', false);
  };

  if (!panels.renderDialog) return null;

  const estimatedTime = (settings.samples * settings.bounces) / 100;

  return (
    <div className="fixed inset-0 bg-black/70 backdrop-blur-sm flex items-center justify-center z-50 animate-fade-in">
      <div className="bg-neutral-800 rounded-lg w-[900px] max-h-[90vh] overflow-hidden shadow-2xl">
        <div className="flex items-center justify-between px-6 py-4 border-b border-neutral-700">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-gradient-to-br from-accent-primary to-accent-secondary flex items-center justify-center">
              <Camera size={20} className="text-white" />
            </div>
            <div>
              <h2 className="text-lg font-semibold text-white">路径追踪渲染</h2>
              <p className="text-xs text-neutral-400">生成高质量渲染图像</p>
            </div>
          </div>
          <button
            onClick={handleClose}
            className="text-neutral-400 hover:text-white transition-colors"
          >
            <X size={20} />
          </button>
        </div>

        <div className="flex h-[600px]">
          <div className="w-80 border-r border-neutral-700 p-4 overflow-y-auto">
            <div className="space-y-6">
              <div>
                <h3 className="text-xs font-semibold text-neutral-400 uppercase mb-3 flex items-center gap-2">
                  <Settings size={12} />
                  渲染质量
                </h3>
                <div className="grid grid-cols-2 gap-2">
                  {(Object.keys(qualityPresets) as RenderQuality[]).map((q) => (
                    <button
                      key={q}
                      onClick={() => handleQualityChange(q)}
                      className={`px-3 py-2 rounded text-sm transition-colors ${
                        settings.quality === q
                          ? 'bg-accent-primary text-white'
                          : 'bg-neutral-700 text-neutral-300 hover:bg-neutral-600'
                      }`}
                    >
                      <div className="font-medium">{qualityPresets[q].label}</div>
                      <div className="text-xs opacity-70">{qualityPresets[q].samples} 采样</div>
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <h3 className="text-xs font-semibold text-neutral-400 uppercase mb-3 flex items-center gap-2">
                  <Image size={12} />
                  输出分辨率
                </h3>
                <div className="space-y-2">
                  {resolutionOptions.map((res) => (
                    <button
                      key={res.label}
                      onClick={() => setSettings((s) => ({ ...s, resolution: { width: res.width, height: res.height } }))}
                      className={`w-full px-3 py-2 rounded text-sm text-left transition-colors ${
                        settings.resolution.width === res.width
                          ? 'bg-accent-primary/20 text-accent-primary border border-accent-primary'
                          : 'bg-neutral-700 text-neutral-300 hover:bg-neutral-600 border border-transparent'
                      }`}
                    >
                      {res.label}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <h3 className="text-xs font-semibold text-neutral-400 uppercase mb-3">光照预设</h3>
                <div className="grid grid-cols-2 gap-2">
                  {(Object.keys(lightingPresets) as RenderPreset[]).map((p) => (
                    <button
                      key={p}
                      onClick={() => handlePresetChange(p)}
                      className={`px-3 py-2 rounded text-sm flex items-center gap-2 transition-colors ${
                        settings.preset === p
                          ? 'bg-accent-primary text-white'
                          : 'bg-neutral-700 text-neutral-300 hover:bg-neutral-600'
                      }`}
                    >
                      {lightingPresets[p].icon}
                      {lightingPresets[p].label}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <h3 className="text-xs font-semibold text-neutral-400 uppercase mb-3">高级设置</h3>
                <div className="space-y-3">
                  <div>
                    <label className="text-xs text-neutral-400 block mb-1">
                      曝光: {settings.exposure.toFixed(1)}
                    </label>
                    <input
                      type="range"
                      min={0.1}
                      max={3}
                      step={0.1}
                      value={settings.exposure}
                      onChange={(e) => setSettings((s) => ({ ...s, exposure: +e.target.value }))}
                      className="w-full accent-accent-primary"
                    />
                  </div>

                  <div>
                    <label className="text-xs text-neutral-400 block mb-1">
                      采样数: {settings.samples}
                    </label>
                    <input
                      type="range"
                      min={16}
                      max={2048}
                      step={16}
                      value={settings.samples}
                      onChange={(e) => setSettings((s) => ({ ...s, samples: +e.target.value }))}
                      className="w-full accent-accent-primary"
                    />
                  </div>

                  <div>
                    <label className="text-xs text-neutral-400 block mb-1">
                      反弹次数: {settings.bounces}
                    </label>
                    <input
                      type="range"
                      min={1}
                      max={32}
                      step={1}
                      value={settings.bounces}
                      onChange={(e) => setSettings((s) => ({ ...s, bounces: +e.target.value }))}
                      className="w-full accent-accent-primary"
                    />
                  </div>

                  <div className="flex items-center justify-between">
                    <span className="text-sm text-neutral-300">降噪处理</span>
                    <input
                      type="checkbox"
                      checked={settings.denoise}
                      onChange={(e) => setSettings((s) => ({ ...s, denoise: e.target.checked }))}
                      className="accent-accent-primary w-4 h-4"
                    />
                  </div>

                  <div className="flex items-center justify-between">
                    <span className="text-sm text-neutral-300">透明背景</span>
                    <input
                      type="checkbox"
                      checked={settings.transparent}
                      onChange={(e) => setSettings((s) => ({ ...s, transparent: e.target.checked }))}
                      className="accent-accent-primary w-4 h-4"
                    />
                  </div>
                </div>
              </div>

              <div className="p-3 bg-neutral-700/50 rounded-lg">
                <div className="flex items-center gap-2 text-sm text-neutral-300 mb-2">
                  <Clock size={14} className="text-accent-secondary" />
                  <span>预计渲染时间</span>
                </div>
                <p className="text-lg font-mono text-white">~{estimatedTime.toFixed(1)} 秒</p>
              </div>
            </div>
          </div>

          <div className="flex-1 flex flex-col p-4">
            <div className="flex-1 bg-neutral-900 rounded-lg overflow-hidden flex items-center justify-center relative">
              {renderedImage ? (
                <img
                  src={renderedImage}
                  alt="渲染结果"
                  className="max-w-full max-h-full object-contain"
                />
              ) : isRendering ? (
                <div className="text-center">
                  <div className="w-16 h-16 border-4 border-neutral-700 border-t-accent-primary rounded-full animate-spin mx-auto mb-4" />
                  <p className="text-white text-sm mb-2">正在渲染...</p>
                  <p className="text-neutral-400 text-xs mb-4">
                    采样 {Math.floor((settings.samples * progress) / 100)} / {settings.samples}
                  </p>
                  <div className="w-64 h-2 bg-neutral-700 rounded-full overflow-hidden mx-auto">
                    <div
                      className="h-full bg-gradient-to-r from-accent-primary to-accent-secondary transition-all duration-200"
                      style={{ width: `${progress}%` }}
                    />
                  </div>
                  <p className="text-neutral-500 text-xs mt-2">{progress.toFixed(0)}%</p>
                </div>
              ) : (
                <div className="text-center text-neutral-500">
                  <Camera size={48} className="mx-auto mb-3 opacity-50" />
                  <p className="text-sm">点击下方按钮开始渲染</p>
                  <p className="text-xs mt-1">分辨率: {settings.resolution.width} × {settings.resolution.height}</p>
                </div>
              )}
            </div>

            <div className="flex items-center justify-between mt-4">
              <div className="text-sm text-neutral-400">
                {renderedImage ? (
                  <span className="text-green-400">✓ 渲染完成</span>
                ) : isRendering ? (
                  <span>正在使用路径追踪算法...</span>
                ) : (
                  <span>准备就绪</span>
                )}
              </div>
              <div className="flex gap-2">
                {renderedImage && (
                  <button
                    onClick={handleDownload}
                    className="flex items-center gap-2 px-4 py-2 bg-accent-secondary text-white rounded-lg hover:bg-accent-secondary/80 transition-colors"
                  >
                    <Download size={16} />
                    下载图片
                  </button>
                )}
                <button
                  onClick={handleStartRender}
                  disabled={isRendering}
                  className="flex items-center gap-2 px-6 py-2 bg-accent-primary text-white rounded-lg hover:bg-accent-hover transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  <Camera size={16} />
                  {isRendering ? '渲染中...' : '开始渲染'}
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
