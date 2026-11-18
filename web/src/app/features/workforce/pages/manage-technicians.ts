import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type { ManagedTechnician, TechnicianPermission } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import { AuthSession } from '../../auth/data-access/auth-session';
import { TechniciansApi } from '../data-access/technicians-api';

@Component({
  selector: 'app-manage-technicians', imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush, styleUrl: './manage-technicians.scss',
  templateUrl: './manage-technicians.html',
})
export class ManageTechniciansPage {
  protected readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthSession);
  private readonly api = inject(TechniciansApi);
  protected readonly rows = signal<ManagedTechnician[]>([]);
  protected readonly permissions = signal<TechnicianPermission[]>([]);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly selected = computed(() => this.rows().find((row) => row.membershipId === this.selectedId()) ?? null);
  protected readonly query = signal('');
  protected readonly permissionQuery = signal('');
  protected readonly visible = computed(() => {
    const q = this.query().trim().toLocaleLowerCase();
    return this.rows().filter((row) => !q || `${row.firstName} ${row.lastName} ${row.email} ${row.phone ?? ''}`.toLocaleLowerCase().includes(q));
  });
  protected readonly visiblePermissions = computed(() => {
    const q = this.permissionQuery().trim().toLocaleLowerCase();
    return this.permissions().filter((p) => !q || `${p.code} ${this.permissionName(p)}`.toLocaleLowerCase().includes(q));
  });
  protected readonly firstName = signal('');
  protected readonly lastName = signal('');
  protected readonly email = signal('');
  protected readonly phone = signal('');
  protected readonly directCodes = signal<string[]>([]);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');

  constructor() { void this.load(); }
  protected async load(): Promise<void> {
    this.loading.set(true); this.error.set('');
    try {
      const result = await firstValueFrom(this.api.list());
      this.rows.set([...result.technicians]); this.permissions.set([...result.permissions]);
      const id = this.selectedId();
      const selected = result.technicians.find((row) => row.membershipId === id) ?? result.technicians[0] ?? null;
      this.selectedId.set(selected?.membershipId ?? null); this.sync(selected);
    } catch { this.error.set(this.i18n.text('techniciansLoadFailed')); }
    finally { this.loading.set(false); }
  }
  protected select(row: ManagedTechnician): void { this.selectedId.set(row.membershipId); this.notice.set(''); this.sync(row); }
  protected inherited(code: string): boolean { return this.selected()?.rolePermissionCodes.includes(code) ?? false; }
  protected checked(code: string): boolean { return this.inherited(code) || this.directCodes().includes(code); }
  protected toggle(code: string, checked: boolean): void {
    if (this.inherited(code)) return;
    this.directCodes.update((codes) => checked ? [...new Set([...codes, code])] : codes.filter((item) => item !== code));
  }
  protected permissionName(permission: TechnicianPermission): string {
    return this.i18n.language() === 'fr' ? permission.nameFr : permission.nameEn;
  }
  protected permissionDescription(permission: TechnicianPermission): string {
    return this.i18n.language() === 'fr' ? permission.descriptionFr : permission.descriptionEn;
  }
  protected async save(): Promise<void> {
    const row = this.selected(); if (!row || this.saving() || !this.auth.hasPermission('TECHNICIAN_UPDATE')) return;
    this.saving.set(true); this.error.set(''); this.notice.set('');
    try {
      const updated = await firstValueFrom(this.api.update(row.membershipId, {
        firstName: this.firstName().trim(), lastName: this.lastName().trim(), email: this.email().trim(),
        phone: this.phone().trim() || null, directPermissionCodes: this.directCodes(),
      }));
      this.rows.update((rows) => rows.map((item) => item.membershipId === updated.membershipId ? updated : item));
      this.sync(updated); this.notice.set(this.i18n.text('technicianSaved'));
    } catch (error) {
      this.error.set(error instanceof HttpErrorResponse && error.error?.code === 'TECHNICIAN_EMAIL_IN_USE'
        ? this.i18n.text('technicianEmailInUse') : this.i18n.text('technicianSaveFailed'));
    } finally { this.saving.set(false); }
  }
  private sync(row: ManagedTechnician | null): void {
    this.firstName.set(row?.firstName ?? ''); this.lastName.set(row?.lastName ?? '');
    this.email.set(row?.email ?? ''); this.phone.set(row?.phone ?? '');
    this.directCodes.set([...(row?.directPermissionCodes ?? [])]);
  }
}
