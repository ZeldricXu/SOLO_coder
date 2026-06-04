import React from 'react';
import { useLegacySimulationStore } from '../store';

const TimelineControls: React.FC = () => {
  const {
    isRunning,
    isPaused,
    currentTime,
    speed,
    startSimulation,
    pauseSimulation,
    resumeSimulation,
    stopSimulation,
    resetSimulation,
    stepSimulation,
    setSpeed,
  } = useLegacySimulationStore();

  const handleStartPause = () => {
    if (!isRunning) {
      startSimulation();
    } else if (isPaused) {
      resumeSimulation();
    } else {
      pauseSimulation();
    }
  };

  const handleStep = () => {
    if (!isRunning) {
      startSimulation();
      setTimeout(() => pauseSimulation(), 50);
    } else {
      pauseSimulation();
      stepSimulation(1 / 60);
    }
  };

  return (
    <div style={{
      position: 'absolute',
      bottom: 20,
      left: '50%',
      transform: 'translateX(-50%)',
      background: 'rgba(30, 30, 30, 0.95)',
      padding: '15px 25px',
      borderRadius: 12,
      display: 'flex',
      alignItems: 'center',
      gap: 15,
      boxShadow: '0 4px 20px rgba(0, 0, 0, 0.5)',
      zIndex: 100,
    }}>
      <button
        onClick={handleStartPause}
        style={{
          padding: '10px 20px',
          fontSize: 16,
          fontWeight: 600,
          border: 'none',
          borderRadius: 8,
          cursor: 'pointer',
          background: isRunning && !isPaused ? '#ff6b6b' : '#4ecdc4',
          color: 'white',
          transition: 'all 0.2s',
        }}
        onMouseEnter={(e) => {
          e.currentTarget.style.transform = 'scale(1.05)';
        }}
        onMouseLeave={(e) => {
          e.currentTarget.style.transform = 'scale(1)';
        }}
      >
        {isRunning ? (isPaused ? '▶ 继续' : '⏸ 暂停') : '▶ 开始'}
      </button>
      
      <button
        onClick={handleStep}
        style={{
          padding: '10px 15px',
          fontSize: 14,
          border: '1px solid #555',
          borderRadius: 8,
          cursor: 'pointer',
          background: '#3a3a3a',
          color: 'white',
        }}
      >
        ⏭ 单步
      </button>
      
      <button
        onClick={stopSimulation}
        style={{
          padding: '10px 15px',
          fontSize: 14,
          border: '1px solid #555',
          borderRadius: 8,
          cursor: 'pointer',
          background: '#3a3a3a',
          color: 'white',
        }}
      >
        ⏹ 停止
      </button>
      
      <button
        onClick={resetSimulation}
        style={{
          padding: '10px 15px',
          fontSize: 14,
          border: '1px solid #555',
          borderRadius: 8,
          cursor: 'pointer',
          background: '#3a3a3a',
          color: 'white',
        }}
      >
        ↺ 重置
      </button>
      
      <div style={{ height: 30, width: 1, background: '#555' }} />
      
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <span style={{ color: '#aaa', fontSize: 14 }}>速度:</span>
        <input
          type="range"
          min="0.1"
          max="5"
          step="0.1"
          value={speed}
          onChange={(e) => setSpeed(parseFloat(e.target.value))}
          style={{ width: 100 }}
        />
        <span style={{ color: 'white', fontSize: 14, minWidth: 40 }}>{speed.toFixed(1)}x</span>
      </div>
      
      <div style={{ height: 30, width: 1, background: '#555' }} />
      
      <div style={{ color: 'white', fontFamily: 'monospace', fontSize: 16 }}>
        时间: <span style={{ color: '#4ecdc4' }}>{currentTime.toFixed(2)}s</span>
      </div>
    </div>
  );
};

export default TimelineControls;
