import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { I18n } from '../../core/i18n/i18n';
import { ToastService } from '../../core/notifications/toast.service';

@Component({
  selector: 'app-toast-region',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="toast-region" aria-live="polite" aria-atomic="true">
      @if (toast.current(); as message) {
        <div class="toast" [class.success]="message.kind === 'success'" [class.warning]="message.kind === 'warning'" [class.error]="message.kind === 'error'" [attr.role]="message.kind === 'error' ? 'alert' : 'status'">
          <span class="toast-indicator" aria-hidden="true">{{ message.kind === 'success' ? '✓' : message.kind === 'warning' ? '!' : '×' }}</span>
          <p>{{ message.message }}</p>
          <button type="button" (click)="toast.dismiss()" [attr.aria-label]="i18n.text('toastDismiss')">×</button>
        </div>
      }
    </div>
  `,
  styles: `
    :host { position: fixed; z-index: 2000; top: 16px; right: 20px; width: min(420px, calc(100vw - 32px)); pointer-events: none; }
    .toast-region { display: grid; gap: 10px; }
    .toast { display: grid; grid-template-columns: 28px minmax(0, 1fr) 28px; align-items: center; gap: 11px; min-height: 58px; padding: 12px 14px; border:1px solid; border-radius:10px; box-shadow: 0 8px 26px #14213d2b; pointer-events: auto; animation: toast-in 160ms ease-out; }
    .toast.success { background: var(--app-success-bg); border-color:var(--app-success-border); color: var(--app-success-text); }
    .toast.warning { background: var(--app-warning-bg); border-color:var(--app-warning-border); color: var(--app-warning-text); }
    .toast.error { background: var(--app-danger-bg); border-color:var(--app-danger-border); color: var(--app-danger-text); }
    .toast-indicator { display: grid; place-items: center; width: 26px; height: 26px; border-radius:50%; color: white; font-size: 17px; font-weight: 800; }
    .success .toast-indicator { background: #2f8b4f; }
    .warning .toast-indicator { background: #b17b08; }
    .error .toast-indicator { background: #c84444; }
    .toast p { margin: 0; font-size: 14px; font-weight: 600; line-height: 1.45; overflow-wrap: anywhere; }
    .toast button { display: grid; place-items: center; width: 28px; height: 28px; border:0; border-radius:6px; background: transparent; color: inherit; font-size: 22px; cursor: pointer; }
    .toast button:hover { background: #00000012; }
    @keyframes toast-in { from { transform: translateY(-8px); } to { transform: translateY(0); } }
    @media (max-width: 600px) { :host { top: 10px; right: 10px; width: calc(100vw - 20px); } }
    @media (prefers-reduced-motion: reduce) { .toast { animation: none; } }
  `,
})
export class ToastRegion {
  protected readonly toast = inject(ToastService);
  protected readonly i18n = inject(I18n);
}
