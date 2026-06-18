import api from './api';
import type { User, ApiResponse, PaginatedResponse } from '@/types';

export interface ListUsersParams {
  page?: number;
  limit?: number;
  tenantId?: string;
  role?: string;
  keyword?: string;
}

export const userService = {
  list(params?: ListUsersParams) {
    return api.get<ApiResponse<PaginatedResponse<User>>>('/users', { params });
  },

  create(data: Partial<User> & { password: string }) {
    return api.post<ApiResponse<User>>('/users', data);
  },

  update(id: string, data: Partial<User>) {
    return api.put<ApiResponse<User>>(`/users/${id}`, data);
  },

  delete(id: string) {
    return api.delete(`/users/${id}`);
  },

  updateRole(id: string, role: string) {
    return api.patch<ApiResponse<User>>(`/users/${id}/role`, { role });
  },

  resetPassword(id: string, newPassword: string) {
    return api.patch<ApiResponse<void>>(`/users/${id}/reset-password`, { newPassword });
  },
};
