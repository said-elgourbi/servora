import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { WorkLabel } from './work-label';

@Component({
  selector: 'app-work-status',
  imports: [WorkLabel],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span [attr.data-status]="code()"
    ><i aria-hidden="true"></i>{{ code() | workLabel }}</span
  >`,
  styles: `
    :host {
      display: inline-flex;
    }
    span {
      display: inline-flex;
      align-items: center;
      gap: 7px;
      border-radius: 6px;
      padding: 5px 9px;
      font-size: 12px;
      line-height: 1.3;
      font-weight: 650;
      background: color-mix(in srgb, var(--mat-sys-primary) 9%, var(--mat-sys-surface));
      color: var(--mat-sys-primary);
    }
    i {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: currentColor;
      flex-shrink: 0;
    }
    [data-status='COMPLETED'],
    [data-status='RESOLVED'] {
      background: var(--app-success-container);
      color: var(--app-on-success-container);
    }
    [data-status='CANCELED'],
    [data-status='DRAFT'],
    [data-status='NEW'] {
      background: var(--mat-sys-surface-container);
      color: var(--mat-sys-on-surface-variant);
    }
  `,
})
export class WorkStatus {
  readonly code = input.required<string>();
}
