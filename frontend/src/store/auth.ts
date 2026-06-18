import { create } from 'zustand';
import type { User } from '@/types';
import { authService } from '@/services/auth';
import { realtimeService } from '@/services/realtime';

interface AuthState {
  user: User | null;
  token: string | null;
  isAuthenticated: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
  loadProfile: () => Promise<void>;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: JSON.parse(localStorage.getItem('user') || 'null'),
  token: localStorage.getItem('token'),
  isAuthenticated: !!localStorage.getItem('token'),

  login: async (email: string, password: string) => {
    const res = await authService.login(email, password);
    const { access_token, user } = res.data.data;
    localStorage.setItem('token', access_token);
    localStorage.setItem('user', JSON.stringify(user));
    realtimeService.connect(access_token);
    set({ user, token: access_token, isAuthenticated: true });
  },

  logout: () => {
    localStorage.removeItem('token');
    localStorage.removeItem('user');
    realtimeService.disconnect();
    set({ user: null, token: null, isAuthenticated: false });
  },

  loadProfile: async () => {
    const res = await authService.getProfile();
    const user = res.data.data;
    localStorage.setItem('user', JSON.stringify(user));
    set({ user });
  },
}));
