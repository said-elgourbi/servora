import { DOCUMENT } from '@angular/common';
import { afterNextRender, inject, Injectable, Injector } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class NavigationFocus {
  private readonly document = inject(DOCUMENT);
  private readonly injector = inject(Injector);

  constructor() {
    let previousPath: string | null = null;
    inject(Router)
      .events.pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe((event) => {
        const path = event.urlAfterRedirects.split(/[?#]/)[0] ?? '/';
        const moveFocus = previousPath !== null && previousPath !== path;
        previousPath = path;
        if (moveFocus) afterNextRender(() => this.focusContent(), { injector: this.injector });
      });
  }

  focusContent(): void {
    this.document.getElementById('main-content')?.focus();
  }
}
