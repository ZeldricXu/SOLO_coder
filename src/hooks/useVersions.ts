import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type {
  UseQueryOptions,
  UseMutationOptions,
  QueryKey,
} from '@tanstack/react-query';
import { api } from '@/lib/api';
import type {
  Version,
  VersionListResult,
  VersionDiff,
} from '../lib/types/version';
import type { RouterInputs, RouterOutputs } from '../lib/api';

type ListVersionsInput = RouterInputs['document']['listVersions'];
type GetVersionInput = RouterInputs['document']['getVersion'];
type CompareVersionsInput = RouterInputs['document']['compareVersions'];
type RollbackToVersionInput = RouterInputs['document']['rollbackToVersion'];
type ListVersionsOutput = RouterOutputs['document']['listVersions'];
type GetVersionOutput = RouterOutputs['document']['getVersion'];
type CompareVersionsOutput = RouterOutputs['document']['compareVersions'];
type RollbackToVersionOutput = RouterOutputs['document']['rollbackToVersion'];

const versionKeys = {
  all: ['versions'] as const,
  list: (documentId: string) =>
    [...versionKeys.all, 'list', documentId] as const,
  detail: (documentId: string, version: number) =>
    [...versionKeys.all, 'detail', documentId, version] as const,
  compare: (
    documentId: string,
    versionFrom: number,
    versionTo: number
  ) =>
    [...versionKeys.all, 'compare', documentId, versionFrom, versionTo] as const,
};

export const useVersionList = (
  input: ListVersionsInput,
  options?: Omit<
    UseQueryOptions<ListVersionsOutput, Error, VersionListResult, QueryKey>,
    'queryKey' | 'queryFn'
  >
) => {
  return useQuery({
    queryKey: versionKeys.list(input.documentId),
    queryFn: async () => {
      const result = await api.document.listVersions.fetch(input);
      return result as VersionListResult;
    },
    ...options,
  });
};

export const useVersionDetail = (
  input: GetVersionInput,
  options?: Omit<
    UseQueryOptions<GetVersionOutput, Error, Version, QueryKey>,
    'queryKey' | 'queryFn'
  >
) => {
  return useQuery({
    queryKey: versionKeys.detail(input.documentId, input.version),
    queryFn: async () => {
      const result = await api.document.getVersion.fetch(input);
      return result as Version;
    },
    ...options,
  });
};

export const useVersionCompare = (
  input: CompareVersionsInput,
  options?: Omit<
    UseQueryOptions<CompareVersionsOutput, Error, VersionDiff, QueryKey>,
    'queryKey' | 'queryFn'
  >
) => {
  return useQuery({
    queryKey: versionKeys.compare(
      input.documentId,
      input.versionFrom,
      input.versionTo
    ),
    queryFn: async () => {
      const result = await api.document.compareVersions.fetch(input);
      return result as VersionDiff;
    },
    ...options,
  });
};

export const useRollbackMutation = (
  options?: Omit<
    UseMutationOptions<
      RollbackToVersionOutput,
      Error,
      RollbackToVersionInput,
      unknown
    >,
    'mutationFn'
  >
) => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (input: RollbackToVersionInput) => {
      const result = await api.document.rollbackToVersion.mutate(input);
      return result;
    },
    onSuccess: (data, variables) => {
      void queryClient.invalidateQueries({
        queryKey: versionKeys.list(variables.documentId),
      });
      void queryClient.invalidateQueries({
        queryKey: ['documents'],
      });
      options?.onSuccess?.(data, variables, undefined);
    },
    ...options,
  });
};

export const useInvalidateVersions = () => {
  const queryClient = useQueryClient();

  return {
    invalidateAll: () =>
      queryClient.invalidateQueries({ queryKey: versionKeys.all }),
    invalidateList: (documentId: string) =>
      queryClient.invalidateQueries({
        queryKey: versionKeys.list(documentId),
      }),
    invalidateDetail: (documentId: string, version: number) =>
      queryClient.invalidateQueries({
        queryKey: versionKeys.detail(documentId, version),
      }),
    invalidateCompare: (
      documentId: string,
      versionFrom: number,
      versionTo: number
    ) =>
      queryClient.invalidateQueries({
        queryKey: versionKeys.compare(documentId, versionFrom, versionTo),
      }),
  };
};

export { versionKeys };
