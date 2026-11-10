import { DatePipe } from '@angular/common';
import { HttpClient, HttpContext, HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom, timeout } from 'rxjs';
import type { AssignableTechnician, FieldFollowUpDto, VisitScheduleConflict } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import type { TranslationKey } from '../../../core/i18n/en';
import { AuthSession } from '../../auth/data-access/auth-session';
import { AUTHENTICATED_REQUEST } from '../../auth/data-access/auth.interceptor';
import { Icon } from '../../../shared/ui/icon';
import { PageTitle } from '../../../shared/ui/page-title';
import { PreferredTime } from '../../../shared/ui/preferred-time';
import { WorkLabel } from '../../../shared/ui/work-label';

interface ApprovalDraft { start: string; end: string; crew: string[]; lead: string; }
@Component({
  selector: 'app-visit-requests-review',
  imports: [DatePipe, RouterLink, Icon, PageTitle, PreferredTime, WorkLabel],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="review-page">
      <header class="page-header"><div><p class="eyebrow">{{ i18n.text('officeSchedule') }}</p><h1 [appPageTitle]="i18n.text('visitRequestReviewTitle')">{{ i18n.text('visitRequestReviewTitle') }}</h1><p>{{ i18n.text('visitRequestReviewHint') }}</p></div>
        <div class="header-actions"><label class="resolved-toggle"><input type="checkbox" [checked]="showResolved()" (change)="showResolved.set($any($event.target).checked)"><span>{{ i18n.text('visitRequestShowResolved') }}</span></label><button type="button" (click)="load()" [disabled]="loading() || busyId() !== null">{{ i18n.text('refresh') }}</button></div>
      </header>
      @if (error()) { <p class="error" role="alert">{{ i18n.text(error()!) }}</p> }
      @if (notice()) { <p class="notice" role="status">{{ i18n.text(notice()!) }}</p> }
      @if (loading()) { <p class="state" role="status">{{ i18n.text('loadingMore') }}</p> }
      @else if (!visible().length) { <section class="empty"><h2>{{ i18n.text(showResolved() ? 'visitRequestNoResolved' : 'visitRequestNoPending') }}</h2><p>{{ i18n.text('visitRequestNoPendingHint') }}</p></section> }
      @else {
        <div class="request-layout">
          <nav class="request-list" [attr.aria-label]="i18n.text('visitRequestReviewList')">
            @for (request of visible(); track request.id) {
              <button type="button" class="request-row" [class.selected]="selectedId() === request.id" (click)="select(request)">
                <span class="row-top"><strong>#{{ request.jobNumber }} · {{ request.jobTitle }}</strong><span class="status" [class.open]="isOpen(request)">{{ request.status | workLabel }}</span></span>
                <span>{{ request.customerName }}</span><small>{{ request.proposedStart | date:'mediumDate':undefined:i18n.language() }} · {{ request.proposedStart | preferredTime }}–{{ request.proposedEnd | preferredTime }}</small>
              </button>
            }
          </nav>
          @if (selected(); as request) {
            <article class="request-detail">
              <header class="detail-title"><div><p class="eyebrow">{{ i18n.text('visitRequestProposal') }}</p><h2>#{{ request.jobNumber }} · {{ request.jobTitle }}</h2><p>{{ request.customerName }}@if (request.address?.addressLine1) { <span> · {{ request.address.addressLine1 }}, {{ request.address.city }}</span> }</p></div><span class="status" [class.open]="isOpen(request)">{{ request.status | workLabel }}</span></header>
              <a class="job-link" [routerLink]="['/work/jobs', request.jobId]">{{ i18n.text('visitRequestOpenJob') }} <app-icon name="arrow" /></a>
              <dl class="facts"><div><dt>{{ i18n.text('visitRequestProposedTime') }}</dt><dd>{{ request.proposedStart | date:'fullDate':undefined:i18n.language() }} · {{ request.proposedStart | preferredTime }}–{{ request.proposedEnd | preferredTime }}</dd></div><div><dt>{{ i18n.text('visitRequestSourceVisit') }}</dt><dd>@if (request.sourceVisitScheduledStart) { {{ (request.sourceVisitStatus || 'SCHEDULED') | workLabel }} · {{ request.sourceVisitScheduledStart | date:'mediumDate':undefined:i18n.language() }} · {{ request.sourceVisitScheduledStart | preferredTime }}@if (request.sourceVisitOutcomeCode) { · {{ request.sourceVisitOutcomeCode | workLabel }} } } @else { {{ i18n.text('visitRequestNoSourceVisit') }} }</dd></div><div><dt>{{ i18n.text('visitRequestTechnicianPreference') }}</dt><dd>{{ i18n.text(request.sameTechnicianPreferred ? 'visitRequestSameTechnician' : 'visitRequestAnyTechnician') }}</dd></div><div><dt>{{ i18n.text('visitRequestCreated') }}</dt><dd>{{ request.createdAt | date:'medium':undefined:i18n.language() }}</dd></div></dl>
              <section class="reason"><h3>{{ i18n.text('fieldReason') }}</h3><p>{{ request.reason }}</p></section>
              @if (request.messages.length) { <section class="conversation"><h3>{{ i18n.text('fieldRequestConversation') }}</h3>@for (message of request.messages; track message.id) { <article class="message"><strong>{{ i18n.text(message.authorKind === 'OFFICE' ? 'fieldOfficeMessage' : 'fieldYourMessage') }}</strong><p>{{ message.body }}</p><time>{{ message.recordedAt | date:'medium':undefined:i18n.language() }}</time></article> }</section> }
              @if (request.reviewNote && !request.messages.length) { <p class="review-note"><strong>{{ i18n.text('visitRequestLastDecision') }}:</strong> {{ request.reviewNote }}</p> }
              @if (isOpen(request)) {
                <section class="decision-panel"><h3>{{ i18n.text('visitRequestOfficeDecision') }}</h3>
                  <label for="review-note">{{ i18n.text('visitRequestDecisionNote') }}</label><textarea id="review-note" rows="3" maxlength="2000" [value]="notes()[request.id] || ''" (input)="setNote(request.id, $any($event.target).value)" [placeholder]="i18n.text('visitRequestDecisionNoteHint')"></textarea>
                  <div class="decision-actions"><button type="button" class="secondary" (click)="review(request, 'clarification')" [disabled]="busyId() === request.id || !notes()[request.id]?.trim()">{{ i18n.text('visitRequestClarify') }}</button><button type="button" class="danger" (click)="review(request, 'rejection')" [disabled]="busyId() === request.id || !notes()[request.id]?.trim()">{{ i18n.text('visitRequestReject') }}</button>@if (canApprove()) { <button type="button" class="primary" (click)="toggleApproval(request)" [disabled]="busyId() === request.id">{{ i18n.text('visitRequestApproveSchedule') }}</button> }</div>
                </section>
                @if (approvingId() === request.id && canApprove()) { <section class="approval-panel"><h3>{{ i18n.text('visitRequestScheduleCreatedVisit') }}</h3><p>{{ i18n.text('visitRequestScheduleHint') }}</p>
                  @if (technicianError()) { <p class="error" role="alert">{{ i18n.text(technicianError()!) }}</p> }
                  <div class="schedule-fields"><label>{{ i18n.text('jobVisitStartDate') }}<input type="datetime-local" [value]="draft(request).start" (input)="patchDraft(request.id, { start: $any($event.target).value })"></label><label>{{ i18n.text('jobVisitEndDate') }}<input type="datetime-local" [value]="draft(request).end" (input)="patchDraft(request.id, { end: $any($event.target).value })"></label></div>
                  <p class="crew-label">{{ i18n.text('jobVisitTechnicians') }}</p><div class="crew-options">@for (member of technicians(); track member.membershipId) { <label><input type="checkbox" [checked]="draft(request).crew.includes(member.membershipId)" (change)="toggleCrew(request.id, member.membershipId, $any($event.target).checked)"><span>{{ member.name || i18n.text('unnamedMember') }}</span></label> }</div>
                  <label class="lead-select">{{ i18n.text('jobVisitLead') }}<select [value]="draft(request).lead" (change)="patchDraft(request.id, { lead: $any($event.target).value })"><option value="">{{ i18n.text('jobVisitChooseLead') }}</option>@for (member of selectedTechnicians(request); track member.membershipId) { <option [value]="member.membershipId">{{ member.name || i18n.text('unnamedMember') }}</option> }</select></label>
                  @if (approvalError()) { <p class="error" role="alert">{{ i18n.text(approvalError()!) }}</p> }
                  @if (conflicts().length) { <div class="conflicts" role="alert"><strong>{{ i18n.text('jobVisitConflicts') }}</strong>@for (conflict of conflicts(); track conflict.visitId + conflict.technicianMembershipId) { <p>{{ conflict.technicianName || i18n.text('unnamedMember') }} · #{{ conflict.jobNumber }} · {{ conflict.scheduledStart | date:'medium':undefined:i18n.language() }}–{{ conflict.scheduledEnd | preferredTime }}</p> }<p>{{ i18n.text('visitRequestConflictConfirmHint') }}</p></div> }
                  <div class="decision-actions"><button class="secondary" type="button" (click)="approvingId.set(null)">{{ i18n.text('cancel') }}</button><button class="primary" type="button" (click)="approve(request, conflicts().length > 0)" [disabled]="busyId() === request.id || !validDraft(request)">{{ i18n.text(conflicts().length ? 'jobVisitConfirmConflicts' : 'visitRequestConfirmApproval') }}</button></div>
                </section> }
              } @else { <p class="closed-state">{{ i18n.text(request.status === 'APPROVED' ? 'visitRequestApprovedState' : 'visitRequestRejectedState') }}</p> }
            </article>
          }
        </div>
      }
    </section>
  `,
  styles: [`
    :host{display:block}.review-page{max-width:1480px;margin:0 auto;padding:24px clamp(16px,2.5vw,36px) 48px;color:var(--mat-sys-on-surface)}.page-header{display:flex;justify-content:space-between;align-items:flex-end;gap:20px;margin-bottom:20px}.page-header h1{margin:2px 0 4px;font-size:28px}.page-header p:last-child{margin:4px 0;color:var(--mat-sys-on-surface-variant)}.eyebrow{margin:0;color:var(--mat-sys-primary);font-size:11px;font-weight:700;letter-spacing:.08em;text-transform:uppercase}.header-actions{display:flex;align-items:center;gap:14px}.header-actions button,.decision-actions button{min-height:42px;padding:8px 13px;border:1px solid var(--mat-sys-outline);border-radius:8px;background:var(--mat-sys-surface);color:var(--mat-sys-on-surface);font:inherit;font-weight:650;cursor:pointer}.resolved-toggle{display:flex;align-items:center;gap:8px;font-size:13px}.resolved-toggle input,.crew-options input{width:18px;height:18px;accent-color:var(--mat-sys-primary)}.request-layout{display:grid;grid-template-columns:minmax(275px,360px) minmax(0,1fr);align-items:start;gap:18px}.request-list{display:grid;gap:8px;max-height:calc(100vh - 190px);overflow:auto}.request-row{display:grid;gap:7px;padding:13px;text-align:left;border:1px solid var(--mat-sys-outline-variant);border-radius:10px;background:var(--mat-sys-surface);color:var(--mat-sys-on-surface);font:inherit;cursor:pointer}.request-row.selected{border-color:var(--mat-sys-primary);box-shadow:0 0 0 1px var(--mat-sys-primary)}.row-top{display:flex;justify-content:space-between;align-items:flex-start;gap:8px}.row-top strong{line-height:1.35}.request-row>span:not(.row-top),.request-row small{color:var(--mat-sys-on-surface-variant)}.request-detail,.empty{min-width:0;padding:22px;border:1px solid var(--mat-sys-outline-variant);border-radius:13px;background:var(--mat-sys-surface);box-shadow:0 3px 12px #162d4a08}.detail-title{display:flex;justify-content:space-between;align-items:flex-start;gap:12px}.detail-title h2{margin:4px 0;font-size:23px;overflow-wrap:anywhere}.detail-title p:last-child{margin:5px 0;color:var(--mat-sys-on-surface-variant)}.status{flex:none;padding:5px 9px;border-radius:999px;background:var(--mat-sys-surface-container);font-size:12px;font-weight:650}.status.open{background:var(--mat-sys-secondary-container);color:var(--mat-sys-on-secondary-container)}.job-link{display:inline-flex;align-items:center;gap:5px;margin:10px 0;color:var(--mat-sys-primary);font-weight:650;text-decoration:none}.facts{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin:16px 0}.facts>div{padding:12px;border-radius:9px;background:var(--mat-sys-surface-container-low)}dt{color:var(--mat-sys-on-surface-variant);font-size:12px;font-weight:650}dd{margin:5px 0 0;font-size:14px;line-height:1.4;font-weight:600;overflow-wrap:anywhere}.reason,.conversation{padding-top:14px;border-top:1px solid var(--mat-sys-outline-variant)}.reason h3,.conversation h3,.decision-panel h3,.approval-panel h3{margin:0 0 8px;font-size:16px}.reason p{margin:0;line-height:1.5;white-space:pre-wrap}.message{margin:9px 0;padding:12px;border-left:3px solid var(--mat-sys-primary);background:var(--mat-sys-surface-container-low)}.message p{margin:6px 0;white-space:pre-wrap}.message time{color:var(--mat-sys-on-surface-variant);font-size:12px}.review-note,.closed-state,.notice,.error{padding:12px;border-radius:8px;background:var(--mat-sys-surface-container-low)}.decision-panel,.approval-panel{display:grid;gap:10px;margin-top:18px;padding:16px;border:1px solid var(--mat-sys-outline-variant);border-radius:10px}.decision-panel>label,.approval-panel label{display:grid;gap:6px;font-size:13px;font-weight:650}textarea,input[type=datetime-local],select{box-sizing:border-box;width:100%;min-height:42px;padding:9px 11px;border:1px solid var(--mat-sys-outline);border-radius:7px;background:var(--mat-sys-surface);color:var(--mat-sys-on-surface);font:inherit}textarea{resize:vertical}.decision-actions{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:8px}.decision-actions .primary{background:var(--mat-sys-primary);border-color:var(--mat-sys-primary);color:var(--mat-sys-on-primary)}.decision-actions .danger{color:var(--mat-sys-error);border-color:var(--mat-sys-error)}.schedule-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.crew-label{margin-top:2px}.crew-options{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:6px;max-height:210px;overflow:auto;padding:9px;border:1px solid var(--mat-sys-outline-variant);border-radius:8px}.crew-options label{display:flex!important;align-items:center;gap:8px;padding:6px;border-radius:6px}.lead-select{max-width:420px}.conflicts{padding:12px;border:1px solid var(--mat-sys-error);border-radius:8px;background:var(--mat-sys-error-container);color:var(--mat-sys-on-error-container)}.conflicts p{margin:7px 0}.state,.empty{text-align:center;color:var(--mat-sys-on-surface-variant)}.empty{padding:48px 20px}.error{border:1px solid var(--mat-sys-error);background:var(--mat-sys-error-container);color:var(--mat-sys-on-error-container)}button:focus-visible,a:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible{outline:3px solid var(--mat-sys-primary);outline-offset:2px}
    @media(max-width:900px){.review-page{padding:20px 14px 36px}.request-layout{grid-template-columns:minmax(0,1fr)}.request-list{grid-template-columns:repeat(2,minmax(0,1fr));max-height:290px}.request-detail{padding:17px}}
    @media(max-width:560px){.page-header{align-items:flex-start;flex-direction:column}.page-header h1{font-size:24px}.header-actions{width:100%;justify-content:space-between}.request-list{grid-template-columns:1fr;max-height:240px}.detail-title{flex-direction:column}.detail-title h2{font-size:20px}.facts,.schedule-fields,.crew-options{grid-template-columns:1fr}.request-detail{padding:14px}.decision-panel,.approval-panel{padding:12px}.decision-actions{justify-content:stretch}.decision-actions button{flex:1 1 auto}}
  `],
})
export class VisitRequestsReviewPage {
  protected readonly i18n = inject(I18n);
  private readonly auth = inject(AuthSession);
  private readonly http = inject(HttpClient);
  protected readonly canApprove = computed(() => this.auth.hasPermission('visits.review_requests') && this.auth.hasPermission('visits.create_schedule'));
  protected readonly rows = signal<FieldFollowUpDto[]>([]);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly selected = computed(() => this.rows().find((row) => row.id === this.selectedId()) ?? null);
  protected readonly showResolved = signal(false);
  protected readonly visible = computed(() => this.rows().filter((row) => this.showResolved() || this.isOpen(row)));
  protected readonly loading = signal(true);
  protected readonly error = signal<TranslationKey | null>(null);
  protected readonly notice = signal<TranslationKey | null>(null);
  protected readonly notes = signal<Record<string, string>>({});
  protected readonly busyId = signal<string | null>(null);
  protected readonly approvingId = signal<string | null>(null);
  protected readonly drafts = signal<Record<string, ApprovalDraft>>({});
  protected readonly technicians = signal<AssignableTechnician[]>([]);
  protected readonly technicianError = signal<TranslationKey | null>(null);
  protected readonly approvalError = signal<TranslationKey | null>(null);
  protected readonly conflicts = signal<VisitScheduleConflict[]>([]);

  constructor() { void this.load(); }
  private requestOptions() { return { context: new HttpContext().set(AUTHENTICATED_REQUEST, true), cache: 'no-store' as const }; }
  protected isOpen(row: FieldFollowUpDto): boolean { return row.status === 'PENDING' || row.status === 'NEEDS_CLARIFICATION'; }
  protected async load(): Promise<void> {
    this.loading.set(true); this.error.set(null);
    try {
      const rows = await firstValueFrom(this.http.get<FieldFollowUpDto[]>('/jobs/visit-requests', this.requestOptions()).pipe(timeout(15000)));
      this.rows.set(rows);
      const current = this.selectedId();
      const next = rows.find((row) => row.id === current) ?? rows.find((row) => this.isOpen(row)) ?? rows[0] ?? null;
      this.selectedId.set(next?.id ?? null);
    } catch (e) { this.error.set(this.errorKey(e)); }
    finally { this.loading.set(false); }
  }
  protected select(row: FieldFollowUpDto): void { this.selectedId.set(row.id); this.notice.set(null); this.error.set(null); this.approvalError.set(null); this.conflicts.set([]); }
  protected setNote(id: string, value: string): void { this.notes.update((all) => ({ ...all, [id]: value })); }
  protected async review(row: FieldFollowUpDto, action: 'clarification' | 'rejection'): Promise<void> {
    if (this.busyId()) return;
    this.busyId.set(row.id); this.error.set(null); this.notice.set(null);
    try {
      await firstValueFrom(this.http.post(`/jobs/${encodeURIComponent(row.jobId)}/visit-requests/${encodeURIComponent(row.id)}/${action}`, {
        note: this.notes()[row.id]?.trim() || null, expectedStatus: row.status, expectedVersion: row.version,
      }, this.requestOptions()).pipe(timeout(20000)));
      this.notice.set(action === 'clarification' ? 'visitRequestClarified' : 'visitRequestRejectedNotice');
      this.approvingId.set(null);
      await this.load();
    } catch (e) { await this.actionFailed(e); }
    finally { this.busyId.set(null); }
  }
  protected async toggleApproval(row: FieldFollowUpDto): Promise<void> {
    if (this.approvingId() === row.id) { this.approvingId.set(null); this.conflicts.set([]); return; }
    this.approvingId.set(row.id); this.approvalError.set(null); this.conflicts.set([]);
    if (!this.technicians().length) await this.loadTechnicians();
    if (!this.drafts()[row.id]) this.patchDraft(row.id, {
      start: this.toLocalInput(row.proposedStart), end: this.toLocalInput(row.proposedEnd), crew: [], lead: '',
    });
  }
  private async loadTechnicians(): Promise<void> {
    this.technicianError.set(null);
    try { this.technicians.set(await firstValueFrom(this.http.get<AssignableTechnician[]>('/technicians', this.requestOptions()).pipe(timeout(15000)))); }
    catch (e) { this.technicianError.set(this.errorKey(e)); }
  }
  protected draft(row: FieldFollowUpDto): ApprovalDraft { return this.drafts()[row.id] ?? { start: '', end: '', crew: [], lead: '' }; }
  protected patchDraft(id: string, patch: Partial<ApprovalDraft>): void {
    this.drafts.update((all) => ({ ...all, [id]: { ...(all[id] ?? { start: '', end: '', crew: [], lead: '' }), ...patch } }));
    this.conflicts.set([]); this.approvalError.set(null);
  }
  protected toggleCrew(id: string, membershipId: string, checked: boolean): void {
    const current = this.drafts()[id] ?? { start: '', end: '', crew: [], lead: '' };
    const crew = checked ? [...new Set([...current.crew, membershipId])] : current.crew.filter((member) => member !== membershipId);
    this.patchDraft(id, { crew, lead: current.lead === membershipId && !checked ? '' : current.lead });
  }
  protected selectedTechnicians(row: FieldFollowUpDto): AssignableTechnician[] { return this.technicians().filter((member) => this.draft(row).crew.includes(member.membershipId)); }
  protected validDraft(row: FieldFollowUpDto): boolean {
    const draft = this.draft(row); const start = new Date(draft.start); const end = new Date(draft.end);
    return !!draft.start && !!draft.end && Number.isFinite(start.getTime()) && Number.isFinite(end.getTime()) && end > start && start > new Date() && draft.crew.length > 0 && draft.crew.includes(draft.lead) && !this.technicianError();
  }
  protected async approve(row: FieldFollowUpDto, confirmConflicts: boolean): Promise<void> {
    if (!this.validDraft(row) || this.busyId()) return;
    const draft = this.draft(row); this.busyId.set(row.id); this.approvalError.set(null);
    try {
      await firstValueFrom(this.http.post(`/jobs/${encodeURIComponent(row.jobId)}/visit-requests/${encodeURIComponent(row.id)}/approval`, {
        scheduledStart: new Date(draft.start).toISOString(), scheduledEnd: new Date(draft.end).toISOString(),
        technicians: draft.crew.map((membershipId) => ({ membershipId, roleCode: membershipId === draft.lead ? 'LEAD' : 'TECHNICIAN' })),
        confirmConflicts, expectedStatus: row.status, expectedVersion: row.version, note: this.notes()[row.id]?.trim() || null,
      }, this.requestOptions()).pipe(timeout(20000)));
      this.notice.set('visitRequestApprovedNotice'); this.approvingId.set(null); this.conflicts.set([]); await this.load();
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.error?.code === 'SCHEDULE_CONFLICT' && Array.isArray(e.error?.details?.conflicts)) {
        this.conflicts.set(e.error.details.conflicts as VisitScheduleConflict[]); this.approvalError.set('visitRequestScheduleConflict');
      } else {
        this.approvalError.set(this.errorKey(e));
        if (e instanceof HttpErrorResponse && e.status === 409) await this.load();
      }
    } finally { this.busyId.set(null); }
  }
  private async actionFailed(error: unknown): Promise<void> {
    this.error.set(error instanceof HttpErrorResponse && error.status === 409 ? 'visitRequestConflict' : this.errorKey(error));
    if (error instanceof HttpErrorResponse && error.status === 409) await this.load();
  }
  private errorKey(error: unknown): TranslationKey {
    if (error instanceof HttpErrorResponse && error.status === 403) return 'forbiddenDescription';
    if (error instanceof HttpErrorResponse && error.status === 409) return 'visitRequestConflict';
    return 'visitRequestFailed';
  }
  private toLocalInput(iso: string): string {
    const date = new Date(iso); const pad = (value: number) => String(value).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
  }
}
