import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';

export type ToastKind = 'success' | 'error' | 'info';

interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
}

interface ToastsContextValue {
  toasts: Toast[];
  notify: (kind: ToastKind, message: string) => void;
  dismiss: (id: number) => void;
}

const ToastsContext = createContext<ToastsContextValue | null>(null);

let nextId = 1;

export function ToastsProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((t) => t.id !== id));
  }, []);

  const notify = useCallback(
    (kind: ToastKind, message: string) => {
      const id = nextId++;
      setToasts((current) => [...current, { id, kind, message }]);
      setTimeout(() => dismiss(id), kind === 'error' ? 8000 : 4000);
    },
    [dismiss],
  );

  return <ToastsContext.Provider value={{ toasts, notify, dismiss }}>{children}</ToastsContext.Provider>;
}

export function useToasts(): ToastsContextValue {
  const ctx = useContext(ToastsContext);
  if (!ctx) {
    throw new Error('useToasts must be used within a ToastsProvider');
  }
  return ctx;
}
