'use client';

import { useState, useRef, useEffect, KeyboardEvent } from 'react';
import { X, Plus, Sparkles } from 'lucide-react';
import { cn } from '@/lib/utils';
import { api } from '@/lib/api';

interface TagInputProps {
  spaceId: string;
  documentId?: string;
  selectedTags: Array<{
    id: string;
    name: string;
    color: string | null;
  }>;
  onTagsChange: (tags: Array<{
    id: string;
    name: string;
    color: string | null;
  }>) => void;
  onAddTag?: (name: string, color?: string) => void;
  onRemoveTag?: (tagId: string) => void;
  placeholder?: string;
  maxTags?: number;
  showSuggestions?: boolean;
  className?: string;
}

const DEFAULT_COLORS = [
  '#ef4444',
  '#f97316',
  '#f59e0b',
  '#eab308',
  '#84cc16',
  '#22c55e',
  '#10b981',
  '#14b8a6',
  '#06b6d4',
  '#0ea5e9',
  '#3b82f6',
  '#6366f1',
  '#8b5cf6',
  '#a855f7',
  '#d946ef',
  '#ec4899',
  '#f43f5e',
];

export function TagInput({
  spaceId,
  documentId,
  selectedTags,
  onTagsChange,
  onAddTag,
  onRemoveTag,
  placeholder = '输入标签，按回车添加...',
  maxTags = 20,
  showSuggestions = true,
  className,
}: TagInputProps) {
  const [inputValue, setInputValue] = useState('');
  const [showColorPicker, setShowColorPicker] = useState(false);
  const [selectedColor, setSelectedColor] = useState<string | null>(null);
  const [suggestions, setSuggestions] = useState<Array<{
    id: string;
    name: string;
    color: string | null;
  }>>([]);
  const [highlightedIndex, setHighlightedIndex] = useState(-1);
  const inputRef = useRef<HTMLInputElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const autoSuggestQuery = api.tag.autoSuggest.useQuery(
    {
      spaceId,
      query: inputValue,
      limit: 8,
    },
    {
      enabled: inputValue.length > 0 && showSuggestions,
      staleTime: 30000,
    }
  );

  useEffect(() => {
    if (autoSuggestQuery.data) {
      setSuggestions(autoSuggestQuery.data.filter(
        (tag) => !selectedTags.some((st) => st.id === tag.id)
      ));
    }
  }, [autoSuggestQuery.data, selectedTags]);

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setShowColorPicker(false);
        setHighlightedIndex(-1);
      }
    }

    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      
      if (highlightedIndex >= 0 && suggestions[highlightedIndex]) {
        addExistingTag(suggestions[highlightedIndex]);
      } else if (inputValue.trim()) {
        addNewTag(inputValue.trim());
      }
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      setHighlightedIndex((prev) =>
        prev < suggestions.length - 1 ? prev + 1 : 0
      );
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setHighlightedIndex((prev) =>
        prev > 0 ? prev - 1 : suggestions.length - 1
      );
    } else if (e.key === 'Escape') {
      setHighlightedIndex(-1);
      setShowColorPicker(false);
    } else if (e.key === 'Backspace' && !inputValue && selectedTags.length > 0) {
      removeTag(selectedTags[selectedTags.length - 1].id);
    }
  };

  const addNewTag = async (name: string) => {
    if (selectedTags.length >= maxTags) return;
    
    const normalizedName = name.trim();
    if (!normalizedName) return;

    const existingTag = selectedTags.find(
      (t) => t.name.toLowerCase() === normalizedName.toLowerCase()
    );
    if (existingTag) return;

    const existingSuggestion = suggestions.find(
      (s) => s.name.toLowerCase() === normalizedName.toLowerCase()
    );

    if (existingSuggestion) {
      addExistingTag(existingSuggestion);
      return;
    }

    if (onAddTag) {
      const color = selectedColor || DEFAULT_COLORS[Math.floor(Math.random() * DEFAULT_COLORS.length)];
      onAddTag(normalizedName, color);
    }

    setInputValue('');
    setSelectedColor(null);
    setShowColorPicker(false);
  };

  const addExistingTag = (tag: {
    id: string;
    name: string;
    color: string | null;
  }) => {
    if (selectedTags.length >= maxTags) return;
    if (selectedTags.some((t) => t.id === tag.id)) return;

    onTagsChange([...selectedTags, tag]);
    setInputValue('');
    setHighlightedIndex(-1);
  };

  const removeTag = (tagId: string) => {
    const updatedTags = selectedTags.filter((t) => t.id !== tagId);
    onTagsChange(updatedTags);
    onRemoveTag?.(tagId);
  };

  const getContrastColor = (hexColor: string) => {
    const r = parseInt(hexColor.slice(1, 3), 16);
    const g = parseInt(hexColor.slice(3, 5), 16);
    const b = parseInt(hexColor.slice(5, 7), 16);
    const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    return luminance > 0.5 ? '#000000' : '#ffffff';
  };

  return (
    <div ref={containerRef} className={cn('relative', className)}>
      <div
        className={cn(
          'flex flex-wrap gap-2 p-2 min-h-[42px] bg-background border rounded-md focus-within:ring-2 focus-within:ring-ring focus-within:ring-offset-2',
          selectedTags.length >= maxTags && 'opacity-60'
        )}
        onClick={() => inputRef.current?.focus()}
      >
        {selectedTags.map((tag) => (
          <span
            key={tag.id}
            className="inline-flex items-center gap-1 px-2 py-1 rounded-full text-xs font-medium transition-all hover:opacity-90"
            style={{
              backgroundColor: tag.color || '#6b7280',
              color: tag.color ? getContrastColor(tag.color) : '#ffffff',
            }}
          >
            {tag.name}
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation();
                removeTag(tag.id);
              }}
              className="hover:bg-black/20 rounded-full p-0.5 transition-colors"
            >
              <X className="h-3 w-3" />
            </button>
          </span>
        ))}

        {selectedTags.length < maxTags && (
          <div className="flex items-center gap-2 flex-1 min-w-[120px]">
            <input
              ref={inputRef}
              type="text"
              value={inputValue}
              onChange={(e) => {
                setInputValue(e.target.value);
                setHighlightedIndex(-1);
              }}
              onKeyDown={handleKeyDown}
              onFocus={() => setHighlightedIndex(-1)}
              placeholder={selectedTags.length === 0 ? placeholder : ''}
              className="flex-1 bg-transparent border-none outline-none text-sm min-w-[100px]"
            />

            <button
              type="button"
              onClick={() => setShowColorPicker(!showColorPicker)}
              className={cn(
                'h-6 w-6 rounded-full border-2 transition-all',
                showColorPicker && 'ring-2 ring-ring ring-offset-1'
              )}
              style={{
                backgroundColor: selectedColor || '#ffffff',
                borderColor: selectedColor || '#d1d5db',
              }}
              title="选择标签颜色"
            />

            {inputValue.trim() && (
              <button
                type="button"
                onClick={() => addNewTag(inputValue.trim())}
                className="p-1 rounded-md hover:bg-muted transition-colors"
              >
                <Plus className="h-4 w-4" />
              </button>
            )}
          </div>
        )}
      </div>

      {showColorPicker && (
        <div className="absolute top-full left-0 mt-2 p-2 bg-background border rounded-md shadow-lg z-50">
          <div className="grid grid-cols-6 gap-1">
            {DEFAULT_COLORS.map((color) => (
              <button
                key={color}
                type="button"
                onClick={() => {
                  setSelectedColor(color);
                  setShowColorPicker(false);
                  inputRef.current?.focus();
                }}
                className={cn(
                  'h-6 w-6 rounded-full transition-all hover:scale-110',
                  selectedColor === color && 'ring-2 ring-ring ring-offset-1'
                )}
                style={{ backgroundColor: color }}
              />
            ))}
          </div>
        </div>
      )}

      {suggestions.length > 0 && inputValue.length > 0 && (
        <div className="absolute top-full left-0 right-0 mt-1 bg-background border rounded-md shadow-lg z-50 max-h-60 overflow-y-auto">
          {suggestions.map((suggestion, index) => (
            <button
              key={suggestion.id}
              type="button"
              onClick={() => addExistingTag(suggestion)}
              className={cn(
                'w-full px-3 py-2 text-left text-sm flex items-center gap-2 transition-colors',
                highlightedIndex === index
                  ? 'bg-muted'
                  : 'hover:bg-muted/50'
              )}
            >
              <span
                className="h-3 w-3 rounded-full"
                style={{ backgroundColor: suggestion.color || '#6b7280' }}
              />
              <span className="flex-1">{suggestion.name}</span>
              <Sparkles className="h-3 w-3 text-muted-foreground" />
            </button>
          ))}
        </div>
      )}

      {selectedTags.length >= maxTags && (
        <p className="text-xs text-muted-foreground mt-1">
          最多可添加 {maxTags} 个标签
        </p>
      )}
    </div>
  );
}
