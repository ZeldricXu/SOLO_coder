import { useNovelStore, Volume, Chapter } from "@/store/novelStore";
import { ChevronRight, ChevronDown, Plus, MoreVertical, FileText } from "lucide-react";
import { useState, useRef, useEffect } from "react";

interface VolumeItemProps {
  volume: Volume;
  isExpanded: boolean;
  onToggle: () => void;
  onAddChapter: (volumeId: string) => void;
  onDeleteVolume: (volumeId: string) => void;
  onEditVolumeTitle: (volumeId: string, title: string) => void;
}

function VolumeItem({
  volume,
  isExpanded,
  onToggle,
  onAddChapter,
  onDeleteVolume,
  onEditVolumeTitle,
}: VolumeItemProps) {
  const [isEditing, setIsEditing] = useState(false);
  const [editValue, setEditValue] = useState(volume.title);
  const inputRef = useRef<HTMLInputElement>(null);
  const [showMenu, setShowMenu] = useState(false);

  const { currentVolumeId, currentChapterId, selectChapter, createChapter } =
    useNovelStore();

  useEffect(() => {
    if (isEditing && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [isEditing]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter") {
      onEditVolumeTitle(volume.id, editValue);
      setIsEditing(false);
    } else if (e.key === "Escape") {
      setEditValue(volume.title);
      setIsEditing(false);
    }
  };

  const handleBlur = () => {
    onEditVolumeTitle(volume.id, editValue);
    setIsEditing(false);
  };

  return (
    <div className="mb-2">
      <div
        className={`flex items-center px-2 py-1.5 rounded-md cursor-pointer group hover:bg-muted ${
          currentVolumeId === volume.id && !currentChapterId ? "bg-muted" : ""
        }`}
        onClick={() => {
          onToggle();
          if (volume.chapters.length === 0) {
            selectChapter(volume.id, "");
          }
        }}
      >
        <button
          className="p-0.5 hover:bg-muted-foreground/10 rounded"
          onClick={(e) => {
            e.stopPropagation();
            onToggle();
          }}
        >
          {isExpanded ? (
            <ChevronDown className="w-4 h-4 text-muted-foreground" />
          ) : (
            <ChevronRight className="w-4 h-4 text-muted-foreground" />
          )}
        </button>

        {isEditing ? (
          <input
            ref={inputRef}
            type="text"
            value={editValue}
            onChange={(e) => setEditValue(e.target.value)}
            onKeyDown={handleKeyDown}
            onBlur={handleBlur}
            className="flex-1 ml-2 px-1 py-0.5 text-sm bg-background border border-input rounded focus:outline-none focus:ring-1 focus:ring-ring"
            onClick={(e) => e.stopPropagation()}
          />
        ) : (
          <span className="ml-2 text-sm font-medium flex-1 truncate">
            {volume.title}
          </span>
        )}

        <div className="flex items-center opacity-0 group-hover:opacity-100 transition-opacity">
          <button
            className="p-1 hover:bg-muted-foreground/10 rounded"
            onClick={(e) => {
              e.stopPropagation();
              onAddChapter(volume.id);
            }}
            title="添加章节"
          >
            <Plus className="w-4 h-4 text-muted-foreground" />
          </button>
          <button
            className="p-1 hover:bg-muted-foreground/10 rounded"
            onClick={(e) => {
              e.stopPropagation();
              setIsEditing(true);
            }}
            title="重命名"
          >
            <FileText className="w-4 h-4 text-muted-foreground" />
          </button>
        </div>
      </div>

      {isExpanded && (
        <div className="ml-6 mt-1">
          {volume.chapters.map((chapter) => (
            <ChapterItem
              key={chapter.id}
              chapter={chapter}
              volumeId={volume.id}
            />
          ))}
          {volume.chapters.length === 0 && (
            <div className="px-2 py-2 text-xs text-muted-foreground italic">
              暂无章节，点击 + 添加
            </div>
          )}
        </div>
      )}
    </div>
  );
}

interface ChapterItemProps {
  chapter: Chapter;
  volumeId: string;
}

function ChapterItem({ chapter, volumeId }: ChapterItemProps) {
  const { currentChapterId, selectChapter, updateChapterTitle, deleteChapter } =
    useNovelStore();
  const [isEditing, setIsEditing] = useState(false);
  const [editValue, setEditValue] = useState(chapter.title);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (isEditing && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [isEditing]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter") {
      updateChapterTitle(chapter.id, editValue);
      setIsEditing(false);
    } else if (e.key === "Escape") {
      setEditValue(chapter.title);
      setIsEditing(false);
    }
  };

  const handleBlur = () => {
    updateChapterTitle(chapter.id, editValue);
    setIsEditing(false);
  };

  const isActive = currentChapterId === chapter.id;

  return (
    <div
      className={`flex items-center px-2 py-1.5 rounded-md cursor-pointer group ${
        isActive ? "bg-primary/10 text-primary" : "hover:bg-muted"
      }`}
      onClick={() => selectChapter(volumeId, chapter.id)}
    >
      <FileText className="w-4 h-4 text-muted-foreground flex-shrink-0" />

      {isEditing ? (
        <input
          ref={inputRef}
          type="text"
          value={editValue}
          onChange={(e) => setEditValue(e.target.value)}
          onKeyDown={handleKeyDown}
          onBlur={handleBlur}
          className="flex-1 ml-2 px-1 py-0.5 text-sm bg-background border border-input rounded focus:outline-none focus:ring-1 focus:ring-ring"
          onClick={(e) => e.stopPropagation()}
        />
      ) : (
        <span className="ml-2 text-sm flex-1 truncate">{chapter.title}</span>
      )}

      <div className="flex items-center opacity-0 group-hover:opacity-100 transition-opacity">
        <button
          className="p-1 hover:bg-muted-foreground/10 rounded"
          onClick={(e) => {
            e.stopPropagation();
            setIsEditing(true);
          }}
          title="重命名"
        >
          <FileText className="w-3 h-3 text-muted-foreground" />
        </button>
        <button
          className="p-1 hover:bg-red-100 rounded"
          onClick={(e) => {
            e.stopPropagation();
            if (confirm("确定删除这一章吗？")) {
              deleteChapter(volumeId, chapter.id);
            }
          }}
          title="删除"
        >
          <MoreVertical className="w-3 h-3 text-red-500" />
        </button>
      </div>
    </div>
  );
}

