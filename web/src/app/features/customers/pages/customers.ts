import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { DOCUMENT, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import type { CustomerDetails, CustomerJob, CustomerJobFilter, CustomerListSummary, CustomerStatusFilter } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import { CustomersApi } from '../data-access/customers-api';
import { AuthSession } from '../../auth/data-access/auth-session';
import { WorkLabel } from '../../../shared/ui/work-label';
import { Icon } from '../../../shared/ui/icon';
import { PreferredTime } from '../../../shared/ui/preferred-time';

type QuickFilter = 'ALL' | 'OVERDUE' | 'ACTIVE_JOBS' | 'INACTIVE';

@Component({
  selector: 'app-customers',
  imports: [DatePipe, RouterLink, WorkLabel, Icon, PreferredTime],
  templateUrl: './customers.html',
  styleUrl: './customers.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomersPage {
  protected readonly i18n = inject(I18n);
  private readonly api = inject(CustomersApi);
  protected readonly auth = inject(AuthSession);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);
  private lastTrigger: HTMLElement | null = null;
  private bodyScrollLock: { scrollY: number; position: string; top: string; left: string; right: string; width: string; overflow: string } | null = null;
  protected readonly rows = signal<CustomerListSummary[]>([]);
  protected readonly overviewRows = signal<CustomerListSummary[] | null>(null);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);
  protected readonly query = signal('');
  protected readonly type = signal('ALL');
  protected readonly filter = signal<QuickFilter>('ALL');
  protected readonly activePopover = signal<{ kind: 'contacts' | 'jobs'; id: string; top: number; left: number } | null>(null);
  protected readonly contactCache = signal<Record<string, CustomerDetails['contacts']>>({});
  protected readonly jobCache = signal<Record<string, CustomerJob[]>>({});
  protected readonly popoverLoading = signal<string | null>(null);
  protected readonly popoverError = signal<string | null>(null);
  protected readonly copiedContactValue = signal<string | null>(null);
  protected readonly visible = computed(() => {
    const q = this.query().trim().toLocaleLowerCase();
    return [...this.rows()].filter((row) => {
      const matchesText = !q || `${row.displayName} ${row.email ?? ''} ${row.phone ?? ''}`.toLocaleLowerCase().includes(q);
      const matchesType = this.type() === 'ALL' || row.type === this.type();
      const matchesFilter = this.filter() === 'ALL' ||
        (this.filter() === 'OVERDUE' && row.overdueVisitCount > 0) ||
        (this.filter() === 'ACTIVE_JOBS' && row.openJobCount > 0) ||
        (this.filter() === 'INACTIVE' && row.status === 'INACTIVE');
      return matchesText && matchesType && matchesFilter;
    }).sort((a, b) => b.overdueVisitCount - a.overdueVisitCount || a.displayName.localeCompare(b.displayName));
  });
  protected readonly stats = computed(() => {
    const rows = this.overviewRows() ?? this.rows();
    return {
      total: rows.length,
      companies: rows.filter((row) => row.type === 'COMPANY').length,
      individuals: rows.filter((row) => row.type === 'INDIVIDUAL').length,
      active: rows.filter((row) => row.status === 'ACTIVE').length,
      overdue: rows.filter((row) => row.overdueVisitCount > 0).length,
      withJobs: rows.filter((row) => row.openJobCount > 0).length,
    };
  });

  constructor() {
    this.destroyRef.onDestroy(() => this.unlockDocumentScroll());
    this.load();
    this.api.list('ALL', 'ALL').subscribe({ next: (rows) => this.overviewRows.set(rows) });
  }
  protected load(): void {
    this.loading.set(true); this.failed.set(false);
    const status: CustomerStatusFilter = this.filter() === 'INACTIVE' ? 'INACTIVE' : 'ALL';
    const jobs: CustomerJobFilter = this.filter() === 'OVERDUE' ? 'HAS_OVERDUE_VISITS' : this.filter() === 'ACTIVE_JOBS' ? 'HAS_OPEN_JOBS' : 'ALL';
    this.api.list(status, jobs).subscribe({ next: (rows) => { this.rows.set(rows); this.loading.set(false); }, error: () => { this.failed.set(true); this.loading.set(false); } });
  }
  protected setFilter(filter: QuickFilter): void { this.filter.set(filter); this.load(); }
  protected setQuery(value: string): void { this.query.set(value); }
  protected setType(value: string): void { this.type.set(value); }
  protected toggleContacts(event: MouseEvent, row: CustomerListSummary): void {
    if (this.activePopover()?.kind === 'contacts' && this.activePopover()?.id === row.id) { this.closePopover(); return; }
    const point = this.popoverPoint(event, row.id, 'contacts');
    this.activePopover.set(point); this.lockDocumentScroll(); this.popoverError.set(null); this.focusPopoverForKeyboard(event);
    if (this.contactCache()[row.id]) return;
    this.popoverLoading.set(row.id);
    this.api.customer(row.id).subscribe({ next: (details) => { this.contactCache.update((cache) => ({ ...cache, [row.id]: details.contacts })); this.popoverLoading.set(null); }, error: () => { this.popoverLoading.set(null); this.popoverError.set(row.id); } });
  }
  protected toggleJobs(event: MouseEvent, row: CustomerListSummary): void {
    if (this.activePopover()?.kind === 'jobs' && this.activePopover()?.id === row.id) { this.closePopover(); return; }
    const point = this.popoverPoint(event, row.id, 'jobs');
    this.activePopover.set(point); this.lockDocumentScroll(); this.popoverError.set(null); this.focusPopoverForKeyboard(event);
    if (this.jobCache()[row.id]) return;
    this.popoverLoading.set(row.id);
    this.api.jobs(row.id).subscribe({ next: (jobs) => { this.jobCache.update((cache) => ({ ...cache, [row.id]: jobs })); this.popoverLoading.set(null); }, error: () => { this.popoverLoading.set(null); this.popoverError.set(row.id); } });
  }
  protected closePopover(restoreFocus = true): void { this.activePopover.set(null); this.unlockDocumentScroll(); this.popoverError.set(null); if (restoreFocus) queueMicrotask(() => this.lastTrigger?.focus()); }
  protected retryPopover(): void { const popover = this.activePopover(); if (!popover) return; const { id, kind } = popover; this.popoverLoading.set(id); this.popoverError.set(null); if (kind === 'contacts') this.api.customer(id).subscribe({ next: (details) => { this.contactCache.update((cache) => ({ ...cache, [id]: details.contacts })); this.popoverLoading.set(null); }, error: () => { this.popoverLoading.set(null); this.popoverError.set(id); } }); else this.api.jobs(id).subscribe({ next: (jobs) => { this.jobCache.update((cache) => ({ ...cache, [id]: jobs })); this.popoverLoading.set(null); }, error: () => { this.popoverLoading.set(null); this.popoverError.set(id); } }); }
  protected closeOutside(event: MouseEvent): void { const target = event.target as HTMLElement; if (!target.closest('.quick-popover') && !target.closest('.popover-trigger')) this.closePopover(false); }
  protected contactName(row: CustomerListSummary): string { if (row.type === 'INDIVIDUAL') return row.displayName; return row.primaryContact ? `${row.primaryContact.firstName} ${row.primaryContact.lastName}`.trim() : this.i18n.text('customerNoPrimaryContact'); }
  protected contactMoreCount(row: CustomerListSummary): number { return Math.max(0, row.contactCount - (row.primaryContact ? 1 : 0)); }
  protected extraContacts(id: string): CustomerDetails['contacts'] { return (this.contactCache()[id] ?? []).filter((contact) => !contact.isPrimary); }
  protected contactInitials(firstName: string, lastName: string): string { return `${firstName.trim().slice(0, 1)}${lastName.trim().slice(0, 1)}`.toLocaleUpperCase(); }
  protected async copyContactValue(value: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(value);
      this.copiedContactValue.set(value);
      window.setTimeout(() => { if (this.copiedContactValue() === value) this.copiedContactValue.set(null); }, 1400);
    } catch { this.copiedContactValue.set(null); }
  }
  private popoverPoint(event: MouseEvent, id: string, kind: 'contacts' | 'jobs'): { kind: 'contacts' | 'jobs'; id: string; top: number; left: number } { const rect = (event.currentTarget as HTMLElement).getBoundingClientRect(); const width = kind === 'contacts' ? 330 : 390; const left = Math.max(12, Math.min(rect.left, window.innerWidth - width - 12)); const estimatedHeight = kind === 'contacts' ? 170 : 300; const below = rect.bottom + 6; const top = below + estimatedHeight <= window.innerHeight - 12 ? below : Math.max(12, rect.top - estimatedHeight - 6); return { kind, id, top, left }; }
  private focusPopoverForKeyboard(event: MouseEvent): void { this.lastTrigger = event.currentTarget as HTMLElement; if (event.detail !== 0) return; queueMicrotask(() => this.document.getElementById('customer-quick-popover')?.focus()); }
  private lockDocumentScroll(): void {
    if (this.bodyScrollLock) return;
    const body = this.document.body;
    this.bodyScrollLock = { scrollY: window.scrollY, position: body.style.position, top: body.style.top, left: body.style.left, right: body.style.right, width: body.style.width, overflow: body.style.overflow };
    body.style.position = 'fixed'; body.style.top = `-${this.bodyScrollLock.scrollY}px`; body.style.left = '0'; body.style.right = '0'; body.style.width = '100%'; body.style.overflow = 'hidden';
  }
  private unlockDocumentScroll(): void {
    const lock = this.bodyScrollLock;
    if (!lock) return;
    const body = this.document.body;
    body.style.position = lock.position; body.style.top = lock.top; body.style.left = lock.left; body.style.right = lock.right; body.style.width = lock.width; body.style.overflow = lock.overflow;
    this.bodyScrollLock = null;
    window.scrollTo(0, lock.scrollY);
  }
  protected initials(name: string): string { return name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]).join('').toLocaleUpperCase(); }
  protected clear(): void { this.query.set(''); this.type.set('ALL'); this.setFilter('ALL'); }
}
