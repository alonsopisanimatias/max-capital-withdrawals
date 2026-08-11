import { api } from './client';
import type { AccountResponse } from './types';

export const accountsApi = {
  list: () => api.get<AccountResponse[]>('/accounts'),
};
