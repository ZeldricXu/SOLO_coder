import api from './api';
import type { AxiosResponse } from 'axios';
import type { User, ApiResponse } from '@/types';

export interface LoginData {
  email: string;
  password: string;
}

export interface RegisterData {
  email: string;
  password: string;
  name: string;
  role: string;
  tenantId?: string;
}

export interface UpdateProfileData {
  name?: string;
  password?: string;
}

export const authService = {
  login(data: LoginData): Promise<AxiosResponse<ApiResponse<{ access_token: string; user: User }>>> {
    return api.post<ApiResponse<{ access_token: string; user: User }>>('/auth/login', data);
  },

  register(data: RegisterData): Promise<AxiosResponse<ApiResponse<User>>> {
    return api.post<ApiResponse<User>>('/auth/register', data);
  },

  getProfile(): Promise<AxiosResponse<ApiResponse<User>>> {
    return api.get<ApiResponse<User>>('/auth/profile');
  },

  updateProfile(data: UpdateProfileData): Promise<AxiosResponse<ApiResponse<User>>> {
    return api.put<ApiResponse<User>>('/auth/profile', data);
  },

  logout(): void {
    localStorage.removeItem('token');
    localStorage.removeItem('user');
  },

  saveToken(token: string): void {
    localStorage.setItem('token', token);
  },

  getToken(): string | null {
    return localStorage.getItem('token');
  },

  saveUser(user: User): void {
    localStorage.setItem('user', JSON.stringify(user));
  },

  getUser(): User | null {
    const userStr = localStorage.getItem('user');
    return userStr ? JSON.parse(userStr) : null;
  },
};
