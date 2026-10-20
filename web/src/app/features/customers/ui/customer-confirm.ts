import { AuthSession } from '../../auth/data-access/auth-session';
import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
@Component({
  selector: 'app-customer-confirm',
  imports: [MatDialogModule, MatButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<h2 mat-dialog-title>{{ i18n.text(data.title) }}</h2>
    <mat-dialog-content
      ><p>{{ i18n.text(data.message) }}</p>
      <p>{{ data.name }}</p></mat-dialog-content
    >
    <mat-dialog-actions align="end">
      <button mat-button [mat-dialog-close]="false" cdkFocusInitial>
        {{ i18n.text('cancel') }}
      </button>
      <button mat-flat-button [mat-dialog-close]="true">{{ i18n.text('customerConfirm') }}</button>
    </mat-dialog-actions>`,
})
export class CustomerConfirm {
  constructor() {
    const auth = inject(AuthSession),
      ref = inject(MatDialogRef<CustomerConfirm>),
      boundary = auth.boundary();
    effect(() => {
      if (auth.boundary() !== boundary) ref.close(false);
    });
  }
  protected readonly i18n = inject(I18n);
  protected readonly data = inject<{
    title: TranslationKey;
    message: TranslationKey;
    name: string;
  }>(MAT_DIALOG_DATA);
}
