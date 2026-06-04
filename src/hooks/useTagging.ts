'use client';

import { useCallback, useMemo } from 'react';
import { api } from '@/lib/api';
import type {
  TagSuggestion,
  ClassificationResult,
} from '@/lib/nlp/types';

interface UseTagSuggestionsOptions {
  spaceId: string;
  title: string;
  content?: string;
  enabled?: boolean;
  maxTags?: number;
  minConfidence?: number;
  includeClassificationTags?: boolean;
}

export function useTagSuggestions({
  spaceId,
  title,
  content = '',
  enabled = true,
  maxTags = 10,
  minConfidence = 0.3,
  includeClassificationTags = true,
}: UseTagSuggestionsOptions) {
  const query = api.tag.suggestTags.useQuery(
    {
      spaceId,
      title,
      content,
      maxTags,
      minConfidence,
      includeClassificationTags,
    },
    {
      enabled: enabled && !!spaceId && !!title,
      staleTime: 60000,
      refetchOnWindowFocus: false,
    }
  );

  const suggestions = useMemo(() => {
    return query.data?.suggestions || [];
  }, [query.data]);

  const classification = useMemo(() => {
    return query.data?.classification || null;
  }, [query.data]);

  const refresh = useCallback(() => {
    return query.refetch();
  }, [query]);

  return {
    suggestions,
    classification,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
    refresh,
  };
}

interface UseAutoClassifyOptions {
  title: string;
  content?: string;
  enabled?: boolean;
}

export function useAutoClassify({
  title,
  content = '',
  enabled = true,
}: UseAutoClassifyOptions) {
  const query = api.tag.classifyDocument.useQuery(
    {
      title,
      content,
    },
    {
      enabled: enabled && !!title,
      staleTime: 60000,
      refetchOnWindowFocus: false,
    }
  );

  const classification = useMemo(() => {
    return query.data || null;
  }, [query.data]);

  const refresh = useCallback(() => {
    return query.refetch();
  }, [query]);

  return {
    classification,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
    refresh,
  };
}

interface UseApplyTagsOptions {
  documentId: string;
  spaceId: string;
  onSuccess?: (tags: Array<{
    tagId: string;
    tagName: string;
    color: string | null;
    isNew: boolean;
  }>) => void;
  onError?: (error: unknown) => void;
}

export function useApplyTagsMutation({
  documentId,
  spaceId,
  onSuccess,
  onError,
}: UseApplyTagsOptions) {
  const utils = api.useContext();

  const mutation = api.tag.applySuggestedTags.useMutation({
    onSuccess: (data) => {
      onSuccess?.(data.tags);
      utils.tag.list.invalidate({ spaceId });
      utils.document.get.invalidate({ id: documentId });
    },
    onError: (error) => {
      onError?.(error);
    },
  });

  const applyTag = useCallback(
    async (suggestion: TagSuggestion & {
      isExisting?: boolean;
      tagId?: string;
    }) => {
      return mutation.mutateAsync({
        documentId,
        spaceId,
        tagNames: [suggestion.name],
      });
    },
    [mutation, documentId, spaceId]
  );

  const applyTags = useCallback(
    async (tagNames: string[]) => {
      if (tagNames.length === 0) return { tags: [] };
      return mutation.mutateAsync({
        documentId,
        spaceId,
        tagNames,
      });
    },
    [mutation, documentId, spaceId]
  );

  const applyAllSuggestions = useCallback(
    async (suggestions: (TagSuggestion & {
      isExisting?: boolean;
      tagId?: string;
    })[]) => {
      const tagNames = suggestions.map((s) => s.name);
      return applyTags(tagNames);
    },
    [applyTags]
  );

  return {
    applyTag,
    applyTags,
    applyAllSuggestions,
    isLoading: mutation.isLoading,
    isError: mutation.isError,
    error: mutation.error,
  };
}

interface UseAutoTagOptions {
  documentId: string;
  spaceId: string;
  onSuccess?: (result: {
    tags: Array<{
      tagId: string;
      tagName: string;
      color: string | null;
      isNew: boolean;
    }>;
    classification: ClassificationResult;
  }) => void;
  onError?: (error: unknown) => void;
}

