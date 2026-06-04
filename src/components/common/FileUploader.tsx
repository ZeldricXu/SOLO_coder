'use client';

import * as React from 'react';
import { Upload, X, FileText, Image, AlertCircle } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Progress } from '@/components/ui/progress';
import { Alert, AlertDescription } from '@/components/ui/alert';

interface UploadFile {
  id: string;
  file: File;
  progress: number;
  status: 'pending' | 'uploading' | 'completed' | 'error';
  url?: string;
  error?: string;
}

interface FileUploaderProps extends React.HTMLAttributes<HTMLDivElement> {
  onUpload?: (file: File) => Promise<string>;
  onFileSelect?: (files: File[]) => void;
  accept?: string;
  maxFiles?: number;
  maxSize?: number;
  multiple?: boolean;
  disabled?: boolean;
}

function FileUploader({
  onUpload,
  onFileSelect,
  accept,
  maxFiles = 10,
  maxSize = 10 * 1024 * 1024,
  multiple = true,
  disabled = false,
  className,
  ...props
}: FileUploaderProps) {
  const [files, setFiles] = React.useState<UploadFile[]>([]);
  const [isDragging, setIsDragging] = React.useState(false);
  const inputRef = React.useRef<HTMLInputElement>(null);

  const handleFiles = (selectedFiles: FileList | null) => {
    if (!selectedFiles) return;

    const newFiles: UploadFile[] = [];
    const totalFiles = files.length + selectedFiles.length;

    if (totalFiles > maxFiles) {
      alert(`最多只能上传 ${maxFiles} 个文件`);
      return;
    }

    Array.from(selectedFiles).forEach((file) => {
      if (file.size > maxSize) {
        alert(`文件 ${file.name} 超过大小限制 (${maxSize / 1024 / 1024}MB)`);
        return;
      }
      newFiles.push({
        id: `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`,
        file,
        progress: 0,
        status: 'pending',
      });
    });

    setFiles((prev) => [...prev, ...newFiles]);
    onFileSelect?.(newFiles.map((f) => f.file));

    if (onUpload) {
      newFiles.forEach((uploadFile) => {
        uploadFileItem(uploadFile);
      });
    }
  };

  const uploadFileItem = async (uploadFile: UploadFile) => {
    setFiles((prev) =>
      prev.map((f) =>
        f.id === uploadFile.id ? { ...f, status: 'uploading' as const } : f
      )
    );

    try {
      const simulateProgress = () => {
        setFiles((prev) =>
          prev.map((f) =>
            f.id === uploadFile.id
              ? { ...f, progress: Math.min(f.progress + 10, 90) }
              : f
          )
        );
      };

      const progressInterval = setInterval(simulateProgress, 100);

      const url = await onUpload!(uploadFile.file);

      clearInterval(progressInterval);

      setFiles((prev) =>
        prev.map((f) =>
          f.id === uploadFile.id
            ? { ...f, status: 'completed' as const, progress: 100, url }
            : f
        )
      );
    } catch (error) {
      setFiles((prev) =>
        prev.map((f) =>
          f.id === uploadFile.id
            ? {
                ...f,
                status: 'error' as const,
                error: error instanceof Error ? error.message : '上传失败',
              }
            : f
        )
      );
    }
  };

  const removeFile = (id: string) => {
    setFiles((prev) => prev.filter((f) => f.id !== id));
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
    handleFiles(e.dataTransfer.files);
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(true);
  };

  const handleDragLeave = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragging(false);
  };

  const getFileIcon = (file: File) => {
    if (file.type.startsWith('image/')) {
      return <Image className="h-5 w-5" />;
    }
    return <FileText className="h-5 w-5" />;
  };

  return (
    <div className={cn('w-full space-y-4', className)} {...props}>
      <div
        onDrop={handleDrop}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onClick={() => !disabled && inputRef.current?.click()}
        className={cn(
          'flex flex-col items-center justify-center rounded-lg border-2 border-dashed p-8 text-center transition-colors cursor-pointer',
          isDragging
            ? 'border-primary bg-primary/5'
            : 'border-muted-foreground/25 hover:border-primary/50 hover:bg-muted/50',
          disabled && 'opacity-50 cursor-not-allowed'
        )}
      >
        <Upload className="h-10 w-10 text-muted-foreground" />
        <p className="mt-4 text-sm font-medium">
          拖拽文件到此处，或点击选择
        </p>
        <p className="mt-2 text-xs text-muted-foreground">
          {accept ? `支持格式: ${accept}` : '支持所有文件格式'}
          {` · 最大 ${maxSize / 1024 / 1024}MB`}
          {multiple && ` · 最多 ${maxFiles} 个文件`}
        </p>
        <input
          ref={inputRef}
          type="file"
          accept={accept}
          multiple={multiple}
          disabled={disabled}
          onChange={(e) => handleFiles(e.target.files)}
          className="hidden"
        />
      </div>

      {files.length > 0 && (
        <div className="space-y-2">
          {files.map((uploadFile) => (
            <div
              key={uploadFile.id}
              className={cn(
                'flex items-center gap-3 rounded-lg border p-3',
                uploadFile.status === 'error' && 'border-destructive/50'
              )}
            >
              <div className="flex h-10 w-10 items-center justify-center rounded-md bg-muted">
                {uploadFile.file.type.startsWith('image/') &&
                uploadFile.url ? (
                  <img
                    src={uploadFile.url}
                    alt={uploadFile.file.name}
                    className="h-full w-full rounded-md object-cover"
                  />
                ) : (
                  getFileIcon(uploadFile.file)
                )}
              </div>
              <div className="flex-1 min-w-0">
                <p className="truncate text-sm font-medium">
                  {uploadFile.file.name}
                </p>
                <p className="text-xs text-muted-foreground">
                  {(uploadFile.file.size / 1024 / 1024).toFixed(2)} MB
                </p>
                {uploadFile.status === 'uploading' && (
                  <Progress value={uploadFile.progress} className="mt-2 h-1" />
                )}
                {uploadFile.status === 'error' && (
                  <Alert variant="destructive" className="mt-2 p-2">
                    <AlertCircle className="h-4 w-4" />
                    <AlertDescription className="text-xs">
                      {uploadFile.error}
                    </AlertDescription>
                  </Alert>
                )}
              </div>
              <div className="flex items-center gap-2">
                {uploadFile.status === 'completed' && (
                  <span className="text-xs text-green-600">已完成</span>
                )}
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  onClick={() => removeFile(uploadFile.id)}
                  disabled={uploadFile.status === 'uploading'}
                >
                  <X className="h-4 w-4" />
                </Button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export { FileUploader };
