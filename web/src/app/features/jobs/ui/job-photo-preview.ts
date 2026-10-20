import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { AuthSession } from '../../auth/data-access/auth-session';
import { JobsApi } from '../data-access/jobs-api';
import { I18n } from '../../../core/i18n/i18n';

@Component({
  selector: 'app-job-photo-preview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<button
    type="button"
    (click)="opened.emit()"
    [attr.aria-label]="i18n.text('fieldViewPhoto')"
  >
    @if (url(); as source) {
      <img [src]="source" alt="" />
    }
    <span>{{ i18n.text(failed() ? 'jobPhotoUnavailable' : 'fieldViewPhoto') }}</span>
  </button>`,
  styles: `
    :host {
      display: block;
      margin-top: 0;
    }
    button {
      display: grid;
      padding: 0;
      width: 112px;
      min-height: 72px;
      overflow: hidden;
      border: 1px solid var(--mat-sys-outline-variant);
      border-radius: 8px;
      background: var(--mat-sys-surface-container);
      color: var(--mat-sys-primary);
      cursor: pointer;
    }
    img {
      width: 112px;
      height: 72px;
      object-fit: cover;
    }
    span {
      padding: 7px;
      font-family: inherit;
      font-size: 12px;
      font-weight: 500;
      line-height: 1.4;
    }
  `,
})
export class JobPhotoPreview {
  readonly jobId = input.required<string>();
  readonly photoId = input.required<string>();
  readonly opened = output<void>();
  protected readonly i18n = inject(I18n);
  private readonly auth = inject(AuthSession);
  private readonly api = inject(JobsApi);
  protected readonly url = signal<string | null>(null);
  protected readonly failed = signal(false);
  constructor() {
    effect((cleanup) => {
      const boundary = this.auth.boundary();
      const jobId = this.jobId(),
        photoId = this.photoId();
      this.url.set(null);
      this.failed.set(false);
      if (!this.auth.hasPermission('evidence.view')) return;
      let objectUrl: string | null = null;
      const request = this.api.content(jobId, photoId, false).subscribe({
        next: (blob) => {
          if (boundary !== this.auth.boundary() || !this.auth.hasPermission('evidence.view'))
            return;
          objectUrl = URL.createObjectURL(blob);
          this.url.set(objectUrl);
        },
        error: () => this.failed.set(true),
      });
      cleanup(() => {
        request.unsubscribe();
        if (objectUrl) URL.revokeObjectURL(objectUrl);
      });
    });
  }
}
