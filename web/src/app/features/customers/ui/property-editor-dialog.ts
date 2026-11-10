import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';
import type { CanadianProvinceCode, PropertyDetails, SavePropertyRequest } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
import { AuthSession } from '../../auth/data-access/auth-session';
import { CustomersApi } from '../data-access/customers-api';

export interface PropertyEditorData { customerId: string; customerName: string; property?: PropertyDetails }
const provinces: CanadianProvinceCode[] = ['AB','BC','MB','NB','NL','NS','NT','NU','ON','PE','QC','SK','YT'];
@Component({
  selector: 'app-property-editor-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<h2 mat-dialog-title>{{ i18n.text(data.property ? 'propertyEdit' : 'propertyNew') }}</h2>
    <mat-dialog-content><p class="context">{{ i18n.text('propertyCustomerContext') }} · <b>{{ data.customerName }}</b></p>
      <p class="hint">{{ i18n.text('customerServiceLocationHint') }}</p>
      <form [formGroup]="form" (ngSubmit)="save()"><div class="grid">
        <label>{{ i18n.text('propertyName') }}<input formControlName="name" maxlength="255" [placeholder]="i18n.text('customerPropertyExample')"></label>
        <label>{{ i18n.text('propertyStreet') }} *<input formControlName="addressLine1" maxlength="255"></label>
        <label>{{ i18n.text('propertyUnit') }}<input formControlName="addressLine2" maxlength="255"></label>
        <label>{{ i18n.text('propertyCity') }} *<input formControlName="city" maxlength="100"></label>
        <label>{{ i18n.text('propertyProvince') }} *<select formControlName="province"><option value="">{{ i18n.text('propertyChooseProvince') }}</option>@for (code of provinces; track code) { <option [value]="code">{{ provinceName(code) }}</option> }</select></label>
        <label>{{ i18n.text('propertyPostalCode') }} *<input formControlName="postalCode" maxlength="20" placeholder="A1A 1A1"></label></div>
        <label class="notes">{{ i18n.text('customerNotes') }}<textarea rows="3" formControlName="notes"></textarea></label>
      </form>
      @if (error()) { <p class="error" role="alert">{{ i18n.text(error()!) }}</p> }
    </mat-dialog-content><mat-dialog-actions align="end"><button mat-button [mat-dialog-close]="false" [disabled]="busy()">{{ i18n.text('cancel') }}</button>
      <button mat-flat-button (click)="save()" [disabled]="busy()">{{ i18n.text(busy() ? 'customerSaving' : 'propertySave') }}</button></mat-dialog-actions>`,
  styles: [`:host{display:block;width:min(760px,92vw)}:host mat-dialog-content{padding-top:8px;max-height:calc(100dvh - 230px);overflow:auto}.context,.hint{color:var(--mat-sys-on-surface-variant)}.hint{font-size:13px;margin:0 0 16px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:14px 16px}label{display:grid;gap:5px;font-size:12px;font-weight:600;color:var(--mat-sys-on-surface-variant)}input,select,textarea{box-sizing:border-box;width:100%;min-height:42px;padding:8px 10px;border:1px solid var(--mat-sys-outline);border-radius:7px;background:var(--mat-sys-surface);color:var(--mat-sys-on-surface);font:inherit}input:focus-visible,select:focus-visible,textarea:focus-visible{outline:none;box-shadow:0 0 0 2px color-mix(in srgb,var(--mat-sys-primary) 22%,transparent);border-color:var(--mat-sys-primary)}.notes{margin-top:14px}textarea{resize:vertical}.error{color:var(--mat-sys-error)}@media(max-width:600px){:host{width:100%}.grid{grid-template-columns:1fr}}`],
})
export class PropertyEditorDialog {
  protected readonly data = inject<PropertyEditorData>(MAT_DIALOG_DATA);
  protected readonly i18n = inject(I18n);
  protected readonly provinces = provinces;
  private readonly api = inject(CustomersApi);
  private readonly auth = inject(AuthSession);
  private readonly ref = inject(MatDialogRef<PropertyEditorDialog, boolean>);
  private readonly boundary = this.auth.boundary();
  protected readonly busy = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected provinceName(code: CanadianProvinceCode): string { return this.i18n.text(('province' + code) as TranslationKey); }
  protected readonly form = inject(FormBuilder).nonNullable.group({
    name: [this.data.property?.name ?? ''], addressLine1: [this.data.property?.addressLine1 ?? '', [Validators.required, Validators.pattern(/\S/)]],
    addressLine2: [this.data.property?.addressLine2 ?? ''], city: [this.data.property?.city ?? '', [Validators.required, Validators.pattern(/\S/)]],
    province: [this.data.property?.province ?? '' as CanadianProvinceCode | '', Validators.required], postalCode: [this.data.property?.postalCode ?? '', [Validators.required, Validators.pattern(/\S/)]],
    notes: [this.data.property?.notes ?? ''],
  });
  constructor() { effect(() => { if (this.auth.boundary() !== this.boundary) this.ref.close(false); }); }
  protected async save(): Promise<void> {
    if (this.busy()) return;
    this.form.markAllAsTouched();
    if (this.form.invalid) { this.error.set('propertyFormInvalid'); return; }
    const v = this.form.getRawValue();
    const body: SavePropertyRequest = { name: v.name.trim() || null, addressLine1: v.addressLine1.trim(), addressLine2: v.addressLine2.trim() || null, city: v.city.trim(), province: v.province as CanadianProvinceCode, postalCode: v.postalCode.trim(), notes: v.notes.trim() || null, ...(this.data.property ? { expectedVersion: this.data.property.version } : {}) };
    this.busy.set(true); this.error.set(null);
    try {
      if (this.data.property) await firstValueFrom(this.api.updateProperty(this.data.customerId, this.data.property.id, body));
      else await firstValueFrom(this.api.createProperty(this.data.customerId, body));
      this.ref.close(true);
    } catch { this.error.set(this.data.property ? 'propertySaveFailed' : 'propertyCreateFailed'); }
    finally { this.busy.set(false); }
  }
}
