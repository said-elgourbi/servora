import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { firstValueFrom } from 'rxjs';
import type { CustomerContact, SaveContactRequest } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
import { AuthSession } from '../../auth/data-access/auth-session';
import { CustomersApi } from '../data-access/customers-api';
import { PhoneFormatDirective } from '../../../shared/ui/phone-format.directive';

export interface ContactEditorData { customerId: string; customerName: string; contact?: CustomerContact }
@Component({
  selector: 'app-contact-editor-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatCheckboxModule, PhoneFormatDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<h2 mat-dialog-title>{{ i18n.text(data.contact ? 'customerEditContact' : 'customerAddContact') }}</h2>
    <mat-dialog-content><p class="context">{{ data.customerName }}</p><p class="hint">{{ i18n.text('customerContactOwnDetailsHint') }}</p>
      <form [formGroup]="form" (ngSubmit)="save()">
        <div class="grid"><label>{{ i18n.text('customerFirstName') }} *<input formControlName="firstName" autocomplete="given-name" maxlength="100"></label>
          <label>{{ i18n.text('customerLastName') }} *<input formControlName="lastName" autocomplete="family-name" maxlength="100"></label>
          <label>{{ i18n.text('customerEmail') }}<input formControlName="email" type="email" autocomplete="email" maxlength="320"></label>
          <label>{{ i18n.text('customerPhone') }}<input appPhoneFormat formControlName="phone" type="tel" autocomplete="tel" maxlength="50"></label></div>
        <mat-checkbox formControlName="isPrimary">{{ i18n.text('customerMakePrimary') }}</mat-checkbox>
      </form>
      @if (error()) { <p class="error" role="alert">{{ i18n.text(error()!) }}</p> }
    </mat-dialog-content><mat-dialog-actions align="end"><button mat-button [mat-dialog-close]="false" [disabled]="busy()">{{ i18n.text('cancel') }}</button>
      <button mat-flat-button (click)="save()" [disabled]="busy()">{{ i18n.text(busy() ? 'customerSaving' : 'customerSave') }}</button></mat-dialog-actions>`,
  styles: [`:host{display:block;width:min(620px,90vw)}:host mat-dialog-content{padding-top:8px;max-height:none;overflow:visible}.context{margin:0 0 4px;color:var(--mat-sys-on-surface-variant)}.hint{margin:0 0 16px;color:var(--mat-sys-on-surface-variant);font-size:12px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:14px 16px;margin-bottom:16px}label{display:grid;gap:5px;font-size:12px;font-weight:600;color:var(--mat-sys-on-surface-variant)}input{height:42px;box-sizing:border-box;padding:8px 10px;border:1px solid var(--mat-sys-outline);border-radius:7px;background:var(--mat-sys-surface);color:var(--mat-sys-on-surface);font:inherit}input:focus-visible{outline:none;box-shadow:0 0 0 2px color-mix(in srgb,var(--mat-sys-primary) 22%,transparent);border-color:var(--mat-sys-primary)}.error{color:var(--mat-sys-error)}@media(max-width:560px){:host{width:100%}.grid{grid-template-columns:1fr}}`],
})
export class ContactEditorDialog {
  protected readonly data = inject<ContactEditorData>(MAT_DIALOG_DATA);
  protected readonly i18n = inject(I18n);
  private readonly api = inject(CustomersApi);
  private readonly auth = inject(AuthSession);
  private readonly ref = inject(MatDialogRef<ContactEditorDialog, boolean>);
  private readonly boundary = this.auth.boundary();
  protected readonly busy = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    firstName: [this.data.contact?.firstName ?? '', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(100)]],
    lastName: [this.data.contact?.lastName ?? '', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(100)]],
    email: [this.data.contact?.email ?? '', Validators.email],
    phone: [this.data.contact?.phone ?? ''],
    isPrimary: [this.data.contact?.isPrimary ?? !this.data.contact],
  });
  constructor() { effect(() => { if (this.auth.boundary() !== this.boundary) this.ref.close(false); }); }
  protected async save(): Promise<void> {
    if (this.busy()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) { this.error.set('customerFormInvalid'); return; }
    const v = this.form.getRawValue();
    const body: SaveContactRequest = { firstName: v.firstName.trim(), lastName: v.lastName.trim(), email: v.email.trim() || null, phone: v.phone.trim() || null, isPrimary: v.isPrimary };
    this.busy.set(true); this.error.set(null);
    try {
      if (this.data.contact) await firstValueFrom(this.api.updateContact(this.data.customerId, this.data.contact.id, { ...body, expectedVersion: this.data.contact.version }));
      else await firstValueFrom(this.api.addContact(this.data.customerId, body));
      this.ref.close(true);
    } catch { this.error.set('customerSaveFailed'); }
    finally { this.busy.set(false); }
  }
}
