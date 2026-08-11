import { useToasts } from '../hooks/useToasts';

export function Toasts() {
  const { toasts, dismiss } = useToasts();

  return (
    <div className="toasts">
      {toasts.map((toast) => (
        <div key={toast.id} className={`toast toast-${toast.kind}`} onClick={() => dismiss(toast.id)}>
          {toast.message}
        </div>
      ))}
    </div>
  );
}
