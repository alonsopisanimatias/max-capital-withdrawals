import type { WithdrawalFilters, WithdrawalStatus } from '../api/types';

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

  return (
    <div className="panel filters">
      <label>
        Estado
        <select value={filters.status ?? ''} onChange={(e) => update({ status: e.target.value as WithdrawalStatus | '' })}>
          <option value="">Todos</option>
          {STATUS_OPTIONS.map((status) => (
            <option key={status} value={status}>
              {status}
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
          value={filters.search ?? ''}
          onChange={(e) => update({ search: e.target.value || undefined })}
        />
      </label>
      <button className="btn btn-small" onClick={() => onChange({ page: 0, size: filters.size })}>
        Limpiar filtros
      </button>
    </div>
  );
}