export function DirectoryTree() {
  const { currentNovel, currentVolumeId, createVolume, createChapter } =
    useNovelStore();
  const [expandedVolumes, setExpandedVolumes] = useState<Set<string>>(
    new Set(currentNovel?.volumes.map((v) => v.id) || [])
  );

  const toggleVolume = (volumeId: string) => {
    setExpandedVolumes((prev) => {
      const next = new Set(prev);
      if (next.has(volumeId)) {
        next.delete(volumeId);
      } else {
        next.add(volumeId);
      }
      return next;
    });
  };

  const handleAddVolume = () => {
    const title = prompt("请输入卷名：", `第${(currentNovel?.volumes.length || 0) + 1}卷`);
    if (title) {
      createVolume(title);
    }
  };

  const handleAddChapter = (volumeId: string) => {
    const volume = currentNovel?.volumes.find((v) => v.id === volumeId);
    const title = prompt(
      "请输入章节名：",
      `第${(volume?.chapters.length || 0) + 1}章`
    );
    if (title) {
      createChapter(volumeId, title);
      setExpandedVolumes((prev) => new Set(prev).add(volumeId));
    }
  };

  if (!currentNovel) {
    return (
      <div className="flex flex-col items-center justify-center h-full text-muted-foreground">
        <FileText className="w-12 h-12 mb-4 opacity-50" />
        <p className="text-sm">暂无小说</p>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full bg-card border-r border-border">
      <div className="p-4 border-b border-border">
        <h2 className="text-lg font-semibold truncate">{currentNovel.title}</h2>
        <p className="text-xs text-muted-foreground mt-1 line-clamp-2">
          {currentNovel.description}
        </p>
      </div>

      <div className="flex-1 overflow-y-auto p-2">
        <div className="flex items-center justify-between px-2 py-2 mb-2">
          <span className="text-xs font-medium text-muted-foreground uppercase">
            卷列表
          </span>
          <button
            onClick={handleAddVolume}
            className="p-1 hover:bg-muted rounded text-muted-foreground hover:text-foreground transition-colors"
            title="添加卷"
          >
            <Plus className="w-4 h-4" />
          </button>
        </div>

        {currentNovel.volumes.length === 0 ? (
          <div className="px-2 py-8 text-center text-muted-foreground">
            <p className="text-sm mb-2">暂无卷</p>
            <button
              onClick={handleAddVolume}
              className="text-sm text-primary hover:underline"
            >
              点击添加第一卷
            </button>
          </div>
        ) : (
          currentNovel.volumes.map((volume) => (
            <VolumeItem
              key={volume.id}
              volume={volume}
              isExpanded={expandedVolumes.has(volume.id)}
              onToggle={() => toggleVolume(volume.id)}
              onAddChapter={handleAddChapter}
              onDeleteVolume={(id) => {
                if (confirm("确定删除这一卷吗？所有章节也会被删除。")) {
                  // deleteVolume(id);
                }
              }}
              onEditVolumeTitle={(id, title) => {
                // updateVolumeTitle(id, title);
              }}
            />
          ))
        )}
      </div>
    </div>
  );
}