export function useAutoTagMutation({
  documentId,
  spaceId,
  onSuccess,
  onError,
}: UseAutoTagOptions) {
  const utils = api.useContext();

  const mutation = api.tag.autoTagDocument.useMutation({
    onSuccess: (data) => {
      onSuccess?.(data);
      utils.tag.list.invalidate({ spaceId });
      utils.document.get.invalidate({ id: documentId });
    },
    onError: (error) => {
      onError?.(error);
    },
  });

  const autoTag = useCallback(
    (options?: {
      maxTags?: number;
      minConfidence?: number;
    }) => {
      return mutation.mutateAsync({
        documentId,
        spaceId,
        ...options,
      });
    },
    [mutation, documentId, spaceId]
  );

  return {
    autoTag,
    isLoading: mutation.isLoading,
    isError: mutation.isError,
    error: mutation.error,
  };
}

interface UseClassifyAndTagOptions {
  documentId: string;
  spaceId: string;
  onSuccess?: (result: {
    classification: ClassificationResult;
    tags: Array<{
      tagId: string;
      tagName: string;
      color: string | null;
    }>;
  }) => void;
  onError?: (error: unknown) => void;
}

export function useClassifyAndTagMutation({
  documentId,
  spaceId,
  onSuccess,
  onError,
}: UseClassifyAndTagOptions) {
  const utils = api.useContext();

  const mutation = api.tag.classifyAndTag.useMutation({
    onSuccess: (data) => {
      onSuccess?.(data);
      utils.tag.list.invalidate({ spaceId });
      utils.document.get.invalidate({ id: documentId });
    },
    onError: (error) => {
      onError?.(error);
    },
  });

  const classifyAndTag = useCallback(() => {
    return mutation.mutateAsync({
      documentId,
      spaceId,
    });
  }, [mutation, documentId, spaceId]);

  return {
    classifyAndTag,
    isLoading: mutation.isLoading,
    isError: mutation.isError,
    error: mutation.error,
  };
}

interface UseTagStatsOptions {
  spaceId: string;
  enabled?: boolean;
  days?: number;
  limit?: number;
}

export function useTagStats({
  spaceId,
  enabled = true,
  days = 30,
  limit = 50,
}: UseTagStatsOptions) {
  const query = api.tag.getUsageStats.useQuery(
    {
      spaceId,
      days,
      limit,
    },
    {
      enabled: enabled && !!spaceId,
      staleTime: 300000,
      refetchOnWindowFocus: false,
    }
  );

  const stats = useMemo(() => {
    return query.data || [];
  }, [query.data]);

  const refresh = useCallback(() => {
    return query.refetch();
  }, [query]);

  return {
    stats,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
    refresh,
  };
}

interface UseTrendingTagsOptions {
  spaceId: string;
  enabled?: boolean;
  days?: number;
  limit?: number;
}

export function useTrendingTags({
  spaceId,
  enabled = true,
  days = 7,
  limit = 20,
}: UseTrendingTagsOptions) {
  const query = api.tag.getTrendingTags.useQuery(
    {
      spaceId,
      days,
      limit,
    },
    {
      enabled: enabled && !!spaceId,
      staleTime: 180000,
      refetchOnWindowFocus: false,
    }
  );

  const tags = useMemo(() => {
    return query.data || [];
  }, [query.data]);

  const refresh = useCallback(() => {
    return query.refetch();
  }, [query]);

  return {
    tags,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
    refresh,
  };
}

interface UseMergeTagsOptions {
  spaceId: string;
  onSuccess?: (result: {
    success: boolean;
    mergedCount: number;
  }) => void;
  onError?: (error: unknown) => void;
}

export function useMergeTagsMutation({
  spaceId,
  onSuccess,
  onError,
}: UseMergeTagsOptions) {
  const utils = api.useContext();

  const mutation = api.tag.mergeTags.useMutation({
    onSuccess: (data) => {
      onSuccess?.(data);
      utils.tag.list.invalidate({ spaceId });
      utils.tag.getUsageStats.invalidate({ spaceId });
      utils.tag.getTrendingTags.invalidate({ spaceId });
    },
    onError: (error) => {
      onError?.(error);
    },
  });

  const mergeTags = useCallback(
    (sourceTagIds: string[], targetTagId: string) => {
      return mutation.mutateAsync({
        spaceId,
        sourceTagIds,
        targetTagId,
      });
    },
    [mutation, spaceId]
  );

  return {
    mergeTags,
    isLoading: mutation.isLoading,
    isError: mutation.isError,
    error: mutation.error,
  };
}
