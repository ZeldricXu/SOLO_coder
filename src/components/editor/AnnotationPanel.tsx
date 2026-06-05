import React, { useState } from 'react';
import { X, MessageSquare, Plus, Camera, Clock, User, Trash2 } from 'lucide-react';
import { useUIStore } from '@/store/useUIStore';
import { useFloorPlanStore } from '@/store/useFloorPlanStore';
import type { Annotation } from '@/types/floorplan';

export const AnnotationPanel: React.FC = () => {
  const { panels, setPanel } = useUIStore();
  const { floorPlan, addAnnotation, removeAnnotation, selectAnnotation } = useFloorPlanStore();
  const [newAnnotation, setNewAnnotation] = useState('');
  const [activeFilter, setActiveFilter] = useState<'all' | 'open' | 'resolved'>('all');

  const filteredAnnotations = floorPlan.annotations.filter((a) => {
    if (activeFilter === 'all') return true;
    return a.status === activeFilter;
  });

  const handleAddAnnotation = () => {
    if (!newAnnotation.trim()) return;

    const annotation: Omit<Annotation, 'id'> = {
      content: newAnnotation,
      position: { x: 0, y: 1.5, z: 0 },
      author: '当前用户',
      createdAt: Date.now(),
      status: 'open',
      screenshot: undefined,
    };

    addAnnotation(annotation);
    setNewAnnotation('');
  };

  const handleSelectAnnotation = (id: string) => {
    selectAnnotation(id);
  };

  const formatTime = (timestamp: number) => {
    const date = new Date(timestamp);
    return date.toLocaleString('zh-CN', {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  if (!panels.annotationPanel) return null;

  return (
    <div className="w-80 bg-neutral-800 border-l border-neutral-700 flex flex-col animate-slide-in">
      <div className="flex items-center justify-between px-4 py-3 border-b border-neutral-700">
        <h2 className="text-sm font-semibold text-white">协作批注</h2>
        <button
          onClick={() => setPanel('annotationPanel', false)}
          className="text-neutral-400 hover:text-white transition-colors"
        >
          <X size={16} />
        </button>
      </div>

      <div className="flex gap-1 p-3 border-b border-neutral-700">
        {(['all', 'open', 'resolved'] as const).map((filter) => (
          <button
            key={filter}
            onClick={() => setActiveFilter(filter)}
            className={`flex-1 px-3 py-1.5 rounded text-xs transition-colors ${
              activeFilter === filter
                ? 'bg-accent-primary text-white'
                : 'bg-neutral-700 text-neutral-300 hover:bg-neutral-600'
            }`}
          >
            {filter === 'all' ? '全部' : filter === 'open' ? '待处理' : '已解决'}
          </button>
        ))}
      </div>

      <div className="p-3 border-b border-neutral-700">
        <div className="flex gap-2">
          <textarea
            value={newAnnotation}
            onChange={(e) => setNewAnnotation(e.target.value)}
            placeholder="在3D视图中点击位置添加批注..."
            className="flex-1 px-3 py-2 bg-neutral-700 border border-neutral-600 rounded text-white text-sm placeholder-neutral-500 focus:outline-none focus:border-accent-primary resize-none h-20"
            onKeyDown={(e) => {
              if (e.key === 'Enter' && e.ctrlKey) {
                handleAddAnnotation();
              }
            }}
          />
        </div>
        <div className="flex items-center justify-between mt-2">
          <div className="flex gap-1">
            <button className="p-1.5 rounded bg-neutral-700 text-neutral-400 hover:text-white transition-colors">
              <Camera size={14} />
            </button>
          </div>
          <button
            onClick={handleAddAnnotation}
            disabled={!newAnnotation.trim()}
            className="flex items-center gap-1 px-3 py-1.5 bg-accent-primary text-white rounded text-xs hover:bg-accent-hover transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <Plus size={14} />
            添加批注
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto">
        {filteredAnnotations.length > 0 ? (
          <div className="divide-y divide-neutral-700">
            {filteredAnnotations.map((annotation) => (
              <div
                key={annotation.id}
                onClick={() => handleSelectAnnotation(annotation.id)}
                className="p-3 hover:bg-neutral-700/50 cursor-pointer transition-colors"
              >
                <div className="flex items-start justify-between mb-2">
                  <div className="flex items-center gap-2">
                    <div className="w-6 h-6 rounded-full bg-accent-secondary/20 flex items-center justify-center">
                      <User size={12} className="text-accent-secondary" />
                    </div>
                    <span className="text-xs text-white font-medium">{annotation.author}</span>
                  </div>
                  <div className="flex items-center gap-1">
                    <span
                      className={`px-2 py-0.5 rounded text-xs ${
                        annotation.status === 'open'
                          ? 'bg-amber-500/20 text-amber-400'
                          : 'bg-green-500/20 text-green-400'
                      }`}
                    >
                      {annotation.status === 'open' ? '待处理' : '已解决'}
                    </span>
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        removeAnnotation(annotation.id);
                      }}
                      className="p-1 text-neutral-500 hover:text-red-400 transition-colors"
                    >
                      <Trash2 size={12} />
                    </button>
                  </div>
                </div>

                <p className="text-sm text-neutral-300 mb-2">{annotation.content}</p>

                {annotation.screenshot && (
                  <div className="mb-2 rounded overflow-hidden bg-neutral-900">
                    <img
                      src={annotation.screenshot}
                      alt="批注截图"
                      className="w-full h-24 object-cover"
                    />
                  </div>
                )}

                <div className="flex items-center gap-1 text-xs text-neutral-500">
                  <Clock size={10} />
                  <span>{formatTime(annotation.createdAt)}</span>
                  <span className="mx-1">·</span>
                  <MessageSquare size={10} />
                  <span>位置: ({annotation.position.x.toFixed(1)}, {annotation.position.z.toFixed(1)})</span>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="text-center py-12 text-neutral-500">
            <MessageSquare size={32} className="mx-auto mb-2 opacity-50" />
            <p className="text-sm">暂无批注</p>
            <p className="text-xs mt-1">在3D视图中点击位置添加批注</p>
          </div>
        )}
      </div>

      <div className="p-3 border-t border-neutral-700 bg-neutral-900/50">
        <div className="flex items-center gap-2 text-xs text-neutral-400">
          <div className="w-2 h-2 rounded-full bg-green-500 animate-pulse" />
          <span>实时同步中 · 2 人在线</span>
        </div>
      </div>
    </div>
  );
};
