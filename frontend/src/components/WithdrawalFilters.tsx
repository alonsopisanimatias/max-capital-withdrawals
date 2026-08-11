import { useEffect, useState } from 'react';
import type { WithdrawalFilters, WithdrawalStatus } from '../api/types';
import { STATUS_LABELS } from '../labels';

const SEARCH_DEBOUNCE_MS = 400;

const STATUS_OPTIONS: WithdrawalStatus[] = [
  'EVALUATING_RISK',
  'PENDING_AUTHORIZATION',
  'AUTHORIZED',
  'REJECTED',
  'PROCESSING_TRANSFER',
  'EXECUTED',
  'FINAL_ERROR',
  'RETRYABLE_ERROR',
  'MANUAL_REVIEW',
];

interface Props {
  filters: WithdrawalFilters;
  onChange: (filters: WithdrawalFilters) => void;
}

export function WithdrawalFiltersBar({ filters, onChange }: Props) {
  function update(patch: Partial<WithdrawalFilters>) {
    onChange({ ...filters, ...patch, page: 0 });
  }

  // Local, un-debounced state for the search box so typing feels instant, while the actual
  // query (and the HTTP request it triggers) only fires SEARCH_DEBOUNCE_MS after the last
  // keystroke — without this, every character typed into a 22-digit CBU fired its own request.
  const [searchInput, setSearchInput] = useState(filters.search ?? '');

  useEffect(() => {
    setSearchInput(filters.search ?? '');
  }, [filters.search]);

  useEffect(() => {
    const trimmed = searchInput.trim();
    if (trimmed === (filters.search ?? '')) return;
    const timeout = setTimeout(() => update({ search: trimmed || undefined }), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timeout);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchInput]);

  return (
    <div className="panel filters">
      <label>
        Estado
        <select value={filters.status ?? ''} onChange={(e) => update({ status: e.target.value as WithdrawalStatus | '' })}>
          <option value="">Todos</option>
          {STATUS_OPTIONS.map((status) => (
            <option key={status} value={status}>
              {STATUS_LABELS[status]}
            </option>
          ))}
        </select>
      </label>
      <label>
        Desde
        <input type="date" value={filters.dateFrom ?? ''} onChange={(e) => update({ dateFrom: e.target.value || undefined })} />
      </label>
      <label>
        Hasta
        <input type="date" value={filters.dateTo ?? ''} onChange={(e) => update({ dateTo: e.target.value || undefined })} />
      </label>
      <label style={{ flex: 1, minWidth: 200 }}>
        Buscar (CBU o cuenta)
        <input
          type="text"
          placeholder="CBU parcial o UUID de cuenta"
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
        />
      </label>
      <button className="btn btn-small" onClick={() => onChange({ page: 0, size: filters.size })}>
        Limpiar filtros
      </button>
    </div>
  );
}
