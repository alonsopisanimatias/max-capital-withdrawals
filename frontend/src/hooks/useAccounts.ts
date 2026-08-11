import { useQuery } from '@tanstack/react-query';
import { accountsApi } from '../api/accounts';

export function useAccounts() {
  return useQuery({
    queryKey: ['accounts'],
    queryFn: accountsApi.list,
  });
}
