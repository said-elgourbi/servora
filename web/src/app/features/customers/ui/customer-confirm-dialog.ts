import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';

export interface CustomerConfirmationData {
  title: TranslationKey;
  message: TranslationKey;
  confirm: TranslationKey;
  messageParams?: Record<string, string | number>;
  destructive?: boolean;
}

@Component({
  selector: 'app-customer-confirm-dialog',
  imports: [MatDialogModule, MatButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<h2 mat-dialog-title>{{ i18n.text(data.title) }}</h2>
    <mat-dialog-content><p>{{ message }}</p></mat-dialog-content>
    <mat-dialog-actions align="end"><button mat-button [mat-dialog-close]="false">{{ i18n.text('cancel') }}</button>
      @if (data.destructive) { <button mat-stroked-button color="warn" [mat-dialog-close]="true">{{ i18n.text(data.confirm) }}</button> }
      @else { <button mat-flat-button [mat-dialog-close]="true">{{ i18n.text(data.confirm) }}</button> }</mat-dialog-actions>`,
  styles: [`:host{display:block;width:min(500px,90vw)}mat-dialog-content{max-height:none;overflow:visible}p{line-height:1.55;margin:0 0 8px}`],
})
export class CustomerConfirmDialog {
  protected readonly data = inject<CustomerConfirmationData>(MAT_DIALOG_DATA);
  protected readonly i18n = inject(I18n);
  protected get message(): string {
    let value = this.i18n.text(this.data.message);
    for (const [key, replacement] of Object.entries(this.data.messageParams ?? {}))
      value = value.replace(`{${key}}`, String(replacement));
    return value;
  }
}
