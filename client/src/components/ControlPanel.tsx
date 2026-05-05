import React from 'react';
import { useAppContext } from '../context/AppContext';
import { 
  AUDIO_LANGUAGES, 
  TARGET_LANGUAGES, 
  AudioLanguage, 
  TargetLanguage 
} from '../types';

export function ControlPanel() {
  const { state, startRecording, stopRecording, updateSettings } = useAppContext();

  const handleLanguageChange = (language: AudioLanguage) => {
    updateSettings({ audioLanguage: language });
  };

  const handleTargetLanguageChange = (language: TargetLanguage) => {
    updateSettings({ targetLanguage: language });
  };

  const handleTranslationToggle = () => {
    updateSettings({ enableTranslation: !state.settings.enableTranslation });
  };

  const handleRecordClick = async () => {
    if (state.isRecording) {
      await stopRecording();
    } else {
      await startRecording();
    }
  };

  return (
    <div className="control-panel">
      <div className="controls-row">
        <div className="control-group">
        <label>源语言</label>
        <select
          value={state.settings.audioLanguage}
          onChange={(e) => handleLanguageChange(e.target.value as AudioLanguage)}
          disabled={state.isRecording}
          className="language-select"
        >
          {AUDIO_LANGUAGES.map((lang) => (
            <option key={lang.value} value={lang.value}>
              {lang.label}
            </option>
          ))}
        </select>
      </div>

      <div className="control-group">
        <label>
          <input
            type="checkbox"
            checked={state.settings.enableTranslation}
            onChange={handleTranslationToggle}
            disabled={state.isRecording}
          />
          启用翻译
        </label>
      </div>

      {state.settings.enableTranslation && (
        <div className="control-group">
          <label>目标语言</label>
          <select
            value={state.settings.targetLanguage}
            onChange={(e) => handleTargetLanguageChange(e.target.value as TargetLanguage)}
            disabled={state.isRecording}
            className="language-select"
          >
            {TARGET_LANGUAGES.map((lang) => (
              <option key={lang.value} value={lang.value}>
                {lang.label}
              </option>
            ))}
          </select>
        </div>
      )}
      </div>

      <div className="record-section">
        <VolumeIndicator volume={state.volumeLevel} isActive={state.isRecording} />
        
        <button
          onClick={handleRecordClick}
          className={state.isRecording ? 'record-btn recording' : 'record-btn'}
          disabled={!state.isConnected && !state.isRecording}
        >
          {state.isRecording ? (
            <>
              <span className="stop-icon"></span>
              停止录音
            </>
          ) : (
            <>
              <span className="mic-icon">🎤</span>
              开始录音
            </>
          )}
        </button>

        <div className="connection-status">
          <span className={state.isConnected ? 'status connected' : 'status disconnected'}></span>
          <span className="status-text">
            {state.isConnected ? '已连接' : '未连接'}
          </span>
        </div>
      </div>

      {state.error && (
        <div className="error-message">
          {state.error}
        </div>
      )}
    </div>
  );
}

interface VolumeIndicatorProps {
  volume: number;
  isActive: boolean;
}

function VolumeIndicator({ volume, isActive }: VolumeIndicatorProps) {
  const normalizedVolume = Math.min(volume * 3, 1);
  const bars = 5;
  const activeBars = Math.floor(normalizedVolume * bars);

  return (
    <div className="volume-indicator">
      {Array.from({ length: bars }).map((_, index) => (
        <div
          key={index}
          className={index < activeBars ? 'volume-bar active' : 'volume-bar'}
          style={{
            height: `${20 + index * 8}px`,
          }}
        />
      ))}
    </div>
  );
}
