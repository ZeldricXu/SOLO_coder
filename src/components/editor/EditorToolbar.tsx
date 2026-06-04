'use client';

import { useEditor } from '@tiptap/react';
import {
  Bold,
  Italic,
  Underline,
  Strikethrough,
  Heading1,
  Heading2,
  Heading3,
  Heading4,
  Heading5,
  Heading6,
  List,
  ListOrdered,
  CheckSquare,
  Link,
  Image,
  Code,
  Table,
  Minus,
  AlignLeft,
  AlignCenter,
  AlignRight,
  AlignJustify,
  Undo,
  Redo,
  Quote,
  ChevronDown,
  Plus,
  Trash2,
  Merge,
  Spline,
  RowsIcon,
  Columns,
  Header,
  Calculator,
} from 'lucide-react';
import * as React from 'react';
import { useState, useCallback } from 'react';

interface EditorToolbarProps {
  editor: ReturnType<typeof useEditor> | undefined;
  isEditable?: boolean;
  onInsertTable?: (rows: number, cols: number, withHeader: boolean) => void;
  onFormula?: (formula: string, range: string) => void;
}

interface ToolbarButtonProps {
  onClick: () => void;
  isActive?: boolean;
  disabled?: boolean;
  children: React.ReactNode;
  title?: string;
}

function ToolbarButton({ onClick, isActive, disabled, children, title }: ToolbarButtonProps) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={title}
      className={`p-2 rounded hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed transition-colors ${
        isActive ? 'bg-gray-200 text-blue-600' : 'text-gray-700'
      }`}
    >
      {children}
    </button>
  );
}

interface DropdownItem {
  label: string;
  onClick: () => void;
  isActive?: boolean;
  icon?: React.ReactNode;
  disabled?: boolean;
}

