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

  // Both bounds are ISO yyyy-mm-dd strings from the native date inputs, so a plain string
  // comparison is a correct range check without parsing Date objects.
  const rangeInvalid = Boolean(filters.dateFrom && filters.dateTo && filters.dateFrom > filters.dateTo);

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
      <div className="date-range-field">
        <span className="date-range-label">Rango de fechas</span>
        <div className={`date-range-inputs${rangeInvalid ? ' date-range-inputs-invalid' : ''}`}>
          <input type="date" value={filters.dateFrom ?? ''} onChange={(e) => update({ dateFrom: e.target.value || undefined })} />
          <span className="date-range-arrow">→</span>
          <input type="date" value={filters.dateTo ?? ''} onChange={(e) => update({ dateTo: e.target.value || undefined })} />
        </div>
        {rangeInvalid && <span className="field-error">"Desde" es posterior a "Hasta"</span>}
      </div>
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
