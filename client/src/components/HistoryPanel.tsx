import React, { useState, useEffect } from 'react';
import { HistoryRecord, Segment } from '../types';
import { ExportService } from '../services/export';
import axios from 'axios';

export function HistoryPanel() {
  const [records, setRecords] = useState<HistoryRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedRecord, setSelectedRecord] = useState<{
    id: string;
    segments: Segment[];
    metadata: {
      audioLanguage: string;
      targetLanguage?: string;
      createdAt: string;
    };
  } | null>(null);

  useEffect(() => {
    fetchHistory();
  }, []);

  const fetchHistory = async () => {
    setLoading(true);
    setError(null);
    try {
      const response = await axios.get('/api/v1/transcribe/history');
      if (response.data.code === 200) {
        setRecords(response.data.data.records);
      }
    } catch (err) {
      setError('无法加载历史记录');
      console.error('Failed to fetch history:', err);
    } finally {
      setLoading(false);
    }
  };

  const fetchRecordDetails = async (id: string) => {
    try {
      const response = await axios.get(`/api/v1/transcribe/${id}`);
      if (response.data.code === 200) {
        const data = response.data.data;
        setSelectedRecord({
          id,
          segments: data.segments.map((s: {
            segment_id: number;
            start_time: number;
            end_time: number;
            original_text: string;
            translated_text?: string;
            confidence: number;
            status: 'confirmed' | 'pending';
          }) => ({
            segmentId: s.segment_id,
            startTime: s.start_time,
            endTime: s.end_time,
            originalText: s.original_text,
            translatedText: s.translated_text,
            confidence: s.confidence,
            status: s.status,
          })),
          metadata: {
            audioLanguage: data.audio_language,
            targetLanguage: data.target_language,
            createdAt: data.created_at,
          },
        });
      }
    } catch (err) {
      setError('无法加载记录详情');
      console.error('Failed to fetch record details:', err);
    }
  };

  const handleDelete = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    if (!confirm('确定要删除这条记录吗？')) return;
    
    try {
      await axios.delete(`/api/v1/transcribe/${id}`);
      setRecords(records.filter(r => r.transcribe_id !== id));
      if (selectedRecord?.id === id) {
        setSelectedRecord(null);
      }
    } catch (err) {
      setError('删除失败');
      console.error('Failed to delete record:', err);
    }
  };

  const handleExport = (type: 'txt' | 'srt' | 'json') => {
    if (!selectedRecord) return;

    switch (type) {
      case 'txt':
        ExportService.downloadTXT(selectedRecord.segments);
        break;
      case 'srt':
        ExportService.downloadSRT(selectedRecord.segments);
        break;
      case 'json':
        ExportService.downloadJSON(selectedRecord.segments, {
          audioLanguage: selectedRecord.metadata.audioLanguage,
          targetLanguage: selectedRecord.metadata.targetLanguage,
        });
        break;
    }
  };

  const formatDuration = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  const formatDate = (dateString: string) => {
    const date = new Date(dateString);
    return date.toLocaleString('zh-CN');
  };

  return (
    <div className="history-panel">
      <div className="history-header">
        <h2>历史记录</h2>
        <button className="refresh-btn" onClick={fetchHistory} disabled={loading}>
          🔄 刷新
        </button>
      </div>

      {error && (
        <div className="history-error">{error}</div>
      )}

      {loading && (
        <div className="history-loading">加载中...</div>
      )}

      {!loading && records.length === 0 && (
        <div className="history-empty">
          <p>暂无历史记录</p>
          <p className="hint">开始录音后，记录将自动保存</p>
        </div>
      )}

      <div className="history-content">
        <div className="history-list">
          {records.map((record) => (
            <div
              key={record.transcribe_id}
              className={`history-item ${selectedRecord?.id === record.transcribe_id ? 'selected' : ''}`}
              onClick={() => fetchRecordDetails(record.transcribe_id)}
            >
              <div className="item-info">
                <div className="item-date">{formatDate(record.created_at)}</div>
                <div className="item-meta">
                  <span className="meta-label">时长</span>
                  <span className="meta-value">{formatDuration(record.duration)}</span>
                  <span className="meta-label">片段</span>
                  <span className="meta-value">{record.segment_count}</span>
                </div>
              </div>
              <button
                className="delete-btn"
                onClick={(e) => handleDelete(record.transcribe_id, e)}
                title="删除"
              >
                🗑️
              </button>
            </div>
          ))}
        </div>

        {selectedRecord && (
          <div className="history-detail">
            <div className="detail-header">
              <h3>记录详情</h3>
              <div className="detail-actions">
                <button className="export-small" onClick={() => handleExport('txt')}>
                  📄 TXT
                </button>
                <button className="export-small" onClick={() => handleExport('srt')}>
                  📺 SRT
                </button>
                <button className="export-small" onClick={() => handleExport('json')}>
                  📋 JSON
                </button>
              </div>
            </div>
            <div className="detail-meta">
              <span>创建时间: {formatDate(selectedRecord.metadata.createdAt)}</span>
              <span>源语言: {selectedRecord.metadata.audioLanguage}</span>
              {selectedRecord.metadata.targetLanguage && (
                <span>目标语言: {selectedRecord.metadata.targetLanguage}</span>
              )}
            </div>
            <div className="detail-segments">
              {selectedRecord.segments.map((segment, index) => (
                <div key={segment.segmentId} className="detail-segment">
                  <div className="segment-time">
                    {formatDuration(Math.floor(segment.startTime))} - {formatDuration(Math.floor(segment.endTime))}
                  </div>
                  <div className="segment-original">{segment.originalText}</div>
                  {segment.translatedText && (
                    <div className="segment-translated">{segment.translatedText}</div>
                  )}
                  <div className="segment-confidence">
                    置信度: {(segment.confidence * 100).toFixed(1)}%
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
