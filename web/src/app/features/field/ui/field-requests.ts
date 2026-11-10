import { DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import type { FieldFollowUpDto } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import { AuthSession } from '../../auth/data-access/auth-session';
import { FieldSession } from '../data-access/field-session';
import { WorkLabel } from '../../../shared/ui/work-label';
import { PreferredTime } from '../../../shared/ui/preferred-time';

@Component({
  selector: 'app-field-requests',
  imports: [DatePipe, RouterLink, WorkLabel, PreferredTime],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="requests-page">
      <a class="back-link" routerLink="/home" [queryParams]="{ view: 'field' }">{{ i18n.text('backToDay') }}</a>
      <header class="page-header">
        <div><p class="eyebrow">{{ i18n.text('technicianView') }}</p><h1>{{ i18n.text('fieldMyRequests') }}</h1>
          <p>{{ i18n.text('fieldMyRequestsHint') }}</p></div>
        <button type="button" (click)="load()" [disabled]="loading() || busyId() !== null">{{ i18n.text('refresh') }}</button>
      </header>
      @if (error()) { <p class="error" role="alert">{{ i18n.text(error()!) }}</p> }
      @if (loading()) { <p class="state" role="status">{{ i18n.text('loadingMore') }}</p> }
      @else if (!requests().length) { <section class="empty"><h2>{{ i18n.text('fieldNoMyRequests') }}</h2><p>{{ i18n.text('fieldMyRequestsEmptyHint') }}</p></section> }
      @else {
        <section class="request-filters" [attr.aria-label]="i18n.text('fieldRequestFilters')">
          <label class="search-label" for="request-search">{{ i18n.text('fieldSearchRequests') }}</label>
          <input id="request-search" type="search" [value]="searchTerm()" (input)="searchTerm.set($any($event.target).value)" [placeholder]="i18n.text('fieldSearchRequestsHint')" />
          <div class="filter-tabs" role="group" [attr.aria-label]="i18n.text('fieldFilterByStatus')">
            @for (filter of filters; track filter) {
              <button type="button" [class.active]="requestFilter() === filter" [attr.aria-pressed]="requestFilter() === filter" (click)="requestFilter.set(filter)">
                {{ i18n.text(filterLabelKey(filter)) }} <span class="filter-count">{{ countForFilter(filter) }}</span>
              </button>
            }
          </div>
        </section>
        @if (!visibleRequests().length) {
          <section class="empty no-matches"><h2>{{ i18n.text('fieldNoMatchingRequests') }}</h2><button type="button" (click)="clearFilters()">{{ i18n.text('fieldClearFilters') }}</button></section>
        } @else {
        <div class="request-list">
          @for (request of visibleRequests(); track request.id) {
            <article class="request-card" [class.expanded]="expandedRequestId() === request.id">
              <header class="request-heading">
                <div class="request-title">
                  <a [routerLink]="['/work/jobs', request.jobId]" [queryParams]="{ view: 'field' }">
                    <b>#{{ request.jobNumber }}</b> {{ request.jobTitle }}
                  </a>
                  <p>{{ request.customerName }}@if (request.address?.addressLine1) { <span> · {{ request.address.addressLine1 }}, {{ request.address.city }}</span> }</p>
                </div>
                <span class="status" [class.pending]="request.status === 'PENDING'" [class.clarification]="request.status === 'NEEDS_CLARIFICATION'">{{ request.status | workLabel }}</span>
              </header>
              <div class="request-summary">
                <div class="summary-fact">
                  <span>{{ i18n.text('fieldProposedVisit') }}</span>
                  <strong>{{ request.proposedStart | date:'mediumDate':undefined:i18n.language() }} · {{ request.proposedStart | preferredTime }}–{{ request.proposedEnd | preferredTime }}</strong>
                </div>
                @if (latestMessage(request); as message) {
                  <p class="latest-message"><strong>{{ i18n.text(message.authorKind === 'OFFICE' ? 'fieldOfficeMessage' : 'fieldYourMessage') }}:</strong> {{ message.body }}</p>
                }
              </div>
              <button class="request-toggle" type="button" [attr.aria-label]="i18n.text(expandedRequestId() === request.id ? 'fieldRequestHideDetails' : 'fieldRequestShowDetails') + ' #' + request.jobNumber + ' ' + request.jobTitle" [attr.aria-expanded]="expandedRequestId() === request.id" [attr.aria-controls]="expandedRequestId() === request.id ? 'request-details-' + request.id : null" (click)="toggleRequest(request.id)">
                <span>{{ i18n.text(expandedRequestId() === request.id ? 'fieldRequestHideDetails' : 'fieldRequestShowDetails') }}</span>
                <span class="toggle-mark" aria-hidden="true">{{ expandedRequestId() === request.id ? '−' : '+' }}</span>
              </button>
              @if (expandedRequestId() === request.id) {
                <div class="request-details" [id]="'request-details-' + request.id">
                  <p class="reason"><strong>{{ i18n.text('fieldReason') }}:</strong> {{ request.reason }}</p>
                  <dl class="request-facts">
                    <div><dt>{{ i18n.text('fieldRequestSource') }}</dt><dd>{{ request.sourceVisitScheduledStart ? ((request.sourceVisitScheduledStart | date:'mediumDate':undefined:i18n.language()) + ' · ' + (request.sourceVisitScheduledStart | preferredTime)) : i18n.text('fieldNoSourceVisit') }}</dd></div>
                  </dl>
                  @if (request.reviewNote && !request.messages.length) { <p class="office-note"><strong>{{ i18n.text('fieldOfficeDecision') }}:</strong> {{ request.reviewNote }}</p> }
                  @if (request.messages.length) {
                    <section class="conversation" [attr.aria-label]="i18n.text('fieldRequestConversation')">
                      <h2>{{ i18n.text('fieldRequestConversation') }}</h2>
                      @for (message of request.messages; track message.id) {
                        <article class="message"><strong>{{ i18n.text(message.authorKind === 'OFFICE' ? 'fieldOfficeMessage' : 'fieldYourMessage') }}</strong><p>{{ message.body }}</p><time>{{ message.recordedAt | date:'medium':undefined:i18n.language() }}</time></article>
                      }
                    </section>
                  }
                  @if (request.status === 'NEEDS_CLARIFICATION') {
                    <label class="reply-label" [for]="'reply-' + request.id">{{ i18n.text('fieldReply') }}</label>
                    <textarea [id]="'reply-' + request.id" rows="3" maxlength="2000" [value]="replies()[request.id] || ''" (input)="setReply(request.id, $any($event.target).value)" [placeholder]="i18n.text('fieldReplyHint')"></textarea>
                    <button class="send" type="button" (click)="reply(request)" [disabled]="busyId() === request.id || !replies()[request.id]?.trim()">{{ i18n.text('fieldSendReply') }}</button>
                  } @else if (request.status === 'PENDING') { <p class="waiting">{{ i18n.text('fieldRequestAwaitingOffice') }}</p> }
                  @else if (request.status === 'APPROVED') { <p class="resolved">{{ i18n.text('fieldRequestApproved') }}</p> }
                  @else if (request.status === 'REJECTED') { <p class="resolved">{{ i18n.text('fieldRequestRejected') }}@if (request.reviewNote) { <span> · {{ request.reviewNote }}</span> }</p> }
                </div>
              }
            </article>
          }
        </div>
        }
      }
    </section>
  `,
  styles: [`
    :host{display:block}.requests-page{max-width:1120px;margin:0 auto;padding:28px clamp(18px,3vw,40px) 48px;color:var(--mat-sys-on-surface)}
    .back-link{color:var(--mat-sys-primary);font-weight:600;text-decoration:none}.page-header{display:flex;align-items:center;justify-content:space-between;gap:20px;margin:18px 0 22px}.page-header h1{margin:3px 0;font-size:28px}.page-header p:last-child,.request-heading p{margin:5px 0;color:var(--mat-sys-on-surface-variant)}.eyebrow{margin:0;color:var(--mat-sys-primary);font-size:12px;font-weight:700}.page-header button,.send,.no-matches button{min-height:44px;padding:8px 16px;border:1px solid var(--mat-sys-outline);border-radius:8px;background:var(--mat-sys-surface);color:var(--mat-sys-on-surface);font:inherit;font-weight:650;cursor:pointer}.request-filters{display:grid;grid-template-columns:minmax(220px,1fr) auto;align-items:end;gap:10px 16px;margin:0 0 16px}.search-label{grid-column:1/-1;margin-bottom:-5px;font-size:13px;font-weight:650}.request-filters input{box-sizing:border-box;width:100%;min-height:44px;padding:9px 12px;border:1px solid var(--mat-sys-outline);border-radius:8px;background:var(--mat-sys-surface);color:var(--mat-sys-on-surface);font:inherit}.filter-tabs{display:flex;flex-wrap:wrap;gap:6px}.filter-tabs button{min-height:40px;padding:7px 10px;border:1px solid var(--mat-sys-outline-variant);border-radius:8px;background:var(--mat-sys-surface);color:var(--mat-sys-on-surface);font:inherit;font-size:13px;font-weight:600;cursor:pointer}.filter-tabs button.active{border-color:var(--mat-sys-primary);background:var(--mat-sys-primary-container);color:var(--mat-sys-on-primary-container)}.filter-count{display:inline-block;min-width:1.4em;margin-left:3px;text-align:center;font-variant-numeric:tabular-nums}.request-list{display:grid;gap:10px}.request-card,.empty{padding:17px 20px;border:1px solid var(--mat-sys-outline-variant);border-radius:14px;background:var(--mat-sys-surface);box-shadow:0 3px 12px #162d4a08}.request-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:14px}.request-title{min-width:0}.request-heading a{color:var(--mat-sys-primary);font-size:18px;font-weight:650;text-decoration:none;overflow-wrap:anywhere}.request-heading a app-icon{display:inline-flex;vertical-align:middle}.status{flex:none;padding:5px 9px;border-radius:999px;background:var(--mat-sys-surface-container);font-size:12px;font-weight:650}.status.pending{background:var(--mat-sys-secondary-container);color:var(--mat-sys-on-secondary-container)}.status.clarification{background:var(--mat-sys-tertiary-container);color:var(--mat-sys-on-tertiary-container)}.request-summary{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.2fr);align-items:center;gap:12px;margin-top:12px}.summary-fact{display:grid;gap:4px;min-width:0}.summary-fact span{color:var(--mat-sys-on-surface-variant);font-size:12px;font-weight:650}.summary-fact strong{font-size:14px;overflow-wrap:anywhere}.latest-message{margin:0;color:var(--mat-sys-on-surface-variant);font-size:13px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.request-toggle{display:flex;justify-content:space-between;align-items:center;width:100%;margin-top:12px;padding:10px 0 0;border:0;border-top:1px solid var(--mat-sys-outline-variant);background:transparent;color:var(--mat-sys-primary);font:inherit;font-size:13px;font-weight:650;text-align:left;cursor:pointer}.toggle-mark{font-size:19px;line-height:1}.request-details{padding-top:2px}.request-facts{display:grid;grid-template-columns:minmax(0,1fr);gap:10px;margin:14px 0}.request-facts div{padding:11px;border-radius:9px;background:var(--mat-sys-surface-container-low)}dt{color:var(--mat-sys-on-surface-variant);font-size:12px;font-weight:650}dd{margin:5px 0 0;font-weight:600}.reason,.office-note{line-height:1.5}.conversation{margin-top:18px;padding-top:14px;border-top:1px solid var(--mat-sys-outline-variant)}.conversation h2{font-size:15px}.message{margin:9px 0;padding:12px;border-left:3px solid var(--mat-sys-primary);background:var(--mat-sys-surface-container-low)}.message p{margin:6px 0;white-space:pre-wrap;overflow-wrap:anywhere}.message time{color:var(--mat-sys-on-surface-variant);font-size:12px}.reply-label{display:block;margin:18px 0 6px;font-weight:650}textarea{box-sizing:border-box;width:100%;padding:11px;border:1px solid var(--mat-sys-outline);border-radius:8px;background:var(--mat-sys-surface);color:var(--mat-sys-on-surface);font:inherit;resize:vertical}.send{margin-top:10px;background:var(--mat-sys-primary);border-color:var(--mat-sys-primary);color:var(--mat-sys-on-primary)}button:disabled{opacity:.55;cursor:default}.waiting,.resolved{padding:11px;border-radius:8px;background:var(--mat-sys-surface-container-low)}.error{padding:12px;border:1px solid var(--mat-sys-error);border-radius:8px;color:var(--mat-sys-on-error-container)}.state,.empty{text-align:center;color:var(--mat-sys-on-surface-variant)}.empty h2{color:var(--mat-sys-on-surface)}.no-matches button{margin-top:8px}button:focus-visible,a:focus-visible,textarea:focus-visible,input:focus-visible{outline:3px solid var(--mat-sys-primary);outline-offset:2px}
    @media(max-width:760px){.requests-page{padding:18px 18px 32px}.page-header{align-items:flex-start}.page-header h1{font-size:24px}.request-card{padding:15px}.request-summary{grid-template-columns:1fr;gap:7px}.request-heading{gap:8px}.request-filters{grid-template-columns:1fr}.search-label{grid-column:auto}.filter-tabs{display:grid;grid-template-columns:repeat(2,minmax(0,1fr))}.filter-tabs button{text-align:left}}
    @media(max-width:520px){.requests-page{padding:18px 14px 32px}.page-header{align-items:flex-start;flex-direction:column}.page-header h1{font-size:24px}.page-header button{width:100%}.request-card{padding:14px}.request-heading{align-items:flex-start;flex-direction:column}.request-heading a{font-size:16px}.status{align-self:flex-start}.latest-message{white-space:normal;display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:2}}
  `],
})
export class FieldRequestsPage {
  protected readonly i18n = inject(I18n);
  private readonly auth = inject(AuthSession);
  private readonly session = inject(FieldSession);
  private readonly http = inject(HttpClient);
  protected readonly requests = signal<FieldFollowUpDto[]>([]);
  protected readonly filters = ['ALL', 'NEEDS_REPLY', 'WAITING', 'RESOLVED'] as const;
  protected readonly requestFilter = signal<(typeof this.filters)[number]>('ALL');
  protected readonly searchTerm = signal('');
  protected readonly visibleRequests = computed(() => {
    const filter = this.requestFilter();
    const query = this.searchTerm().trim().toLocaleLowerCase();
    return this.requests().filter((request) => this.matchesFilter(request, filter) && (!query || [
      request.jobNumber, request.jobTitle, request.customerName, request.address?.addressLine1,
      request.address?.city, request.reason, ...request.messages.map((message) => message.body),
    ].filter(Boolean).join(' ').toLocaleLowerCase().includes(query)));
  });
  protected readonly expandedRequestId = signal<string | null>(null);
  protected readonly replies = signal<Record<string, string>>({});
  protected readonly loading = signal(true);
  protected readonly error = signal<'fieldContextUnavailable' | 'fieldRequestFailed' | 'forbiddenDescription' | null>(null);
  protected readonly busyId = signal<string | null>(null);

  constructor() { void this.load(); }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    if (!this.auth.hasPermission('visits.request_follow_up')) {
      this.requests.set([]);
      this.expandedRequestId.set(null);
      this.error.set('forbiddenDescription');
      this.loading.set(false);
      return;
    }
    try {
      const scope = this.session.context() ?? await this.session.verify();
      const live = this.session.lease();
      const rows = await firstValueFrom(this.http.get<FieldFollowUpDto[]>('/jobs/visit-requests', this.session.options(scope)));
      if (live()) {
        this.requests.set(rows);
        const expandedId = this.expandedRequestId();
        if (!expandedId || !rows.some((row) => row.id === expandedId))
          this.expandedRequestId.set(rows.find((row) => row.status === 'NEEDS_CLARIFICATION')?.id ?? null);
      }
    } catch {
      this.error.set(this.session.issue() ? 'fieldContextUnavailable' : 'fieldRequestFailed');
    } finally { this.loading.set(false); }
  }

  protected setReply(id: string, value: string): void { this.replies.update((rows) => ({ ...rows, [id]: value })); }

  protected toggleRequest(id: string): void {
    this.expandedRequestId.update((expanded) => expanded === id ? null : id);
  }

  protected latestMessage(request: FieldFollowUpDto): FieldFollowUpDto['messages'][number] | null {
    return request.messages.at(-1) ?? null;
  }

  protected filterLabelKey(filter: (typeof this.filters)[number]): 'fieldFilterAll' | 'fieldFilterNeedsReply' | 'fieldFilterWaiting' | 'fieldFilterResolved' {
    const labels = { ALL: 'fieldFilterAll', NEEDS_REPLY: 'fieldFilterNeedsReply', WAITING: 'fieldFilterWaiting', RESOLVED: 'fieldFilterResolved' } as const;
    return labels[filter];
  }

  protected countForFilter(filter: (typeof this.filters)[number]): number {
    return this.requests().filter((request) => this.matchesFilter(request, filter)).length;
  }

  protected clearFilters(): void { this.searchTerm.set(''); this.requestFilter.set('ALL'); }

  private matchesFilter(request: FieldFollowUpDto, filter: (typeof this.filters)[number]): boolean {
    if (filter === 'NEEDS_REPLY') return request.status === 'NEEDS_CLARIFICATION';
    if (filter === 'WAITING') return request.status === 'PENDING';
    if (filter === 'RESOLVED') return request.status === 'APPROVED' || request.status === 'REJECTED';
    return true;
  }

  protected async reply(request: FieldFollowUpDto): Promise<void> {
    const body = this.replies()[request.id]?.trim();
    if (!body || this.busyId()) return;
    this.busyId.set(request.id);
    this.error.set(null);
    try {
      const scope = this.session.context() ?? await this.session.verify();
      await firstValueFrom(this.http.post(`/jobs/${encodeURIComponent(request.jobId)}/visit-requests/${encodeURIComponent(request.id)}/reply`, {
        body, expectedStatus: request.status, expectedVersion: request.version,
      }, this.session.options(scope)));
      this.replies.update((rows) => ({ ...rows, [request.id]: '' }));
      await this.load();
    } catch { this.error.set('fieldRequestFailed'); }
    finally { this.busyId.set(null); }
  }
}
