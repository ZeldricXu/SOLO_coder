import React, { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { Vec3 } from '@physics-sim/shared';
import { useLegacySimulationStore } from '../store';
import { computeFFT, leastSquaresLinear, leastSquaresQuadratic, leastSquaresExponential, leastSquaresSine, downsampleForZoom } from '@physics-sim/math';

interface PlotData {
  time: number[];
  values: number[];
}

interface ViewBounds {
  startTime: number;
  endTime: number;
}

const DataAnalyzer: React.FC = () => {
  const { sensorData } = useLegacySimulationStore();
  const [activeTab, setActiveTab] = useState<'time' | 'fft' | 'fit'>('time');
  const [selectedChannel, setSelectedChannel] = useState<string | null>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [viewBounds, setViewBounds] = useState<ViewBounds | null>(null);
  const [isDragging, setIsDragging] = useState(false);
  const dragStartRef = useRef<{ x: number; bounds: ViewBounds } | null>(null);

  const sensors = Array.from(sensorData.entries());

  const getPlotData = useCallback((sensorId: string): PlotData | null => {
    const data = sensorData.get(sensorId);
    if (!data || data.length < 2) return null;

    return {
      time: data.map((d) => d.time),
      values: data.map((d) => {
        if (typeof d.value === 'number') return d.value as number;
        const v = d.value as Vec3;
        return Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
      }),
    };
  }, [sensorData]);

  const fullData = useMemo(() => {
    return selectedChannel ? getPlotData(selectedChannel) : null;
  }, [selectedChannel, getPlotData]);

  const effectiveBounds = useMemo((): ViewBounds | null => {
    if (viewBounds) return viewBounds;
    if (!fullData) return null;
    return {
      startTime: fullData.time[0],
      endTime: fullData.time[fullData.time.length - 1],
    };
  }, [viewBounds, fullData]);

  const drawCanvas = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas || !fullData || !effectiveBounds) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    const width = rect.width;
    const height = rect.height;

    canvas.width = width * dpr;
    canvas.height = height * dpr;
    ctx.scale(dpr, dpr);

    ctx.fillStyle = '#2a2a2a';
    ctx.fillRect(0, 0, width, height);

    const margin = { top: 20, right: 20, bottom: 30, left: 50 };
    const plotWidth = width - margin.left - margin.right;
    const plotHeight = height - margin.top - margin.bottom;

    const dataPoints = fullData.time.map((t, i) => ({ time: t, value: fullData.values[i] }));
    const downsampled = downsampleForZoom(
      dataPoints,
      effectiveBounds.startTime,
      effectiveBounds.endTime,
      plotWidth
    );

    const x = (t: number) => margin.left + ((t - effectiveBounds.startTime) / (effectiveBounds.endTime - effectiveBounds.startTime)) * plotWidth;
    
    const visibleValues = downsampled.map(d => d.value);
    const yMin = Math.min(...visibleValues);
    const yMax = Math.max(...visibleValues);
    const yRange = yMax - yMin || 1;
    
    const y = (v: number) => margin.top + plotHeight - ((v - yMin) / yRange) * plotHeight;

    ctx.strokeStyle = '#444';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(margin.left, margin.top);
    ctx.lineTo(margin.left, margin.top + plotHeight);
    ctx.lineTo(margin.left + plotWidth, margin.top + plotHeight);
    ctx.stroke();

    ctx.fillStyle = '#888';
    ctx.font = '11px monospace';
    ctx.textAlign = 'center';
    
    const numXTicks = 5;
    for (let i = 0; i <= numXTicks; i++) {
      const t = effectiveBounds.startTime + (effectiveBounds.endTime - effectiveBounds.startTime) * (i / numXTicks);
      const px = x(t);
      ctx.fillText(t.toFixed(2), px, margin.top + plotHeight + 18);
      ctx.strokeStyle = '#333';
      ctx.beginPath();
      ctx.moveTo(px, margin.top);
      ctx.lineTo(px, margin.top + plotHeight);
      ctx.stroke();
    }

    ctx.textAlign = 'right';
    const numYTicks = 5;
    for (let i = 0; i <= numYTicks; i++) {
      const v = yMin + yRange * (i / numYTicks);
      const py = y(v);
      ctx.fillText(v.toFixed(2), margin.left - 5, py + 4);
      ctx.strokeStyle = '#333';
      ctx.beginPath();
      ctx.moveTo(margin.left, py);
      ctx.lineTo(margin.left + plotWidth, py);
      ctx.stroke();
    }

    if (downsampled.length > 1) {
      ctx.strokeStyle = '#4ecdc4';
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(x(downsampled[0].time), y(downsampled[0].value));
      for (let i = 1; i < downsampled.length; i++) {
        ctx.lineTo(x(downsampled[i].time), y(downsampled[i].value));
      }
      ctx.stroke();
    }

    ctx.fillStyle = '#aaa';
    ctx.font = '10px sans-serif';
    ctx.textAlign = 'left';
    ctx.fillText(`点数: ${downsampled.length} / ${fullData.time.length}`, margin.left, 12);
  }, [fullData, effectiveBounds]);

  useEffect(() => {
    drawCanvas();
  }, [drawCanvas]);

  const handleMouseDown = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!effectiveBounds) return;
    setIsDragging(true);
    dragStartRef.current = {
      x: e.clientX,
      bounds: { ...effectiveBounds },
    };
  };

  const handleMouseMove = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!isDragging || !dragStartRef.current || !effectiveBounds) return;
    
    const canvas = canvasRef.current;
    if (!canvas) return;
    
    const rect = canvas.getBoundingClientRect();
    const plotWidth = rect.width - 70;
    const deltaX = e.clientX - dragStartRef.current.x;
    const timeRange = effectiveBounds.endTime - effectiveBounds.startTime;
    const deltaTime = (deltaX / plotWidth) * timeRange;
    
    setViewBounds({
      startTime: dragStartRef.current.bounds.startTime - deltaTime,
      endTime: dragStartRef.current.bounds.endTime - deltaTime,
    });
  };

  const handleMouseUp = () => {
    setIsDragging(false);
    dragStartRef.current = null;
  };

  const handleWheel = (e: React.WheelEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    if (!effectiveBounds || !fullData) return;

    const canvas = canvasRef.current;
    if (!canvas) return;
    
    const rect = canvas.getBoundingClientRect();
    const mouseX = e.clientX - rect.left - 50;
    const plotWidth = rect.width - 70;
    const mouseTime = effectiveBounds.startTime + (mouseX / plotWidth) * (effectiveBounds.endTime - effectiveBounds.startTime);
    
    const zoomFactor = e.deltaY > 0 ? 1.2 : 0.8;
    const currentRange = effectiveBounds.endTime - effectiveBounds.startTime;
    const newRange = currentRange * zoomFactor;
    
    const leftRatio = (mouseTime - effectiveBounds.startTime) / currentRange;
    const rightRatio = (effectiveBounds.endTime - mouseTime) / currentRange;
    
    const newStartTime = mouseTime - leftRatio * newRange;
    const newEndTime = mouseTime + rightRatio * newRange;
    
    const minRange = (fullData.time[fullData.time.length - 1] - fullData.time[0]) / 100;
    const maxRange = fullData.time[fullData.time.length - 1] - fullData.time[0];
    
    const finalRange = Math.max(minRange, Math.min(maxRange, newRange));
    
    if (finalRange === maxRange) {
      setViewBounds(null);
    } else {
      setViewBounds({
        startTime: Math.max(fullData.time[0], newStartTime),
        endTime: Math.min(fullData.time[fullData.time.length - 1], newEndTime),
      });
    }
  };

  const handleDoubleClick = () => {
    setViewBounds(null);
  };

  const computeStatistics = (data: PlotData) => {
    const values = data.values;
    const n = values.length;
    if (n === 0) return null;

    const mean = values.reduce((a, b) => a + b, 0) / n;
    const max = Math.max(...values);
    const min = Math.min(...values);
    const variance = values.reduce((acc, v) => acc + Math.pow(v - mean, 2), 0) / n;
    const rms = Math.sqrt(values.reduce((acc, v) => acc + v * v, 0) / n);

    return { mean, max, min, variance, rms, count: n };
  };

  const computeFFTData = (data: PlotData) => {
    if (data.values.length < 2) return null;

    const dt = data.time[1] - data.time[0];
    const fs = 1 / dt;
    
    const result = computeFFT(data.values, fs);
    
    const magnitudes = result.magnitudes.map(m => 20 * Math.log10(Math.max(m, 1e-10)));
    
    return { frequencies: result.frequencies, magnitudes };
  };

  const computeFit = (data: PlotData, type: 'linear' | 'quadratic' | 'exponential' | 'sine') => {
    const x = data.time;
    const y = data.values;
    
    switch (type) {
      case 'linear':
        return leastSquaresLinear(x, y);
      case 'quadratic':
        return leastSquaresQuadratic(x, y);
      case 'exponential':
        return leastSquaresExponential(x, y);
      case 'sine':
        return leastSquaresSine(x, y);
    }
  };

  const plotData = fullData;
  const stats = plotData ? computeStatistics(plotData) : null;
  const fftData = plotData ? computeFFTData(plotData) : null;

  return (
    <div style={{
      width: 350,
      height: '100%',
      background: '#1e1e1e',
      borderLeft: '1px solid #333',
      display: 'flex',
      flexDirection: 'column',
      overflow: 'hidden',
    }}>
      <div style={{
        padding: '15px 20px',
        background: '#2a2a2a',
        borderBottom: '1px solid #333',
      }}>
        <h2 style={{ color: 'white', fontSize: 18, margin: 0 }}>数据分析</h2>
      </div>
      
      <div style={{
        display: 'flex',
        borderBottom: '1px solid #333',
      }}>
        {(['time', 'fft', 'fit'] as const).map((tab) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            style={{
              flex: 1,
              padding: '10px',
              border: 'none',
              background: activeTab === tab ? '#3a3a3a' : 'transparent',
              color: activeTab === tab ? '#4ecdc4' : '#aaa',
              cursor: 'pointer',
              fontSize: 13,
            }}
          >
            {tab === 'time' ? '时域' : tab === 'fft' ? '频域' : '拟合'}
          </button>
        ))}
      </div>
      
      <div style={{ flex: 1, overflowY: 'auto', padding: 15 }}>
        <div style={{ marginBottom: 20 }}>
          <h3 style={{ color: '#aaa', fontSize: 12, textTransform: 'uppercase', margin: '0 0 10px 0' }}>
            选择传感器
          </h3>
          {sensors.length === 0 ? (
            <p style={{ color: '#666', fontSize: 13 }}>暂无传感器数据</p>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
              {sensors.map(([id]) => (
                <button
                  key={id}
                  onClick={() => { setSelectedChannel(id); setViewBounds(null); }}
                  style={{
                    padding: '8px 12px',
                    border: 'none',
                    borderRadius: 6,
                    background: selectedChannel === id ? '#4ecdc4' : '#3a3a3a',
                    color: 'white',
                    cursor: 'pointer',
                    textAlign: 'left',
                    fontSize: 13,
                  }}
                >
                  传感器 {id.slice(0, 8)}
                </button>
              ))}
            </div>
          )}
        </div>
        
        {plotData && stats && (
          <div>
            <canvas
              ref={canvasRef}
              width={300}
              height={200}
              style={{
                width: 300,
                height: 200,
                background: '#2a2a2a',
                borderRadius: 8,
                marginBottom: 15,
                cursor: isDragging ? 'grabbing' : 'grab',
              }}
              onMouseDown={handleMouseDown}
              onMouseMove={handleMouseMove}
              onMouseUp={handleMouseUp}
              onMouseLeave={handleMouseUp}
              onWheel={handleWheel}
              onDoubleClick={handleDoubleClick}
            />
            
            <div style={{ color: '#888', fontSize: 11, marginBottom: 10, textAlign: 'center' }}>
              拖拽平移 · 滚轮缩放 · 双击重置
            </div>
            
            {activeTab === 'time' && (
              <div style={{ background: '#2a2a2a', padding: 15, borderRadius: 8 }}>
                <h4 style={{ color: '#4ecdc4', fontSize: 13, margin: '0 0 10px 0' }}>统计信息</h4>
                <div style={{ color: '#aaa', fontSize: 12, fontFamily: 'monospace', display: 'flex', flexDirection: 'column', gap: 5 }}>
                  <div>数据点数: {stats.count}</div>
                  <div>均值: {stats.mean.toFixed(4)}</div>
                  <div>最大值: {stats.max.toFixed(4)}</div>
                  <div>最小值: {stats.min.toFixed(4)}</div>
                  <div>方差: {stats.variance.toFixed(6)}</div>
                  <div>RMS: {stats.rms.toFixed(4)}</div>
                </div>
              </div>
            )}
            
            {activeTab === 'fft' && fftData && (
              <div style={{ background: '#2a2a2a', padding: 15, borderRadius: 8 }}>
                <h4 style={{ color: '#4ecdc4', fontSize: 13, margin: '0 0 10px 0' }}>频谱分析</h4>
                <canvas
                  width={300}
                  height={150}
                  ref={(fftCanvas) => {
                    if (!fftCanvas || !fftData) return;
                    const ctx = fftCanvas.getContext('2d');
                    if (!ctx) return;
                    
                    ctx.fillStyle = '#1a1a1a';
                    ctx.fillRect(0, 0, 300, 150);
                    
                    const maxMag = Math.max(...fftData.magnitudes);
                    const minMag = Math.min(...fftData.magnitudes);
                    const range = maxMag - minMag || 1;
                    
                    fftData.frequencies.forEach((_, i) => {
                      const x = i * (300 / fftData.frequencies.length);
                      const h = Math.max(0, ((fftData.magnitudes[i] - minMag) / range) * 140);
                      const y = 150 - h;
                      ctx.fillStyle = '#4ecdc4';
                      ctx.fillRect(x, y, 300 / fftData.frequencies.length - 1, h);
                    });
                  }}
                  style={{ background: '#1a1a1a', borderRadius: 4 }}
                />
              </div>
            )}
            
            {activeTab === 'fit' && (
              <div style={{ background: '#2a2a2a', padding: 15, borderRadius: 8 }}>
                <h4 style={{ color: '#4ecdc4', fontSize: 13, margin: '0 0 10px 0' }}>曲线拟合</h4>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  {(['linear', 'quadratic', 'exponential', 'sine'] as const).map((type) => {
                    const fit = computeFit(plotData, type) as any;
                    const typeName = type === 'linear' ? '线性' : type === 'quadratic' ? '二次' : type === 'exponential' ? '指数' : '正弦';
                    return (
                      <div key={type} style={{ color: '#aaa', fontSize: 12, fontFamily: 'monospace' }}>
                        <div style={{ color: '#fff', marginBottom: 3 }}>{typeName}:</div>
                        <div>R² = {fit.rSquared.toFixed(4)}</div>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default DataAnalyzer;
