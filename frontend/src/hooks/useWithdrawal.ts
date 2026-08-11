import { useQuery } from '@tanstack/react-query';
import { withdrawalsApi } from '../api/withdrawals';

const POLL_INTERVAL_MS = 4000;

export function useWithdrawal(id: string | null) {
  return useQuery({
    queryKey: ['withdrawal', id],
    queryFn: () => withdrawalsApi.getById(id!),
    enabled: id !== null,
    refetchInterval: POLL_INTERVAL_MS,
  });
}
