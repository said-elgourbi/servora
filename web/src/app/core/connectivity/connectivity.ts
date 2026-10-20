import { DestroyRef, Injectable, inject, signal } from '@angular/core';
@Injectable({ providedIn: 'root' })
export class Connectivity {
  readonly online = signal(navigator.onLine);
  constructor() {
    const update = () => this.online.set(navigator.onLine);
    window.addEventListener('online', update);
    window.addEventListener('offline', update);
    inject(DestroyRef).onDestroy(() => {
      window.removeEventListener('online', update);
      window.removeEventListener('offline', update);
    });
  }
}
