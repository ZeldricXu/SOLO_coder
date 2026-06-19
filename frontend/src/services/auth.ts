import api from './api';
import type { User, ApiResponse } from '@/types';

export const authService = {
  login(email: string, password: string) {
    return api.post<ApiResponse<{ access_token: string; user: User }>>('/auth/login', {
      email,
      password,
    });
  },

  register(data: { email: string; password: string; name: string; role: string; tenantId?: string }) {
    return api.post<ApiResponse<User>>('/auth/register', data);
  },

  getProfile() {
    return api.get<ApiResponse<User>>('/auth/profile');
  },

  updateProfile(data: { name?: string; password?: string }) {
    return api.put<ApiResponse<User>>('/auth/profile', data);
  },
};
