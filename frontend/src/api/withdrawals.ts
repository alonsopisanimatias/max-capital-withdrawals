import { api } from './client';
import type { PageResponse, WithdrawalDetailResponse, WithdrawalFilters, WithdrawalResponse } from './types';

export interface CreateWithdrawalRequest {
  accountId: string;
  destinationCbu: string;
  amount: string;
}

function buildQuery(filters: WithdrawalFilters): string {
  const params = new URLSearchParams();
  if (filters.status) params.set('status', filters.status);
  // the backend binds these as Instant (ISO.DATE_TIME) — a bare `<input type="date">` value like
  // "2026-08-10" would fail to parse, so widen it to the full day in UTC before sending.
  if (filters.dateFrom) params.set('dateFrom', `${filters.dateFrom}T00:00:00Z`);
  if (filters.dateTo) params.set('dateTo', `${filters.dateTo}T23:59:59Z`);
  if (filters.search) params.set('search', filters.search);
  params.set('page', String(filters.page ?? 0));
  params.set('size', String(filters.size ?? 20));
  params.set('sort', 'createdAt,desc');
  return params.toString();
}

export const withdrawalsApi = {
  search: (filters: WithdrawalFilters) =>
    api.get<PageResponse<WithdrawalResponse>>(`/withdrawals?${buildQuery(filters)}`),

  getById: (id: string) => api.get<WithdrawalDetailResponse>(`/withdrawals/${id}`),

  create: (request: CreateWithdrawalRequest) => api.post<WithdrawalResponse>('/withdrawals', request),

  authorize: (id: string, operatorId: string) =>
    api.post<WithdrawalResponse>(`/withdrawals/${id}/authorize`, undefined, operatorId),

  reject: (id: string, operatorId: string) =>
    api.post<WithdrawalResponse>(`/withdrawals/${id}/reject`, undefined, operatorId),

  retry: (id: string, operatorId: string) =>
    api.post<WithdrawalResponse>(`/withdrawals/${id}/retry`, undefined, operatorId),

  resolveManualReview: (id: string, operatorId: string) =>
    api.post<WithdrawalResponse>(`/withdrawals/${id}/resolve-manual-review`, undefined, operatorId),
};
