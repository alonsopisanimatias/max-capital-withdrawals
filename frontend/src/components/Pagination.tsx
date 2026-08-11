interface Props {
  page: number;
  totalPages: number;
  totalElements: number;
  onPageChange: (page: number) => void;
}

export function Pagination({ page, totalPages, totalElements, onPageChange }: Props) {
  return (
    <div className="pagination">
      <span>
        {totalElements} retiro{totalElements === 1 ? '' : 's'} · página {totalPages === 0 ? 0 : page + 1} de {totalPages}
      </span>
      <div style={{ display: 'flex', gap: 8 }}>
        <button className="btn btn-small" disabled={page <= 0} onClick={() => onPageChange(page - 1)}>
          Anterior
        </button>
        <button className="btn btn-small" disabled={page + 1 >= totalPages} onClick={() => onPageChange(page + 1)}>
          Siguiente
        </button>
      </div>
    </div>
  );
}
