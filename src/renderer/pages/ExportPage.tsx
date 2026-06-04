import React, { useState } from 'react';
import { IPC_CHANNELS } from '@shared/constants/ipcChannels';
import type { ExportProgress } from '@main/services/ExportService';

export const ExportPage: React.FC = () => {
  const [siteTitle, setSiteTitle] = useState('My Knowledge Base');
  const [siteDescription, setSiteDescription] = useState('');
  const [includeDrafts, setIncludeDrafts] = useState(false);
  const [customDomain, setCustomDomain] = useState('');
  const [isExporting, setIsExporting] = useState(false);
  const [progress, setProgress] = useState<ExportProgress | null>(null);
  const [exportResult, setExportResult] = useState<{ success: boolean; message: string } | null>(null);

  const handleExport = async () => {
    setIsExporting(true);
    setProgress(null);
    setExportResult(null);

    const progressHandler = (_event: Electron.IpcRendererEvent, data: ExportProgress) => {
      setProgress(data);
    };

    window.electron.ipc.on('export:progress', progressHandler);

    try {
      const result = await window.electron.ipc.invoke<any>(
        IPC_CHANNELS.EXPORT.STATIC_SITE,
        {
          siteTitle,
          siteDescription,
          includeDrafts,
          customDomain,
        }
      );

      if (result?.success) {
        setExportResult({
          success: true,
          message: `导出成功！文件已保存到: ${result.data.outputPath}`,
        });
      } else {
        setExportResult({
          success: false,
          message: result?.error || '导出失败',
        });
      }
    } catch (error) {
      setExportResult({
        success: false,
        message: error instanceof Error ? error.message : '导出失败',
      });
    } finally {
      setIsExporting(false);
      window.electron.ipc.removeListener('export:progress', progressHandler);
    }
  };

  return (
    <div className="h-full overflow-y-auto p-8">
      <div className="max-w-2xl mx-auto">
        <h1 className="text-2xl font-bold mb-6">导出静态网站</h1>

        <div className="card p-6 mb-6">
          <h2 className="text-lg font-semibold mb-4">📤 网站导出设置</h2>

          <div className="space-y-4">
            <div>
              <label className="block text-sm font-medium mb-1.5">网站标题</label>
              <input
                type="text"
                value={siteTitle}
                onChange={(e) => setSiteTitle(e.target.value)}
                className="input w-full"
                placeholder="My Knowledge Base"
              />
            </div>

            <div>
              <label className="block text-sm font-medium mb-1.5">网站描述</label>
              <textarea
                value={siteDescription}
                onChange={(e) => setSiteDescription(e.target.value)}
                className="input w-full h-24 resize-none"
                placeholder="简要描述这个知识库..."
              />
            </div>

            <div>
              <label className="block text-sm font-medium mb-1.5">自定义域名 (可选)</label>
              <input
                type="text"
                value={customDomain}
                onChange={(e) => setCustomDomain(e.target.value)}
                className="input w-full"
                placeholder="https://example.com"
              />
              <p className="text-xs text-gray-500 mt-1">
                用于生成 sitemap.xml 和 robots.txt
              </p>
            </div>

            <div className="flex items-center gap-2">
              <input
                type="checkbox"
                id="includeDrafts"
                checked={includeDrafts}
                onChange={(e) => setIncludeDrafts(e.target.checked)}
                className="w-4 h-4"
              />
              <label htmlFor="includeDrafts" className="text-sm">
                包含草稿文档
              </label>
            </div>
          </div>
        </div>

        <div className="card p-6 mb-6">
          <h2 className="text-lg font-semibold mb-4">📋 导出内容</h2>
          <ul className="space-y-2 text-sm text-gray-600 dark:text-gray-400">
            <li className="flex items-center gap-2">
              <span className="text-green-500">✓</span>
              所有文档页面（Markdown 转 HTML）
            </li>
            <li className="flex items-center gap-2">
              <span className="text-green-500">✓</span>
              侧边栏文档树导航
            </li>
            <li className="flex items-center gap-2">
              <span className="text-green-500">✓</span>
              知识图谱可视化页面
            </li>
            <li className="flex items-center gap-2">
              <span className="text-green-500">✓</span>
              内部链接自动转换
            </li>
            <li className="flex items-center gap-2">
              <span className="text-green-500">✓</span>
              反向链接显示
            </li>
            <li className="flex items-center gap-2">
              <span className="text-green-500">✓</span>
              全文搜索（前端）
            </li>
            <li className="flex items-center gap-2">
              <span className="text-green-500">✓</span>
              亮色/暗色主题切换
            </li>
          </ul>
        </div>

        {progress && (
          <div className="card p-6 mb-6">
            <h3 className="font-medium mb-2">导出进度</h3>
            <div className="w-full bg-gray-200 dark:bg-gray-700 rounded-full h-3 mb-2">
              <div
                className="bg-blue-500 h-3 rounded-full transition-all duration-300"
                style={{ width: `${(progress.current / progress.total) * 100}%` }}
              />
            </div>
            <p className="text-sm text-gray-600 dark:text-gray-400">
              {progress.current} / {progress.total} - {progress.message}
            </p>
          </div>
        )}

        {exportResult && (
          <div
            className={`card p-4 mb-6 ${
              exportResult.success
                ? 'bg-green-50 dark:bg-green-900/20 border-green-200 dark:border-green-800'
                : 'bg-red-50 dark:bg-red-900/20 border-red-200 dark:border-red-800'
            }`}
          >
            <p
              className={exportResult.success ? 'text-green-700 dark:text-green-300' : 'text-red-700 dark:text-red-300'}
            >
              {exportResult.success ? '✅ ' : '❌ '}
              {exportResult.message}
            </p>
          </div>
        )}

        <button
          onClick={handleExport}
          disabled={isExporting}
          className="btn btn-primary w-full flex items-center justify-center gap-2"
        >
          {isExporting ? (
            <>
              <span className="animate-spin">⏳</span>
              导出中...
            </>
          ) : (
            <>
              <span>📦</span>
              开始导出
            </>
          )}
        </button>

        <div className="mt-6 text-sm text-gray-500 dark:text-gray-400">
          <h3 className="font-medium mb-2">💡 使用提示</h3>
          <ul className="list-disc list-inside space-y-1">
            <li>导出的网站是纯静态的，不依赖任何后端</li>
            <li>可以直接部署到 GitHub Pages、Netlify、Vercel 等平台</li>
            <li>知识图谱使用 D3.js 实现，支持节点拖拽</li>
            <li>所有样式和脚本都已内联，无需额外构建</li>
          </ul>
        </div>
      </div>
    </div>
  );
};