function ToolbarDropdown({
  trigger,
  items,
  title,
  disabled,
}: {
  trigger: React.ReactNode;
  items: DropdownItem[];
  title?: string;
  disabled?: boolean;
}) {
  const [isOpen, setIsOpen] = useState(false);

  const handleSelect = useCallback((item: DropdownItem) => {
    if (!item.disabled) {
      item.onClick();
    }
    setIsOpen(false);
  }, []);

  return (
    <div className="relative">
      <button
        onClick={() => !disabled && setIsOpen(!isOpen)}
        disabled={disabled}
        title={title}
        className="flex items-center gap-1 p-2 rounded hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed transition-colors text-gray-700"
      >
        {trigger}
        <ChevronDown size={14} />
      </button>
      {isOpen && (
        <>
          <div
            className="fixed inset-0 z-10"
            onClick={() => setIsOpen(false)}
          />
          <div className="absolute left-0 top-full mt-1 z-20 bg-white border border-gray-200 rounded-lg shadow-lg py-1 min-w-[180px]">
            {items.map((item, index) => (
              <button
                key={index}
                onClick={() => handleSelect(item)}
                disabled={item.disabled}
                className={`w-full flex items-center gap-2 px-3 py-2 text-left hover:bg-gray-100 transition-colors ${
                  item.isActive ? 'bg-blue-50 text-blue-600' : 'text-gray-700'
                } ${item.disabled ? 'opacity-50 cursor-not-allowed' : ''}`}
              >
                {item.icon}
                <span>{item.label}</span>
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

function InsertTableDialog({
  isOpen,
  onClose,
  onInsert,
}: {
  isOpen: boolean;
  onClose: () => void;
  onInsert: (rows: number, cols: number, withHeader: boolean) => void;
}) {
  const [rows, setRows] = useState(3);
  const [cols, setCols] = useState(3);
  const [withHeader, setWithHeader] = useState(true);

  if (!isOpen) return null;

  return (
    <>
      <div className="fixed inset-0 bg-black/50 z-50" onClick={onClose} />
      <div className="fixed top-1/2 left-1/2 transform -translate-x-1/2 -translate-y-1/2 bg-white rounded-lg shadow-xl p-6 z-50 min-w-[320px]">
        <h3 className="text-lg font-semibold mb-4">插入表格</h3>
        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              行数
            </label>
            <input
              type="number"
              min="1"
              max="20"
              value={rows}
              onChange={(e) => setRows(Math.min(20, Math.max(1, parseInt(e.target.value) || 1)))}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              列数
            </label>
            <input
              type="number"
              min="1"
              max="10"
              value={cols}
              onChange={(e) => setCols(Math.min(10, Math.max(1, parseInt(e.target.value) || 1)))}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div className="flex items-center">
            <input
              type="checkbox"
              id="withHeader"
              checked={withHeader}
              onChange={(e) => setWithHeader(e.target.checked)}
              className="w-4 h-4 text-blue-600 rounded focus:ring-blue-500"
            />
            <label htmlFor="withHeader" className="ml-2 text-sm text-gray-700">
              第一行作为表头
            </label>
          </div>
          <div className="flex gap-3 mt-6">
            <button
              onClick={onClose}
              className="flex-1 px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50 transition-colors"
            >
              取消
            </button>
            <button
              onClick={() => {
                onInsert(rows, cols, withHeader);
                onClose();
              }}
              className="flex-1 px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 transition-colors"
            >
              插入
            </button>
          </div>
        </div>
      </div>
    </>
  );
}

function FormulaDialog({
  isOpen,
  onClose,
  onApply,
}: {
  isOpen: boolean;
  onClose: () => void;
  onApply: (formula: string, columnIndex: number) => void;
}) {
  const [formula, setFormula] = useState('SUM');
  const [columnIndex, setColumnIndex] = useState(0);

  if (!isOpen) return null;

  const formulas = [
    { value: 'SUM', label: '求和 (SUM)' },
    { value: 'AVERAGE', label: '平均值 (AVERAGE)' },
    { value: 'MAX', label: '最大值 (MAX)' },
    { value: 'MIN', label: '最小值 (MIN)' },
    { value: 'COUNT', label: '计数 (COUNT)' },
  ];

  return (
    <>
      <div className="fixed inset-0 bg-black/50 z-50" onClick={onClose} />
      <div className="fixed top-1/2 left-1/2 transform -translate-x-1/2 -translate-y-1/2 bg-white rounded-lg shadow-xl p-6 z-50 min-w-[320px]">
        <h3 className="text-lg font-semibold mb-4">插入公式</h3>
        <div className="space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              公式类型
            </label>
            <select
              value={formula}
              onChange={(e) => setFormula(e.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
            >
              {formulas.map((f) => (
                <option key={f.value} value={f.value}>
                  {f.label}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              目标列（从0开始）
            </label>
            <input
              type="number"
              min="0"
              value={columnIndex}
              onChange={(e) => setColumnIndex(Math.max(0, parseInt(e.target.value) || 0))}
              className="w-full px-3 py-2 border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            <p className="text-xs text-gray-500 mt-1">
              公式将应用于该列的所有数值单元格（表头除外）
            </p>
          </div>
          <div className="flex gap-3 mt-6">
            <button
              onClick={onClose}
              className="flex-1 px-4 py-2 border border-gray-300 rounded-md text-gray-700 hover:bg-gray-50 transition-colors"
            >
              取消
            </button>
            <button
              onClick={() => {
                onApply(formula, columnIndex);
                onClose();
              }}
              className="flex-1 px-4 py-2 bg-blue-600 text-white rounded-md hover:bg-blue-700 transition-colors"
            >
              应用
            </button>
          </div>
        </div>
      </div>
    </>
  );
}

export function EditorToolbar({ editor, isEditable = true }: EditorToolbarProps) {
  const [showTableDialog, setShowTableDialog] = useState(false);
  const [showFormulaDialog, setShowFormulaDialog] = useState(false);

  if (!editor) {
    return null;
  }

  const isInTable = editor.isActive('table');

  const setLink = useCallback(() => {
    const previousUrl = editor.getAttributes('link').href;
    const url = window.prompt('链接地址:', previousUrl);

    if (url === null) {
      return;
    }

    if (url === '') {
      editor.chain().focus().extendMarkRange('link').unsetLink().run();
      return;
    }

    editor.chain().focus().extendMarkRange('link').setLink({ href: url }).run();
  }, [editor]);

  const setImage = useCallback(() => {
    const url = window.prompt('图片地址:');
    if (url) {
      editor.chain().focus().setImage({ src: url }).run();
    }
  }, [editor]);

  const handleInsertTable = useCallback((rows: number, cols: number, withHeader: boolean) => {
    editor
      .chain()
      .focus()
      .insertTable({ rows, cols, withHeaderRow: withHeader })
      .run();
  }, [editor]);

  const handleApplyFormula = useCallback((formula: string, columnIndex: number) => {
    const { state } = editor;
    const { doc, selection } = state;

    doc.descendants((node, pos) => {
      if (node.type.name === 'table') {
        const rows: any[] = [];
        node.descendants((row, rowPos) => {
          if (row.type.name === 'tableRow') {
            const cells: any[] = [];
            row.descendants((cell) => {
              if (cell.type.name === 'tableCell' || cell.type.name === 'tableHeader') {
                let cellText = '';
                cell.forEach((child) => {
                  cellText += child.textContent || '';
                });
                cells.push({ text: cellText.trim() });
              }
            });
            rows.push(cells);
          }
        });

        if (rows.length > 1) {
          const dataRows = rows.slice(1);
          const values = dataRows
            .map((row) => parseFloat(row[columnIndex]?.text || ''))
            .filter((v) => !isNaN(v));

          let result = 0;
          switch (formula) {
            case 'SUM':
              result = values.reduce((a, b) => a + b, 0);
              break;
            case 'AVERAGE':
              result = values.length > 0 ? values.reduce((a, b) => a + b, 0) / values.length : 0;
              break;
            case 'MAX':
              result = values.length > 0 ? Math.max(...values) : 0;
              break;
            case 'MIN':
              result = values.length > 0 ? Math.min(...values) : 0;
              break;
            case 'COUNT':
              result = values.length;
              break;
          }

          const resultText = formula === 'AVERAGE' ? result.toFixed(2) : result.toString();
          const lastRowIndex = rows.length - 1;
          let targetCellPos = pos + 1;
          let currentRow = 0;

          node.descendants((row, rowPos) => {
            if (row.type.name === 'tableRow') {
              if (currentRow === lastRowIndex) {
                let currentCol = 0;
                row.descendants((cell, cellPos) => {
                  if (cell.type.name === 'tableCell' || cell.type.name === 'tableHeader') {
                    if (currentCol === columnIndex) {
                      const cellStartPos = pos + 1 + rowPos + cellPos + 1;
                      editor
                        .chain()
                        .focus()
                        .setTextSelection(cellStartPos)
                        .selectAll()
                        .deleteSelection()
                        .insertContent(`${resultText}`)
                        .run();
                    }
                    currentCol++;
                  }
                });
              }
              currentRow++;
            }
          });
        }
      }
    });
  }, [editor]);

  const headingItems: DropdownItem[] = [
    { label: '正文', onClick: () => editor.chain().focus().setParagraph().run(), isActive: editor.isActive('paragraph'), icon: <p className="w-5 h-5 text-center">¶</p> },
    { label: '标题 1', onClick: () => editor.chain().focus().toggleHeading({ level: 1 }).run(), isActive: editor.isActive('heading', { level: 1 }), icon: <Heading1 size={18} /> },
    { label: '标题 2', onClick: () => editor.chain().focus().toggleHeading({ level: 2 }).run(), isActive: editor.isActive('heading', { level: 2 }), icon: <Heading2 size={18} /> },
    { label: '标题 3', onClick: () => editor.chain().focus().toggleHeading({ level: 3 }).run(), isActive: editor.isActive('heading', { level: 3 }), icon: <Heading3 size={18} /> },
    { label: '标题 4', onClick: () => editor.chain().focus().toggleHeading({ level: 4 }).run(), isActive: editor.isActive('heading', { level: 4 }), icon: <Heading4 size={18} /> },
    { label: '标题 5', onClick: () => editor.chain().focus().toggleHeading({ level: 5 }).run(), isActive: editor.isActive('heading', { level: 5 }), icon: <Heading5 size={18} /> },
    { label: '标题 6', onClick: () => editor.chain().focus().toggleHeading({ level: 6 }).run(), isActive: editor.isActive('heading', { level: 6 }), icon: <Heading6 size={18} /> },
  ];

  const insertItems: DropdownItem[] = [
    { label: '链接', onClick: setLink, icon: <Link size={18} /> },
    { label: '图片', onClick: setImage, icon: <Image size={18} /> },
    { label: '代码块', onClick: () => editor.chain().focus().toggleCodeBlock().run(), isActive: editor.isActive('codeBlock'), icon: <Code size={18} /> },
    { label: '分隔线', onClick: () => editor.chain().focus().setHorizontalRule().run(), icon: <Minus size={18} /> },
    { label: '引用', onClick: () => editor.chain().focus().toggleBlockquote().run(), isActive: editor.isActive('blockquote'), icon: <Quote size={18} /> },
  ];

  const tableItems: DropdownItem[] = [
    { label: '插入表格...', onClick: () => setShowTableDialog(true), icon: <Table size={18} /> },
    { label: '在上方插入行', onClick: () => editor.chain().focus().addRowBefore().run(), icon: <Plus size={18} />, disabled: !isInTable },
    { label: '在下方插入行', onClick: () => editor.chain().focus().addRowAfter().run(), icon: <RowsIcon size={18} />, disabled: !isInTable },
    { label: '删除行', onClick: () => editor.chain().focus().deleteRow().run(), icon: <Trash2 size={18} />, disabled: !isInTable },
    { label: '在左侧插入列', onClick: () => editor.chain().focus().addColumnBefore().run(), icon: <Plus size={18} />, disabled: !isInTable },
    { label: '在右侧插入列', onClick: () => editor.chain().focus().addColumnAfter().run(), icon: <Columns size={18} />, disabled: !isInTable },
    { label: '删除列', onClick: () => editor.chain().focus().deleteColumn().run(), icon: <Trash2 size={18} />, disabled: !isInTable },
    { label: '合并单元格', onClick: () => editor.chain().focus().mergeCells().run(), icon: <Merge size={18} />, disabled: !isInTable },
    { label: '拆分单元格', onClick: () => editor.chain().focus().splitCell().run(), icon: <Spline size={18} />, disabled: !isInTable },
    { label: '切换表头', onClick: () => editor.chain().focus().toggleHeaderRow().run(), icon: <Header size={18} />, disabled: !isInTable },
    { label: '删除表格', onClick: () => editor.chain().focus().deleteTable().run(), icon: <Trash2 size={18} />, disabled: !isInTable },
  ];

  const tableToolsItems: DropdownItem[] = [
    { label: '公式计算...', onClick: () => setShowFormulaDialog(true), icon: <Calculator size={18} />, disabled: !isInTable },
    { label: '在上方插入行', onClick: () => editor.chain().focus().addRowBefore().run(), icon: <Plus size={18} />, disabled: !isInTable },
    { label: '在下方插入行', onClick: () => editor.chain().focus().addRowAfter().run(), icon: <RowsIcon size={18} />, disabled: !isInTable },
    { label: '在左侧插入列', onClick: () => editor.chain().focus().addColumnBefore().run(), icon: <Plus size={18} />, disabled: !isInTable },
    { label: '在右侧插入列', onClick: () => editor.chain().focus().addColumnAfter().run(), icon: <Columns size={18} />, disabled: !isInTable },
    { label: '合并单元格', onClick: () => editor.chain().focus().mergeCells().run(), icon: <Merge size={18} />, disabled: !isInTable },
  ];

  const alignItems: DropdownItem[] = [
    { label: '左对齐', onClick: () => editor.chain().focus().setTextAlign('left').run(), isActive: editor.isActive({ textAlign: 'left' }), icon: <AlignLeft size={18} /> },
    { label: '居中', onClick: () => editor.chain().focus().setTextAlign('center').run(), isActive: editor.isActive({ textAlign: 'center' }), icon: <AlignCenter size={18} /> },
    { label: '右对齐', onClick: () => editor.chain().focus().setTextAlign('right').run(), isActive: editor.isActive({ textAlign: 'right' }), icon: <AlignRight size={18} /> },
    { label: '两端对齐', onClick: () => editor.chain().focus().setTextAlign('justify').run(), isActive: editor.isActive({ textAlign: 'justify' }), icon: <AlignJustify size={18} /> },
  ];

  return (
    <>
      <div className="flex flex-wrap items-center gap-1 p-2 border-b border-gray-200 bg-gray-50 rounded-t-lg">
        <div className="flex items-center gap-1 pr-2 border-r border-gray-200">
          <ToolbarButton
            onClick={() => editor.chain().focus().undo().run()}
            disabled={!editor.can().undo() || !isEditable}
            title="撤销 (Ctrl+Z)"
          >
            <Undo size={18} />
          </ToolbarButton>
          <ToolbarButton
            onClick={() => editor.chain().focus().redo().run()}
            disabled={!editor.can().redo() || !isEditable}
            title="重做 (Ctrl+Y)"
          >
            <Redo size={18} />
          </ToolbarButton>
        </div>

        <div className="flex items-center gap-1 px-2 border-r border-gray-200">
          <ToolbarDropdown
            trigger={<Heading2 size={18} />}
            items={headingItems}
            title="标题样式"
            disabled={!isEditable}
          />
        </div>

        <div className="flex items-center gap-1 px-2 border-r border-gray-200">
          <ToolbarButton
            onClick={() => editor.chain().focus().toggleBold().run()}
            isActive={editor.isActive('bold')}
            disabled={!isEditable}
            title="粗体 (Ctrl+B)"
          >
            <Bold size={18} />
          </ToolbarButton>
          <ToolbarButton
            onClick={() => editor.chain().focus().toggleItalic().run()}
            isActive={editor.isActive('italic')}
            disabled={!isEditable}
            title="斜体 (Ctrl+I)"
          >
            <Italic size={18} />
          </ToolbarButton>
          <ToolbarButton
            onClick={() => editor.chain().focus().toggleUnderline().run()}
            isActive={editor.isActive('underline')}
            disabled={!isEditable}
            title="下划线 (Ctrl+U)"
          >
            <Underline size={18} />
          </ToolbarButton>
          <ToolbarButton
            onClick={() => editor.chain().focus().toggleStrike().run()}
            isActive={editor.isActive('strike')}
            disabled={!isEditable}
            title="删除线"
          >
            <Strikethrough size={18} />
          </ToolbarButton>
          <ToolbarButton
            onClick={() => editor.chain().focus().toggleCode().run()}
            isActive={editor.isActive('code')}
            disabled={!isEditable}
            title="行内代码"
          >
            <Code size={18} />
          </ToolbarButton>
        </div>

        <div className="flex items-center gap-1 px-2 border-r border-gray-200">
          <ToolbarButton
            onClick={() => editor.chain().focus().toggleBulletList().run()}
            isActive={editor.isActive('bulletList')}
            disabled={!isEditable}
            title="无序列表"
          >
            <List size={18} />
          </ToolbarButton>
          <ToolbarButton
            onClick={() => editor.chain().focus().toggleOrderedList().run()}
            isActive={editor.isActive('orderedList')}
            disabled={!isEditable}
            title="有序列表"
          >
            <ListOrdered size={18} />
          </ToolbarButton>
          <ToolbarButton
            onClick={() => editor.chain().focus().toggleTaskList().run()}
            isActive={editor.isActive('taskList')}
            disabled={!isEditable}
            title="任务列表"
          >
            <CheckSquare size={18} />
          </ToolbarButton>
        </div>

        <div className="flex items-center gap-1 px-2 border-r border-gray-200">
          <ToolbarDropdown
            trigger={<Table size={18} />}
            items={isInTable ? tableItems : [{ label: '插入表格...', onClick: () => setShowTableDialog(true), icon: <Table size={18} /> }]}
            title="表格"
            disabled={!isEditable}
          />
        </div>

        {isInTable && (
          <div className="flex items-center gap-1 px-2 border-r border-gray-200">
            <ToolbarDropdown
              trigger={<Calculator size={18} />}
              items={tableToolsItems}
              title="表格工具"
              disabled={!isEditable}
            />
          </div>
        )}

        <div className="flex items-center gap-1 px-2 border-r border-gray-200">
          <ToolbarDropdown
            trigger={<Link size={18} />}
            items={insertItems}
            title="插入"
            disabled={!isEditable}
          />
        </div>

        <div className="flex items-center gap-1 px-2 border-r border-gray-200">
          <ToolbarDropdown
            trigger={<AlignLeft size={18} />}
            items={alignItems}
            title="对齐方式"
            disabled={!isEditable}
          />
        </div>

        <div className="flex items-center gap-1 px-2">
          <ToolbarButton
            onClick={() => editor.chain().focus().toggleBlockquote().run()}
            isActive={editor.isActive('blockquote')}
            disabled={!isEditable}
            title="引用"
          >
            <Quote size={18} />
          </ToolbarButton>
          <ToolbarButton
            onClick={() => editor.chain().focus().setHorizontalRule().run()}
            disabled={!isEditable}
            title="分隔线"
          >
            <Minus size={18} />
          </ToolbarButton>
        </div>
      </div>

      <InsertTableDialog
        isOpen={showTableDialog}
        onClose={() => setShowTableDialog(false)}
        onInsert={handleInsertTable}
      />

      <FormulaDialog
        isOpen={showFormulaDialog}
        onClose={() => setShowFormulaDialog(false)}
        onApply={handleApplyFormula}
      />
    </>
  );
}
