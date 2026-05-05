import React, { useState } from 'react';
import { useAppContext } from '../context/AppContext';
import { ExportService } from '../services/export';
import { Segment } from '../types';

export function ExportPanel() {
  const { state } = useAppContext();
  const [showMenu, setShowMenu] = useState(false);
  const [includeTranslation, setIncludeTranslation] = useState(true);

  const hasSegments = state.segments.length > 0;

  const handleDownloadSRT = () => {
    if (!hasSegments) return;
    ExportService.downloadSRT(state.segments);
    setShowMenu(false);
  };

  const handleDownloadTXT = () => {
    if (!hasSegments) return;
    ExportService.downloadTXT(state.segments, includeTranslation);
    setShowMenu(false);
  };

  const handleDownloadJSON = () => {
    if (!hasSegments) return;
    ExportService.downloadJSON(state.segments, {
      sessionId: state.session?.sessionId,
      audioLanguage: state.settings.audioLanguage,
      targetLanguage: state.settings.enableTranslation ? state.settings.targetLanguage : undefined,
      totalDuration: ExportService.calculateDuration(state.segments),
    });
    setShowMenu(false);
  };

  if (!hasSegments) {
    return (
      <div className="export-panel disabled">
        <button className="export-btn" disabled>
          📥 导出
        </button>
      </div>
    );
  }

  return (
    <div className="export-panel">
      <div className="export-info">
        <span className="segment-count">
          {state.segments.length} 个片段
        </span>
        <span className="word-count">
          {ExportService.calculateWordCount(state.segments)} 个字符
        </span>
      </div>

      <div className="export-menu-container">
        <button 
          className="export-btn" 
          onClick={() => setShowMenu(!showMenu)}
        >
          📥 导出
        </button>

        {showMenu && (
          <div className="export-menu">
            <div className="menu-option">
              <label className="checkbox-label">
                <input
                  type="checkbox"
                  checked={includeTranslation}
                  onChange={(e) => setIncludeTranslation(e.target.checked)}
                />
                包含翻译文本
              </label>
            </div>
            
            <div className="menu-divider"></div>

            <button 
              className="menu-item"
              onClick={handleDownloadTXT}
            >
              📄 导出为 TXT
            </button>

            <button 
              className="menu-item"
              onClick={handleDownloadSRT}
            >
              📺 导出为 SRT 字幕
            </button>

            <button 
              className="menu-item"
              onClick={handleDownloadJSON}
            >
              📋 导出为 JSON
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
