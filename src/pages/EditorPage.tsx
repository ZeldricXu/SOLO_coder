import React, { useEffect, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Toolbar } from '@/components/editor/Toolbar';
import { ToolPanel } from '@/components/editor/ToolPanel';
import { Canvas2D } from '@/components/editor/Canvas2D';
import { Scene3D } from '@/components/editor/Scene3D';
import { PropertyPanel } from '@/components/editor/PropertyPanel';
import { StatusBar } from '@/components/editor/StatusBar';
import { FurnitureLibrary } from '@/components/editor/FurnitureLibrary';
import { AnnotationPanel } from '@/components/editor/AnnotationPanel';
import { RenderDialog } from '@/components/editor/RenderDialog';
import { SketchfabBrowser } from '@/components/editor/SketchfabBrowser';
import { GIConfigPanel } from '@/components/editor/GIConfigPanel';
import { useFloorPlanStore } from '@/store/useFloorPlanStore';
import { useUIStore } from '@/store/useUIStore';
import { getDraft, saveDraft, generateThumbnail } from '@/utils/storage/indexedDB';
import { createDefaultFloorPlan } from '@/store/useFloorPlanStore';

const EditorPage: React.FC = () => {
  const { draftId } = useParams<{ draftId: string }>();
  const navigate = useNavigate();
  const { viewMode, floorPlan, setFloorPlan } = useFloorPlanStore();
  const { panels, giSettings, setGISettings } = useUIStore();
  const currentDraftIdRef = useRef<string>(draftId || `draft_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`);
  const autoSaveTimerRef = useRef<NodeJS.Timeout | null>(null);
  const isLoadedRef = useRef(false);

  useEffect(() => {
    const loadDraft = async () => {
      if (isLoadedRef.current) return;
      isLoadedRef.current = true;

      if (draftId) {
        try {
          const draft = await getDraft(draftId);
          if (draft) {
            setFloorPlan(draft.data);
            currentDraftIdRef.current = draftId;
          } else {
            alert('项目不存在，将创建新项目');
            setFloorPlan(createDefaultFloorPlan());
            navigate('/editor', { replace: true });
          }
        } catch (error) {
          console.error('加载草稿失败:', error);
          setFloorPlan(createDefaultFloorPlan());
        }
      }
    };

    loadDraft();
  }, [draftId, setFloorPlan, navigate]);

  useEffect(() => {
    if (autoSaveTimerRef.current) {
      clearTimeout(autoSaveTimerRef.current);
    }

    autoSaveTimerRef.current = setTimeout(async () => {
      try {
        const thumbnail = generateThumbnail(floorPlan);
        await saveDraft(currentDraftIdRef.current, floorPlan, floorPlan.name);
        if (thumbnail && floorPlan.thumbnail !== thumbnail) {
          useFloorPlanStore.setState((state) => ({
            floorPlan: { ...state.floorPlan, thumbnail },
          }));
        }
      } catch (error) {
        console.error('自动保存失败:', error);
      }
    }, 2000);

    return () => {
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
      }
    };
  }, [floorPlan]);

  return (
    <div className="h-screen flex flex-col bg-canvas-bg text-white overflow-hidden">
      <Toolbar draftId={currentDraftIdRef.current} />

      <div className="flex-1 flex overflow-hidden">
        <ToolPanel />

        <div className="flex-1 relative overflow-hidden">
          {viewMode === '2d' && <Canvas2D />}
          {viewMode === '3d' && <Scene3D />}
          {viewMode === 'split' && (
            <div className="absolute inset-0 flex">
              <div className="w-1/2 h-full border-r border-neutral-700">
                <Canvas2D />
              </div>
              <div className="w-1/2 h-full">
                <Scene3D />
              </div>
            </div>
          )}
        </div>

        {panels.furnitureLibrary && <FurnitureLibrary />}
        {panels.annotationPanel && <AnnotationPanel />}
        {panels.propertyPanel && <PropertyPanel />}
        {panels.sketchfabBrowser && <SketchfabBrowser />}
      </div>

      <StatusBar />

      {panels.renderDialog && <RenderDialog />}

      {viewMode === '3d' && panels.giPanel && (
        <div className="absolute bottom-4 left-4 w-80 z-20">
          <GIConfigPanel settings={giSettings} onChange={setGISettings} />
        </div>
      )}
    </div>
  );
};

export default EditorPage;
