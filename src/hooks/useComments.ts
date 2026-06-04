import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  CommentWithRelations,
  CreateCommentInput,
  ListCommentsInput,
  UpdateCommentInput,
  CommentListResponse,
} from '@/lib/types/comment';
import { api } from '@/lib/api';

export const commentQueryKeys = {
  all: ['comments'] as const,
  list: (input: ListCommentsInput) => [...commentQueryKeys.all, 'list', input] as const,
  detail: (id: string) => [...commentQueryKeys.all, 'detail', id] as const,
};

export function useComments(input: ListCommentsInput) {
  return useQuery<CommentListResponse>({
    queryKey: commentQueryKeys.list(input),
    queryFn: async () => {
      const result = await api.comment.list.query(input);
      return result as unknown as CommentListResponse;
    },
    enabled: !!input.documentId,
  });
}

export function useComment(id: string) {
  return useQuery<CommentWithRelations>({
    queryKey: commentQueryKeys.detail(id),
    queryFn: async () => {
      const result = await api.comment.getById.query({ id });
      return result as unknown as CommentWithRelations;
    },
    enabled: !!id,
  });
}

export function useCreateComment() {
  const queryClient = useQueryClient();

  return useMutation<CommentWithRelations, Error, CreateCommentInput>({
    mutationFn: async (input) => {
      const result = await api.comment.create.mutate(input);
      return result as unknown as CommentWithRelations;
    },
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({
        queryKey: commentQueryKeys.list({ documentId: variables.documentId }),
      });
    },
  });
}

export function useUpdateComment() {
  const queryClient = useQueryClient();

  return useMutation<CommentWithRelations, Error, UpdateCommentInput>({
    mutationFn: async (input) => {
      const result = await api.comment.update.mutate(input);
      return result as unknown as CommentWithRelations;
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({
        queryKey: commentQueryKeys.list({ documentId: data.documentId }),
      });
      queryClient.invalidateQueries({
        queryKey: commentQueryKeys.detail(data.id),
      });
    },
  });
}

export function useResolveComment() {
  const queryClient = useQueryClient();

  return useMutation<CommentWithRelations, Error, { id: string; documentId: string }>({
    mutationFn: async ({ id }) => {
      const result = await api.comment.resolve.mutate({ id });
      return result as unknown as CommentWithRelations;
    },
    onSuccess: (data, variables) => {
      queryClient.invalidateQueries({
        queryKey: commentQueryKeys.list({ documentId: variables.documentId }),
      });
      queryClient.invalidateQueries({
        queryKey: commentQueryKeys.detail(variables.id),
      });
    },
  });
}

export function useUnresolveComment() {
  const queryClient = useQueryClient();

  return useMutation<CommentWithRelations, Error, { id: string; documentId: string }>({
    mutationFn: async ({ id }) => {
      const result = await api.comment.unresolve.mutate({ id });
      return result as unknown as CommentWithRelations;
    },
    onSuccess: (data, variables) => {
      queryClient.invalidateQueries({
        queryKey: commentQueryKeys.list({ documentId: variables.documentId }),
      });
      queryClient.invalidateQueries({
        queryKey: commentQueryKeys.detail(variables.id),
      });
    },
  });
}

export function useDeleteComment() {
  const queryClient = useQueryClient();

  return useMutation<{ success: boolean }, Error, { id: string; documentId: string }>({
    mutationFn: async ({ id }) => {
      const result = await api.comment.delete.mutate({ id });
      return result as unknown as { success: boolean };
    },
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({
        queryKey: commentQueryKeys.list({ documentId: variables.documentId }),
      });
      queryClient.invalidateQueries({
        queryKey: commentQueryKeys.detail(variables.id),
      });
    },
  });
}
