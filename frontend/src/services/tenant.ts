import api from './api';
import type { Tenant, BusinessLine, ApiResponse } from '@/types';

export const tenantService = {
  list() {
    return api.get<ApiResponse<Tenant[]>>('/tenants');
  },

  create(data: Partial<Tenant>) {
    return api.post<ApiResponse<Tenant>>('/tenants', data);
  },

  update(id: string, data: Partial<Tenant>) {
    return api.put<ApiResponse<Tenant>>(`/tenants/${id}`, data);
  },

  delete(id: string) {
    return api.delete(`/tenants/${id}`);
  },

  listBusinessLines(tenantId: string) {
    return api.get<ApiResponse<BusinessLine[]>>(`/tenants/${tenantId}/business-lines`);
  },

  createBusinessLine(tenantId: string, data: Partial<BusinessLine>) {
    return api.post<ApiResponse<BusinessLine>>(`/tenants/${tenantId}/business-lines`, data);
  },

  updateBusinessLine(tenantId: string, businessLineId: string, data: Partial<BusinessLine>) {
    return api.put<ApiResponse<BusinessLine>>(
      `/tenants/${tenantId}/business-lines/${businessLineId}`,
      data,
    );
  },

  deleteBusinessLine(tenantId: string, businessLineId: string) {
    return api.delete(`/tenants/${tenantId}/business-lines/${businessLineId}`);
  },
};
