import { useQuery } from '@tanstack/react-query';
import { withdrawalsApi } from '../api/withdrawals';
import type { WithdrawalFilters } from '../api/types';

// Most status transitions happen server-side in the background pollers (risk evaluation,
// transfer execution, reconciliation), not in response to a user action here — polling is what
// makes those show up without the operator manually refreshing every few seconds.
const POLL_INTERVAL_MS = 4000;

export function useWithdrawals(filters: WithdrawalFilters) {
  return useQuery({
    queryKey: ['withdrawals', filters],
    queryFn: () => withdrawalsApi.search(filters),
    refetchInterval: POLL_INTERVAL_MS,
    placeholderData: (previous) => previous,
  });
}
