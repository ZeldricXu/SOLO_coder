import React, { useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Undo2,
  Redo2,
  Save,
  Download,
  Upload,
  Eye,
  Box,
  Layers,
  Grid3X3,
  Ruler,
  MessageSquare,
  Settings,
  Camera,
  FileJson,
  Home,
  FileText,
} from 'lucide-react';
import { useFloorPlanStore } from '@/store/useFloorPlanStore';
import { useUIStore } from '@/store/useUIStore';
import type { ViewMode } from '@/types/floorplan';
import { downloadJSON, downloadSVG, importFromFile } from '@/utils/io/importExport';
import { saveDraft, generateThumbnail } from '@/utils/storage/indexedDB';

interface ToolbarProps {
  draftId: string;
}

export const Toolbar: React.FC<ToolbarProps> = ({ draftId }) => {
  const navigate = useNavigate();
  const { undo, redo, viewMode, setViewMode, floorPlan, setFloorPlan } = useFloorPlanStore();
  const { togglePanel, showGrid, setShowGrid, showHelpers, setShowHelpers, addNotification } = useUIStore();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const viewModes: { mode: ViewMode; label: string; icon: React.ReactNode }[] = [
    { mode: '2d', label: '2D', icon: <Layers size={16} /> },
    { mode: '3d', label: '3D', icon: <Box size={16} /> },
    { mode: 'split', label: '分屏', icon: <Eye size={16} /> },
  ];

  const handleSave = async () => {
    try {
      const thumbnail = generateThumbnail(floorPlan);
      await saveDraft(draftId, floorPlan, floorPlan.name);
      if (thumbnail) {
        useFloorPlanStore.setState((state) => ({
          floorPlan: { ...state.floorPlan, thumbnail },
        }));
      }
      addNotification({ type: 'success', message: '项目已保存' });
    } catch (error) {
      addNotification({ type: 'error', message: '保存失败' });
    }
  };

  const handleExportJSON = () => {
    downloadJSON(floorPlan, `${floorPlan.name || 'floorplan'}.json`);
    addNotification({ type: 'success', message: 'JSON文件已导出' });
  };

  const handleExportSVG = () => {
    downloadSVG(floorPlan);
    addNotification({ type: 'success', message: 'SVG文件已导出' });
  };

  const handleImportFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    try {
      const importedFloorPlan = await importFromFile(file);
      setFloorPlan(importedFloorPlan);
      addNotification({ type: 'success', message: `${file.name} 导入成功` });
    } catch (error) {
      addNotification({
        type: 'error',
        message: error instanceof Error ? error.message : '导入失败',
      });
    } finally {
      e.target.value = '';
    }
  };

  return (
    <div className="h-14 bg-neutral-800 border-b border-neutral-700 flex items-center px-4 gap-2 select-none">
      <button
        onClick={() => navigate('/')}
        className="flex items-center gap-2 px-2 py-1.5 rounded text-neutral-400 hover:text-white hover:bg-neutral-700 transition-colors"
        title="返回首页"
      >
        <Home size={16} />
      </button>

      <div className="flex items-center gap-2 mr-4">
        <div className="w-8 h-8 rounded bg-accent-primary flex items-center justify-center">
          <Box size={18} className="text-white" />
        </div>
        <div>
          <h1 className="text-sm font-bold text-white font-display">ArchPlan Studio</h1>
          <p className="text-xs text-neutral-400">{floorPlan.name || '未命名项目'}</p>
        </div>
      </div>

      <div className="h-8 w-px bg-neutral-600 mx-2" />

      <div className="flex items-center gap-1">
        <ToolbarButton icon={<Undo2 size={16} />} label="撤销" onClick={undo} />
        <ToolbarButton icon={<Redo2 size={16} />} label="重做" onClick={redo} />
      </div>

      <div className="h-8 w-px bg-neutral-600 mx-2" />

      <div className="flex items-center gap-1 bg-neutral-700 rounded p-1">
        {viewModes.map(({ mode, label, icon }) => (
          <button
            key={mode}
            onClick={() => setViewMode(mode)}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded text-xs font-medium transition-all ${
              viewMode === mode
                ? 'bg-accent-primary text-white shadow-glow'
                : 'text-neutral-300 hover:text-white hover:bg-neutral-600'
            }`}
          >
            {icon}
            {label}
          </button>
        ))}
      </div>

      <div className="h-8 w-px bg-neutral-600 mx-2" />

      <div className="flex items-center gap-1">
        <ToolbarButton
          icon={<Grid3X3 size={16} />}
          label="网格"
          onClick={() => setShowGrid(!showGrid)}
          active={showGrid}
        />
        <ToolbarButton
          icon={<Ruler size={16} />}
          label="辅助"
          onClick={() => setShowHelpers(!showHelpers)}
          active={showHelpers}
        />
      </div>

      <div className="flex-1" />

      <div className="flex items-center gap-1">
        <ToolbarButton
          icon={<MessageSquare size={16} />}
          label="批注"
          onClick={() => togglePanel('annotationPanel')}
        />
        <ToolbarButton
          icon={<FileText size={16} />}
          label="家具库"
          onClick={() => togglePanel('furnitureLibrary')}
        />
        <ToolbarButton
          icon={<FileJson size={16} />}
          label="属性"
          onClick={() => togglePanel('propertyPanel')}
        />
      </div>

      <div className="h-8 w-px bg-neutral-600 mx-2" />

      <div className="flex items-center gap-1">
        <ToolbarButton
          icon={<Upload size={16} />}
          label="导入"
          onClick={() => fileInputRef.current?.click()}
        />
        <input
          ref={fileInputRef}
          type="file"
          accept=".json,.dxf"
          onChange={handleImportFile}
          className="hidden"
        />

        <div className="relative group">
          <ToolbarButton icon={<Download size={16} />} label="导出" />
          <div className="absolute right-0 top-full mt-1 bg-neutral-800 border border-neutral-700 rounded-lg shadow-xl opacity-0 invisible group-hover:opacity-100 group-hover:visible transition-all z-50 min-w-[120px]">
            <button
              onClick={handleExportJSON}
              className="w-full px-3 py-2 text-left text-sm text-neutral-300 hover:bg-neutral-700 hover:text-white rounded-t-lg transition-colors"
            >
              导出 JSON
            </button>
            <button
              onClick={handleExportSVG}
              className="w-full px-3 py-2 text-left text-sm text-neutral-300 hover:bg-neutral-700 hover:text-white rounded-b-lg transition-colors"
            >
              导出 SVG
            </button>
          </div>
        </div>

        <ToolbarButton icon={<Save size={16} />} label="保存" onClick={handleSave} />
      </div>

      <div className="h-8 w-px bg-neutral-600 mx-2" />

      <button
        onClick={() => togglePanel('renderDialog')}
        className="flex items-center gap-2 px-4 py-2 bg-accent-primary hover:bg-accent-hover text-white text-xs font-medium rounded transition-all shadow-glow hover:shadow-glow"
      >
        <Camera size={16} />
        渲染
      </button>

      <ToolbarButton icon={<Settings size={16} />} label="设置" onClick={() => togglePanel('settingsPanel')} />
    </div>
  );
};

interface ToolbarButtonProps {
  icon: React.ReactNode;
  label: string;
  onClick?: () => void;
  active?: boolean;
}

const ToolbarButton: React.FC<ToolbarButtonProps> = ({ icon, label, onClick, active }) => (
  <button
    onClick={onClick}
    className={`flex items-center gap-1.5 px-3 py-2 rounded text-xs font-medium transition-all ${
      active
        ? 'bg-accent-primary/20 text-accent-primary'
        : 'text-neutral-300 hover:text-white hover:bg-neutral-700'
    }`}
    title={label}
  >
    {icon}
    <span className="hidden sm:inline">{label}</span>
  </button>
);
