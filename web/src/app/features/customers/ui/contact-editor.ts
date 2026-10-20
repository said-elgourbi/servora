import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import type { CustomerContact } from '@servora/shared-types';
import { firstValueFrom } from 'rxjs';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
import { AuthSession } from '../../auth/data-access/auth-session';
import { CustomersApi } from '../data-access/customers-api';
import { customerWriteError } from '../data-access/customer-errors';
@Component({
  selector: 'app-contact-editor',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<form [formGroup]="form" (ngSubmit)="save()" aria-labelledby="contact-editor-title">
    <h3 id="contact-editor-title">
      {{ i18n.text(contact() ? 'customerContactEdit' : 'customerContactNew') }}
    </h3>
    <p>{{ i18n.text('customerOnlineOnly') }}</p>
    <fieldset [disabled]="busy()">
      <mat-form-field
        ><mat-label>{{ i18n.text('customerFirstName') }}</mat-label
        ><input matInput formControlName="firstName" maxlength="100" required /><mat-error>{{
          i18n.text('customerRequired')
        }}</mat-error></mat-form-field
      >
      <mat-form-field
        ><mat-label>{{ i18n.text('customerLastName') }}</mat-label
        ><input matInput formControlName="lastName" maxlength="100" required /><mat-error>{{
          i18n.text('customerRequired')
        }}</mat-error></mat-form-field
      >
      <mat-form-field
        ><mat-label>{{ i18n.text('email') }}</mat-label
        ><input matInput type="email" formControlName="email" maxlength="255" /><mat-error>{{
          i18n.text('emailInvalid')
        }}</mat-error></mat-form-field
      >
      <mat-form-field
        ><mat-label>{{ i18n.text('phone') }}</mat-label
        ><input matInput type="tel" formControlName="phone" maxlength="50"
      /></mat-form-field>
      <mat-checkbox formControlName="isPrimary">{{ i18n.text('customerPrimary') }}</mat-checkbox>
      <p>{{ i18n.text('customerPrimaryDescription') }}</p>
    </fieldset>
    @if (error(); as message) {
      <p role="alert">{{ i18n.text(message) }}</p>
    }
    @if (attempted() && form.invalid) {
      <p role="alert">{{ i18n.text('customerFormInvalid') }}</p>
    }
    <div class="actions">
      <button mat-flat-button type="submit" [disabled]="busy() || blocked()">
        {{ i18n.text(busy() ? 'customerSaving' : 'customerSave') }}</button
      ><button mat-button type="button" [disabled]="busy()" (click)="closed.emit()">
        {{ i18n.text('cancel') }}
      </button>
      @if (blocked()) {
        <button mat-stroked-button type="button" (click)="saved.emit()">
          {{ i18n.text('customerReloadDiscard') }}
        </button>
      }
    </div>
  </form>`,
  styles: `
    :host {
      display: block;
      margin-block: 20px;
      padding: 20px;
      border: 1px solid var(--mat-sys-outline-variant);
      border-radius: 12px;
    }
    fieldset {
      border: 0;
      padding: 0;
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 240px), 1fr));
      gap: 12px;
      min-width: 0;
    }
    p {
      color: var(--mat-sys-on-surface-variant);
    }
    .actions {
      display: flex;
      gap: 12px;
      flex-wrap: wrap;
    }
  `,
})
export class ContactEditor {
  readonly customerId = input.required<string>();
  readonly contact = input<CustomerContact | null>(null);
  readonly saved = output<void>();
  readonly closed = output<void>();
  protected readonly i18n = inject(I18n);
  private readonly auth = inject(AuthSession);
  private readonly api = inject(CustomersApi);
  private readonly destroy = inject(DestroyRef);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    firstName: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(100)]],
    lastName: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(100)]],
    email: ['', [Validators.email, Validators.maxLength(255)]],
    phone: ['', Validators.maxLength(50)],
    isPrimary: [false],
  });
  protected readonly busy = signal(false);
  protected readonly blocked = signal(false);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly attempted = signal(false);
  constructor() {
    effect(() => {
      const c = this.contact();
      this.form.reset({
        firstName: c?.firstName ?? '',
        lastName: c?.lastName ?? '',
        email: c?.email ?? '',
        phone: c?.phone ?? '',
        isPrimary: c?.isPrimary ?? false,
      });
      this.blocked.set(false);
      this.error.set(null);
      this.attempted.set(false);
    });
  }
  protected async save() {
    if (this.busy() || this.blocked()) return;
    const permission = this.contact() ? 'customers.contacts.edit' : 'customers.contacts.create';
    if (!this.auth.hasPermission(permission)) {
      this.error.set('forbiddenDescription');
      return;
    }
    this.attempted.set(true);
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    this.busy.set(true);
    this.error.set(null);
    const boundary = this.auth.boundary(),
      value = this.form.getRawValue();
    try {
      await firstValueFrom(
        this.api.saveContact(
          this.customerId(),
          {
            firstName: value.firstName.trim(),
            lastName: value.lastName.trim(),
            email: value.email.trim() || null,
            phone: value.phone.trim() || null,
            isPrimary: value.isPrimary,
          },
          this.contact() ?? undefined,
        ),
      );
      if (!this.destroy.destroyed && boundary === this.auth.boundary()) this.saved.emit();
    } catch (error) {
      if (!this.destroy.destroyed && boundary === this.auth.boundary()) {
        const message = customerWriteError(error);
        this.error.set(message);
        this.blocked.set(message !== 'customerValidationFailed');
      }
    } finally {
      this.busy.set(false);
    }
  }
}
