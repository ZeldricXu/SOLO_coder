import React from 'react';
import { useBoardStore } from '../../stores/useBoardStore';
import type { RichTextConfig } from '../../types';

const containerStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  paddingLeft: 8,
  paddingRight: 8,
  paddingTop: 4,
  paddingBottom: 4,
  borderLeft: '1px solid #e5e7eb',
  minWidth: 360,
};

const rowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  flexWrap: 'wrap',
};

const labelStyle: React.CSSProperties = {
  fontSize: 12,
  color: '#6b7280',
};

const inputStyle: React.CSSProperties = {
  width: 60,
  height: 28,
  border: '1px solid #d1d5db',
  borderRadius: 6,
  padding: '0 8px',
  fontSize: 12,
  outline: 'none',
};

const selectStyle: React.CSSProperties = {
  ...inputStyle,
  width: 90,
  cursor: 'pointer',
};

const textareaStyle: React.CSSProperties = {
  width: '100%',
  minHeight: 60,
  border: '1px solid #d1d5db',
  borderRadius: 6,
  padding: 8,
  fontSize: 12,
  outline: 'none',
  resize: 'vertical',
  fontFamily: 'monospace',
};

const colorPreviewStyle = (color: string): React.CSSProperties => ({
  width: 28,
  height: 28,
  borderRadius: 6,
  border: '1px solid #d1d5db',
  backgroundColor: color === 'transparent' ? 'transparent' : color,
  backgroundImage:
    color === 'transparent'
      ? 'linear-gradient(45deg, #ccc 25%, transparent 25%), linear-gradient(-45deg, #ccc 25%, transparent 25%), linear-gradient(45deg, transparent 75%, #ccc 75%), linear-gradient(-45deg, transparent 75%, #ccc 75%)'
      : 'none',
  backgroundSize: '8px 8px',
  backgroundPosition: '0 0, 0 4px, 4px -4px, -4px 0px',
  cursor: 'pointer',
});

const fontFamilies = [
  'Arial, sans-serif',
  'Helvetica, sans-serif',
  'Times New Roman, serif',
  'Georgia, serif',
  'Verdana, sans-serif',
  'Trebuchet MS, sans-serif',
  'Courier New, monospace',
  'Comic Sans MS, cursive',
  'Microsoft YaHei, sans-serif',
  'SimSun, serif',
];

const alignOptions: { value: RichTextConfig['textAlign']; label: string }[] = [
  { value: 'left', label: '左对齐' },
  { value: 'center', label: '居中' },
  { value: 'right', label: '右对齐' },
  { value: 'justify', label: '两端对齐' },
];

const TextTool: React.FC = () => {
  const { tool, setRichTextConfig } = useBoardStore();
  const { richTextConfig } = tool;

  return (
    <div style={containerStyle}>
      <div style={rowStyle}>
        <span style={labelStyle}>富文本</span>

        <span style={labelStyle}>字体</span>
        <select
          value={richTextConfig.fontFamily}
          onChange={(e) => setRichTextConfig({ fontFamily: e.target.value })}
          style={{ ...selectStyle, width: 140 }}
        >
          {fontFamilies.map((f) => (
            <option key={f} value={f} style={{ fontFamily: f }}>
              {f.split(',')[0]}
            </option>
          ))}
        </select>

        <span style={labelStyle}>字号</span>
        <input
          type="number"
          min="8"
          max="200"
          value={richTextConfig.fontSize}
          onChange={(e) => setRichTextConfig({ fontSize: Number(e.target.value) })}
          style={inputStyle}
        />

        <span style={labelStyle}>字色</span>
        <label style={{ position: 'relative' }}>
          <input
            type="color"
            value={richTextConfig.fontColor}
            onChange={(e) => setRichTextConfig({ fontColor: e.target.value })}
            style={{
              position: 'absolute',
              opacity: 0,
              cursor: 'pointer',
              width: 28,
              height: 28,
            }}
          />
          <div style={colorPreviewStyle(richTextConfig.fontColor)} />
        </label>

        <span style={labelStyle}>对齐</span>
        <select
          value={richTextConfig.textAlign}
          onChange={(e) => setRichTextConfig({ textAlign: e.target.value as RichTextConfig['textAlign'] })}
          style={selectStyle}
        >
          {alignOptions.map((a) => (
            <option key={a.value} value={a.value}>
              {a.label}
            </option>
          ))}
        </select>
      </div>

      <div style={rowStyle}>
        <span style={labelStyle}>背景色</span>
        <label style={{ position: 'relative' }}>
          <input
            type="color"
            value={richTextConfig.backgroundColor === 'transparent' ? '#ffffff' : richTextConfig.backgroundColor}
            onChange={(e) => setRichTextConfig({ backgroundColor: e.target.value })}
            style={{
              position: 'absolute',
              opacity: 0,
              cursor: 'pointer',
              width: 28,
              height: 28,
            }}
          />
          <div style={colorPreviewStyle(richTextConfig.backgroundColor)} />
        </label>
        <button
          style={{
            height: 28,
            padding: '0 8px',
            border: '1px solid #d1d5db',
            borderRadius: 6,
            background: richTextConfig.backgroundColor === 'transparent' ? '#eff6ff' : '#ffffff',
            cursor: 'pointer',
            fontSize: 11,
          }}
          onClick={() =>
            setRichTextConfig({
              backgroundColor: richTextConfig.backgroundColor === 'transparent' ? '#ffffff' : 'transparent',
            })
          }
        >
          透明背景
        </button>

        <span style={labelStyle}>内边距</span>
        <input
          type="number"
          min="0"
          max="100"
          value={richTextConfig.padding}
          onChange={(e) => setRichTextConfig({ padding: Number(e.target.value) })}
          style={inputStyle}
        />
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        <span style={labelStyle}>HTML内容</span>
        <textarea
          value={richTextConfig.contentHtml}
          onChange={(e) => setRichTextConfig({ contentHtml: e.target.value })}
          style={textareaStyle}
          placeholder="支持 HTML 标签，如：<b>粗体</b> <i>斜体</i> <u>下划线</u> <br/> 换行..."
        />
      </div>
    </div>
  );
};

export default TextTool;
