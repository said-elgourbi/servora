import { inject, Injectable, signal } from '@angular/core';
import { SwUpdate } from '@angular/service-worker';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
@Injectable({ providedIn: 'root' })
export class AppUpdates {
  readonly ready = signal(false);
  readonly reloadBlocked = signal(false);
  private readonly saveBeforeReload = new Set<() => Promise<void>>();
  beforeReload(save: () => Promise<void>): () => void {
    this.saveBeforeReload.add(save);
    return () => this.saveBeforeReload.delete(save);
  }
  constructor() {
    const updates = inject(SwUpdate);
    if (updates.isEnabled)
      updates.versionUpdates.pipe(takeUntilDestroyed()).subscribe((event) => {
        if (event.type === 'VERSION_READY') this.ready.set(true);
      });
  }
  async checkpoint(): Promise<boolean> {
    this.reloadBlocked.set(false);
    try {
      for (const save of this.saveBeforeReload) await save();
      return true;
    } catch {
      this.reloadBlocked.set(true);
      return false;
    }
  }
  async reload(): Promise<void> {
    this.reloadBlocked.set(false);
    try {
      if (await this.checkpoint()) window.location.reload();
    } catch {
      this.reloadBlocked.set(true);
    }
  }
}
