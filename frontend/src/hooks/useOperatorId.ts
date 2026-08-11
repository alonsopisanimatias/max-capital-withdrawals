import { useEffect, useState } from 'react';

const STORAGE_KEY = 'withdrawals.operatorId';

/** No real auth for this challenge (see WithdrawalController's javadoc) — the operator id is
 *  just a trusted free-text header, persisted locally so it survives a page reload. */
export function useOperatorId(): [string, (id: string) => void] {
  const [operatorId, setOperatorId] = useState(() => localStorage.getItem(STORAGE_KEY) ?? '');

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, operatorId);
  }, [operatorId]);

  return [operatorId, setOperatorId];
}
