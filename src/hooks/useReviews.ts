import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  ReviewWithRelations,
  ReviewerWithRelations,
  CreateReviewInput,
  ListReviewsInput,
  SubmitReviewInput,
  AddReviewerInput,
  ReviewListResponse,
  ReviewProgress,
} from '@/lib/types/review';
import { api } from '@/lib/api';

export const reviewQueryKeys = {
  all: ['reviews'] as const,
  list: (input: ListReviewsInput) => [...reviewQueryKeys.all, 'list', input] as const,
  detail: (id: string) => [...reviewQueryKeys.all, 'detail', id] as const,
  progress: (id: string) => [...reviewQueryKeys.all, 'progress', id] as const,
};

export function useReviews(input: ListReviewsInput) {
  return useQuery<ReviewListResponse>({
    queryKey: reviewQueryKeys.list(input),
    queryFn: async () => {
      const result = await api.review.list.query(input);
      return result as unknown as ReviewListResponse;
    },
  });
}

export function useReview(id: string) {
  return useQuery<ReviewWithRelations>({
    queryKey: reviewQueryKeys.detail(id),
    queryFn: async () => {
      const result = await api.review.getById.query({ id });
      return result as unknown as ReviewWithRelations;
    },
    enabled: !!id,
  });
}

export function useReviewProgress(reviewId: string) {
  return useQuery<ReviewProgress>({
    queryKey: reviewQueryKeys.progress(reviewId),
    queryFn: async () => {
      const result = await api.review.getProgress.query({ reviewId });
      return result as unknown as ReviewProgress;
    },
    enabled: !!reviewId,
  });
}

export function useCreateReview() {
  const queryClient = useQueryClient();

  return useMutation<ReviewWithRelations, Error, CreateReviewInput>({
    mutationFn: async (input) => {
      const result = await api.review.create.mutate(input);
      return result as unknown as ReviewWithRelations;
    },
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: reviewQueryKeys.list({ asAuthor: true }),
      });
    },
  });
}

export function useAddReviewer() {
  const queryClient = useQueryClient();

  return useMutation<ReviewerWithRelations, Error, AddReviewerInput>({
    mutationFn: async (input) => {
      const result = await api.review.addReviewer.mutate(input);
      return result as unknown as ReviewerWithRelations;
    },
    onSuccess: (_, variables) => {
      queryClient.invalidateQueries({
        queryKey: reviewQueryKeys.detail(variables.reviewId),
      });
    },
  });
}

export function useSubmitReview() {
  const queryClient = useQueryClient();

  return useMutation<ReviewWithRelations, Error, SubmitReviewInput>({
    mutationFn: async (input) => {
      const result = await api.review.submitReview.mutate(input);
      return result as unknown as ReviewWithRelations;
    },
    onSuccess: (data) => {
      queryClient.invalidateQueries({
        queryKey: reviewQueryKeys.detail(data.id),
      });
      queryClient.invalidateQueries({
        queryKey: reviewQueryKeys.list({ asReviewer: true }),
      });
      queryClient.invalidateQueries({
        queryKey: reviewQueryKeys.progress(data.id),
      });
    },
  });
}

export function useCheckAllApproved(reviewId: string) {
  return useQuery<boolean>({
    queryKey: [...reviewQueryKeys.all, 'checkApproved', reviewId],
    queryFn: async () => {
      const result = await api.review.checkAllApproved.query({ reviewId });
      return result as unknown as boolean;
    },
    enabled: !!reviewId,
  });
}
