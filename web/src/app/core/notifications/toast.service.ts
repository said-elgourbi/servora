import { Injectable, signal } from '@angular/core';

export type ToastKind = 'success' | 'warning' | 'error';
export interface ToastMessage {
  id: number;
  kind: ToastKind;
  message: string;
}

@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly current = signal<ToastMessage | null>(null);
  private nextId = 0;
  private timer: ReturnType<typeof setTimeout> | null = null;

  success(message: string): void { this.show('success', message); }
  warning(message: string): void { this.show('warning', message); }
  error(message: string): void { this.show('error', message); }

  dismiss(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
    this.current.set(null);
  }

  private show(kind: ToastKind, message: string): void {
    if (this.timer) clearTimeout(this.timer);
    const id = ++this.nextId;
    this.current.set({ id, kind, message });
    this.timer = setTimeout(() => {
      if (this.current()?.id === id) this.current.set(null);
      this.timer = null;
    }, 4500);
  }
}
