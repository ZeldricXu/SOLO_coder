import React, { useRef, useEffect, useMemo } from 'react';
import { Segment } from '../types';
import { useAppContext } from '../context/AppContext';
import cn from 'classnames';

interface SubtitleSegmentProps {
  segment: Segment;
  isLatest: boolean;
}

const SubtitleSegment = React.memo(function SubtitleSegment({ segment, isLatest }: SubtitleSegmentProps) {
  const isPending = segment.status === 'pending';
  const hasTranslation = !!segment.translatedText;
  
  return (
    <div 
      className={cn(
        'subtitle-segment',
        { 'latest': isLatest, 'pending': isPending }
      )}
    >
      <div className="subtitle-time">
        {formatTime(segment.startTime)} - {formatTime(segment.endTime)}
      </div>
      <div className="subtitle-original">
        {segment.originalText}
      </div>
      {hasTranslation ? (
        <div className="subtitle-translated">
          {segment.translatedText}
        </div>
      ) : (
        <div className="subtitle-translated placeholder">
          翻译中...
        </div>
      )}
      <div className="subtitle-meta">
        <span className="confidence">
          置信度: {(segment.confidence * 100).toFixed(1)}%
        </span>
        {isPending && (
          <span className="status-pending">
            待确认
          </span>
        )}
        {hasTranslation && (
          <span className="translation-status">
            ✓ 已翻译
          </span>
        )}
      </div>
    </div>
  );
});

function formatTime(seconds: number): string {
  const mins = Math.floor(seconds / 60);
  const secs = Math.floor(seconds % 60);
  return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
}

export function SubtitleRenderer() {
  const { state } = useAppContext();
  const containerRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<boolean>(true);
  const segmentsLengthRef = useRef(0);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const handleScroll = () => {
      const { scrollHeight, scrollTop, clientHeight } = container;
      scrollRef.current = scrollHeight - scrollTop <= clientHeight + 100;
    };

    container.addEventListener('scroll', handleScroll);
    return () => container.removeEventListener('scroll', handleScroll);
  }, []);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const segmentsChanged = state.segments.length !== segmentsLengthRef.current;
    
    if (scrollRef.current) {
      container.scrollTop = container.scrollHeight;
    }

    segmentsLengthRef.current = state.segments.length;
  }, [state.segments]);

  const segmentsWithLatest = useMemo(() => {
    return state.segments.map((segment, index) => ({
      segment,
      isLatest: index === state.segments.length - 1,
    }));
  }, [state.segments]);

  if (state.segments.length === 0) {
    return (
      <div className="subtitle-container empty">
        <div className="empty-message">
          {state.isRecording ? (
            <div className="recording-indicator">
              <span className="pulse-dot"></span>
              <span>正在听，请开始说话...</span>
            </div>
          ) : (
            <span>点击录音按钮开始语音转写</span>
          )}
        </div>
      </div>
    );
  }

  return (
    <div className="subtitle-container" ref={containerRef}>
      {segmentsWithLatest.map(({ segment, isLatest }) => (
        <SubtitleSegment
          key={segment.segmentId}
          segment={segment}
          isLatest={isLatest}
        />
      ))}
    </div>
  );
}
