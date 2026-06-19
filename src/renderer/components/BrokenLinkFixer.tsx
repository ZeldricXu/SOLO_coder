import React, { useState, useEffect, useCallback } from 'react';
import type { BrokenLink, LinkSuggestion, Note } from '@shared/types';
import { scanNoteForBrokenLinks } from '../utils/editorUtils';

interface BrokenLinkFixerProps {
  note: Note | null;
  allNotes: Note[];
  onFixLink: (brokenLink: BrokenLinkUI, newTargetNoteId: string) => Promise<boolean>;
  threshold?: number;
}

interface BrokenLinkUI {
  target: string;
  displayText: string;
  startIndex: number;
  endIndex: number;
  originalLink: string;
  context: string;
  suggestions: LinkSuggestion[];
}

export const BrokenLinkFixer: React.FC<BrokenLinkFixerProps> = ({
  note,
  allNotes,
  onFixLink,
  threshold = 0.6,
}) => {
  const [brokenLinks, setBrokenLinks] = useState<BrokenLinkUI[]>([]);
  const [isScanning, setIsScanning] = useState(false);
  const [selectedLink, setSelectedLink] = useState<BrokenLinkUI | null>(null);
  const [fixingLinkId, setFixingLinkId] = useState<string | null>(null);

  const scanForBrokenLinks = useCallback(() => {
    if (!note) return;
    
    setIsScanning(true);
    try {
      const links = scanNoteForBrokenLinks(note.content, allNotes, note.path, threshold);
      setBrokenLinks(links);
    } catch (err) {
      console.error('Error scanning for broken links:', err);
    } finally {
      setIsScanning(false);
    }
  }, [note, allNotes, threshold]);

  useEffect(() => {
    if (note) {
      scanForBrokenLinks();
    } else {
      setBrokenLinks([]);
      setSelectedLink(null);
    }
  }, [note?.id, note?.content]);

  const handleFixLink = async (brokenLink: BrokenLinkUI, suggestion: LinkSuggestion) => {
    const linkId = `${brokenLink.target}-${brokenLink.startIndex}`;
    setFixingLinkId(linkId);
    
    try {
      const success = await onFixLink(brokenLink, suggestion.noteId);
      if (success) {
        setBrokenLinks(prev => prev.filter(l => 
          !(l.target === brokenLink.target && l.startIndex === brokenLink.startIndex)
        ));
        setSelectedLink(null);
      }
    } catch (err) {
      console.error('Error fixing link:', err);
    } finally {
      setFixingLinkId(null);
    }
  };

  if (!note) {
    return (
      <div className="broken-link-fixer-empty">
        <p>选择一篇笔记以检查失效链接</p>
      </div>
    );
  }

  return (
    <div className="broken-link-fixer">
      <div className="broken-link-fixer-header">
        <h4>🔗 失效链接检测</h4>
        <button 
          className="scan-button" 
          onClick={scanForBrokenLinks}
          disabled={isScanning}
        >
          {isScanning ? '扫描中...' : '重新扫描'}
        </button>
      </div>

      {isScanning && (
        <div className="scanning-indicator">
          <div className="spinner"></div>
          <span>正在扫描失效链接...</span>
        </div>
      )}

      {!isScanning && brokenLinks.length === 0 && (
        <div className="no-broken-links">
          <span className="check-icon">✓</span>
          <p>没有检测到失效链接</p>
        </div>
      )}

      {!isScanning && brokenLinks.length > 0 && (
        <div className="broken-links-count">
          检测到 <span className="count">{brokenLinks.length}</span> 个失效链接
        </div>
      )}

      <div className="broken-links-list">
        {brokenLinks.map((link, index) => {
          const linkId = `${link.target}-${link.startIndex}`;
          const isSelected = selectedLink?.target === link.target && 
                            selectedLink?.startIndex === link.startIndex;
          const isFixing = fixingLinkId === linkId;

          return (
            <div 
              key={linkId}
              className={`broken-link-item ${isSelected ? 'selected' : ''}`}
              onClick={() => setSelectedLink(isSelected ? null : link)}
            >
              <div className="broken-link-header">
                <span className="broken-link-icon">⚠️</span>
                <span className="broken-link-target" title={link.target}>
                  {link.target}
                </span>
                {link.suggestions.length > 0 && (
                  <span className="suggestions-badge">
                    {link.suggestions.length} 个建议
                  </span>
                )}
              </div>
              
              <div className="broken-link-context">
                {link.context}
              </div>

              {isSelected && link.suggestions.length > 0 && (
                <div className="link-suggestions">
                  <div className="suggestions-title">可能的匹配：</div>
                  <div className="suggestions-list">
                    {link.suggestions.map((suggestion, idx) => (
                      <div 
                        key={idx}
                        className={`suggestion-item ${isFixing ? 'disabled' : ''}`}
                        onClick={async (e) => {
                          e.stopPropagation();
                          await handleFixLink(link, suggestion);
                        }}
                      >
                        <div className="suggestion-title">{suggestion.title}</div>
                        <div className="suggestion-meta">
                          <span className="suggestion-path">{suggestion.path}</span>
                          <span className="suggestion-score">
                            相似度: {(suggestion.similarity * 100).toFixed(0)}%
                          </span>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {isSelected && link.suggestions.length === 0 && (
                <div className="no-suggestions">
                  <p>未找到相似的笔记标题</p>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default BrokenLinkFixer;
