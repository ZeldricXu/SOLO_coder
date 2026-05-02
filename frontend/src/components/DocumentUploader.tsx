"use client";

import { useCallback, useState, useRef, useEffect } from "react";
import { useDropzone } from "react-dropzone";
import { 
  Upload, 
  FileText, 
  AlertCircle, 
  CheckCircle2, 
  X, 
  Clock,
  Loader2
} from "lucide-react";
import { documentApi } from "@/lib/api";
import { clsx } from "clsx";
import { twMerge } from "tailwind-merge";

function cn(...inputs: any[]) {
  return twMerge(clsx(inputs));
}

interface DocumentUploaderProps {
  collectionName: string;
  onUploadComplete?: () => void;
}

type UploadStatus = "idle" | "uploading" | "processing" | "success" | "error";

interface FileUploadState {
  file: File;
  status: UploadStatus;
  error?: string;
  progress: number;
  elapsedTime: number;
}

const ACCEPTED_FILE_TYPES = {
  "application/pdf": [".pdf"],
  "text/plain": [".txt"],
  "text/markdown": [".md", ".markdown"],
};

export function DocumentUploader({
  collectionName,
  onUploadComplete,
}: DocumentUploaderProps) {
  const [files, setFiles] = useState<FileUploadState[]>([]);
  const timersRef = useRef<Map<number, NodeJS.Timeout>>(new Map());

  const startTimer = (index: number) => {
    const existingTimer = timersRef.current.get(index);
    if (existingTimer) {
      clearInterval(existingTimer);
    }

    const timer = setInterval(() => {
      setFiles((prev) =>
        prev.map((f, i) =>
          i === index ? { ...f, elapsedTime: f.elapsedTime + 1 } : f
        )
      );
    }, 1000);

    timersRef.current.set(index, timer);
  };

  const stopTimer = (index: number) => {
    const timer = timersRef.current.get(index);
    if (timer) {
      clearInterval(timer);
      timersRef.current.delete(index);
    }
  };

  useEffect(() => {
    return () => {
      timersRef.current.forEach((timer) => clearInterval(timer));
    };
  }, []);

  const onDrop = useCallback((acceptedFiles: File[]) => {
    const newFiles: FileUploadState[] = acceptedFiles.map((file) => ({
      file,
      status: "idle",
      progress: 0,
      elapsedTime: 0,
    }));
    setFiles((prev) => [...prev, ...newFiles]);
  }, []);

  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    onDrop,
    accept: ACCEPTED_FILE_TYPES,
    maxSize: 50 * 1024 * 1024,
  });

  const removeFile = (index: number) => {
    stopTimer(index);
    setFiles((prev) => prev.filter((_, i) => i !== index));
  };

  const uploadFile = async (fileState: FileUploadState, index: number) => {
    setFiles((prev) =>
      prev.map((f, i) =>
        i === index ? { ...f, status: "uploading" as UploadStatus, progress: 0, elapsedTime: 0 } : f
      )
    );

    startTimer(index);

    try {
      await documentApi.upload(
        fileState.file,
        collectionName,
        (progress) => {
          setFiles((prev) =>
            prev.map((f, i) =>
              i === index ? { ...f, progress } : f
            )
          );
        }
      );

      setFiles((prev) =>
        prev.map((f, i) =>
          i === index
            ? { ...f, status: "processing" as UploadStatus, progress: 100 }
            : f
        )
      );

      await new Promise((resolve) => setTimeout(resolve, 500));

      setFiles((prev) =>
        prev.map((f, i) =>
          i === index
            ? { ...f, status: "success" as UploadStatus, progress: 100 }
            : f
        )
      );

      stopTimer(index);

      setTimeout(() => {
        setFiles((prev) => prev.filter((_, i) => i !== index));
      }, 2000);

      onUploadComplete?.();
    } catch (error: any) {
      stopTimer(index);
      setFiles((prev) =>
        prev.map((f, i) =>
          i === index
            ? {
                ...f,
                status: "error" as UploadStatus,
                error: error.response?.data?.detail || "上传失败",
              }
            : f
        )
      );
    }
  };

  const uploadAll = async () => {
    const idleFiles = files.filter((f) => f.status === "idle");
    for (let i = 0; i < idleFiles.length; i++) {
      const fileIndex = files.findIndex((f) => f === idleFiles[i]);
      await uploadFile(idleFiles[i], fileIndex);
    }
  };

  const formatFileSize = (bytes: number) => {
    if (bytes === 0) return "0 Bytes";
    const k = 1024;
    const sizes = ["Bytes", "KB", "MB", "GB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i];
  };

  const formatTime = (seconds: number) => {
    if (seconds < 60) {
      return `${seconds}s`;
    }
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}m ${secs}s`;
  };

  const getStatusText = (status: UploadStatus) => {
    switch (status) {
      case "uploading":
        return "上传中...";
      case "processing":
        return "处理中...";
      case "success":
        return "完成";
      case "error":
        return "失败";
      default:
        return "";
    }
  };

  const getFileIcon = (type: string) => {
    return <FileText className="w-5 h-5 text-gray-500" />;
  };

  const idleCount = files.filter((f) => f.status === "idle").length;

  return (
    <div className="space-y-4">
      <div
        {...getRootProps()}
        className={cn(
          "border-2 border-dashed rounded-xl p-8 text-center cursor-pointer transition-all",
          isDragActive
            ? "border-primary-500 bg-primary-50 dark:bg-primary-900/20"
            : "border-gray-300 dark:border-gray-600 hover:border-primary-400 hover:bg-gray-50 dark:hover:bg-gray-800/50"
        )}
      >
        <input {...getInputProps()} />
        <div className="flex flex-col items-center gap-3">
          <div
            className={cn(
              "w-16 h-16 rounded-full flex items-center justify-center",
              isDragActive
                ? "bg-primary-100 dark:bg-primary-900/30"
                : "bg-gray-100 dark:bg-gray-800"
            )}
          >
            <Upload
              className={cn(
                "w-8 h-8",
                isDragActive
                  ? "text-primary-600 dark:text-primary-400"
                  : "text-gray-400"
              )}
            />
          </div>
          <div>
            <p className="text-sm font-medium text-gray-900 dark:text-white">
              {isDragActive ? "释放文件以上传" : "拖放文件到此处，或点击选择"}
            </p>
            <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">
              支持 PDF、Markdown、TXT 格式，单文件最大 50MB
            </p>
          </div>
        </div>
      </div>

      {files.length > 0 && (
        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-medium text-gray-700 dark:text-gray-300">
              待上传文件 ({files.length})
            </h3>
            {idleCount > 0 && (
              <button
                onClick={uploadAll}
                className="px-4 py-1.5 text-sm font-medium text-white bg-primary-600 hover:bg-primary-700 rounded-lg transition-colors"
              >
                全部上传
              </button>
            )}
          </div>

          <div className="space-y-2">
            {files.map((fileState, index) => (
              <div
                key={index}
                className={cn(
                  "flex items-center gap-3 p-4 rounded-xl border transition-all",
                  fileState.status === "error"
                    ? "border-red-200 dark:border-red-800 bg-red-50 dark:bg-red-900/10"
                    : fileState.status === "success"
                    ? "border-green-200 dark:border-green-800 bg-green-50 dark:bg-green-900/10"
                    : fileState.status === "uploading" || fileState.status === "processing"
                    ? "border-primary-200 dark:border-primary-800 bg-primary-50 dark:bg-primary-900/10"
                    : "border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-800"
                )}
              >
                <div
                  className={cn(
                    "w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0",
                    fileState.status === "success"
                      ? "bg-green-100 dark:bg-green-900/30"
                      : fileState.status === "error"
                      ? "bg-red-100 dark:bg-red-900/30"
                      : "bg-gray-100 dark:bg-gray-700"
                  )}
                >
                  {fileState.status === "uploading" || fileState.status === "processing" ? (
                    <Loader2 className="w-5 h-5 text-primary-500 animate-spin" />
                  ) : fileState.status === "success" ? (
                    <CheckCircle2 className="w-5 h-5 text-green-500" />
                  ) : fileState.status === "error" ? (
                    <AlertCircle className="w-5 h-5 text-red-500" />
                  ) : (
                    getFileIcon(fileState.file.type)
                  )}
                </div>

                <div className="flex-1 min-w-0">
                  <div className="flex items-center justify-between">
                    <p className="text-sm font-medium text-gray-900 dark:text-white truncate">
                      {fileState.file.name}
                    </p>
                    {(fileState.status === "uploading" || fileState.status === "processing" || fileState.status === "success") && (
                      <div className="flex items-center gap-1 ml-2 text-xs text-gray-500 dark:text-gray-400">
                        <Clock className="w-3 h-3" />
                        <span>{formatTime(fileState.elapsedTime)}</span>
                      </div>
                    )}
                  </div>

                  <div className="flex items-center gap-2 mt-1">
                    <span className="text-xs text-gray-500 dark:text-gray-400">
                      {formatFileSize(fileState.file.size)}
                    </span>
                    {(fileState.status === "uploading" || fileState.status === "processing") && (
                      <>
                        <span className="text-xs text-gray-400">·</span>
                        <span className={cn(
                          "text-xs font-medium",
                          fileState.status === "processing"
                            ? "text-secondary-600 dark:text-secondary-400"
                            : "text-primary-600 dark:text-primary-400"
                        )}>
                          {getStatusText(fileState.status)} {fileState.progress}%
                        </span>
                      </>
                    )}
                    {fileState.status === "success" && (
                      <span className="text-xs font-medium text-green-600 dark:text-green-400">
                        {getStatusText(fileState.status)}
                      </span>
                    )}
                    {fileState.error && (
                      <span className="text-xs font-medium text-red-500">
                        {fileState.error}
                      </span>
                    )}
                  </div>

                  {(fileState.status === "uploading" || fileState.status === "processing") && (
                    <div className="mt-2">
                      <div className="h-2 bg-gray-200 dark:bg-gray-700 rounded-full overflow-hidden">
                        <div
                          className={cn(
                            "h-full transition-all duration-300 rounded-full",
                            fileState.status === "processing"
                              ? "bg-secondary-500"
                              : "bg-primary-500"
                          )}
                          style={{ 
                            width: `${fileState.progress}%`,
                          }}
                        />
                      </div>
                      {fileState.status === "processing" && (
                        <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">
                          正在解析文档并向量化存储...
                        </p>
                      )}
                    </div>
                  )}
                </div>

                <div className="flex items-center gap-2">
                  {fileState.status === "idle" && (
                    <>
                      <button
                        onClick={() => uploadFile(fileState, index)}
                        className="px-3 py-1.5 text-xs font-medium text-white bg-primary-600 hover:bg-primary-700 rounded-lg transition-colors"
                      >
                        上传
                      </button>
                      <button
                        onClick={() => removeFile(index)}
                        className="p-1.5 hover:bg-gray-200 dark:hover:bg-gray-700 rounded-lg transition-colors"
                      >
                        <X className="w-4 h-4 text-gray-500" />
                      </button>
                    </>
                  )}
                  {fileState.status === "error" && (
                    <>
                      <button
                        onClick={() => uploadFile(fileState, index)}
                        className="px-3 py-1.5 text-xs font-medium text-white bg-primary-600 hover:bg-primary-700 rounded-lg transition-colors"
                      >
                        重试
                      </button>
                      <button
                        onClick={() => removeFile(index)}
                        className="p-1.5 hover:bg-red-100 dark:hover:bg-red-900/20 rounded-lg transition-colors"
                      >
                        <X className="w-4 h-4 text-red-500" />
                      </button>
                    </>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
