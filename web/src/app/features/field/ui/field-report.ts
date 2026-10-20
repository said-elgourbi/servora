import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { I18n } from '../../../core/i18n/i18n';
import { AppUpdates } from '../../../core/updates/app-updates';
import { WorkLabel } from '../../../shared/ui/work-label';
import { AuthSession } from '../../auth/data-access/auth-session';
import { FieldSession, fieldPartition } from '../data-access/field-session';
import { FieldStore, type FieldCommand } from '../data-access/field-store';
import { FieldQueue } from '../data-access/field-queue';

@Component({
  selector: 'app-field-report',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, ReactiveFormsModule, WorkLabel],
  styleUrl: './field-workflow.scss',
  template: `
    <a routerLink="/" [queryParams]="{ view: 'field' }">{{ i18n.text('backToDay') }}</a>
    <h1>{{ i18n.text('fieldAdHoc') }}</h1>
    @if (error()) {
      <p role="alert">{{ i18n.text(error()!) }}</p>
    }
    @if (ready() && session.context() && auth.hasPermission('visits.report_ad_hoc_work')) {
      <form [formGroup]="form" (ngSubmit)="submit()" class="panel">
        <label for="report-customer">{{ i18n.text('fieldCustomerName') }}</label
        ><input id="report-customer" formControlName="reportedCustomerName" />
        <label for="report-phone">{{ i18n.text('fieldCustomerPhone') }}</label
        ><input id="report-phone" formControlName="reportedCustomerPhone" type="tel" />
        <label for="report-address">{{ i18n.text('fieldCustomerAddress') }}</label
        ><textarea id="report-address" formControlName="reportedCustomerAddress"></textarea>
        <label for="report-start">{{ i18n.text('fieldStart') }}</label
        ><input id="report-start" type="datetime-local" formControlName="workStartedAt" />
        <label for="report-end">{{ i18n.text('fieldEnd') }}</label
        ><input id="report-end" type="datetime-local" formControlName="workEndedAt" />
        <label for="report-outcome">{{ i18n.text('fieldOutcome') }}</label
        ><select id="report-outcome" formControlName="outcomeCode">
          <option value="">{{ i18n.text('fieldChooseOutcome') }}</option>
          @for (outcome of outcomes; track outcome) {
            <option [value]="outcome">{{ outcome | workLabel }}</option>
          }
        </select>
        <label for="report-summary">{{ i18n.text('fieldReportSummary') }}</label
        ><textarea id="report-summary" formControlName="summary" maxlength="2000"></textarea>
        <p role="status">{{ i18n.text(saved() ? 'fieldSaved' : 'fieldDraft') }}</p>
        <p>{{ i18n.text('fieldStorageNotice') }}</p>
        <div class="actions">
          <button type="submit" [disabled]="form.invalid || busy()">
            {{ i18n.text('fieldSubmitReport') }}</button
          ><button type="button" (click)="discard()" [disabled]="busy()">
            {{ i18n.text('fieldDiscard') }}
          </button>
        </div>
      </form>
      <section class="panel">
        <h2>{{ i18n.text('fieldPending') }}</h2>
        <button type="button" (click)="queue.replay()">{{ i18n.text('retry') }}</button>
        @for (command of commands(); track command.operationId) {
          <article>
            <p>{{ command.payload['summary'] }}</p>
            <p>
              {{
                i18n.text(
                  command.state === 'accepted'
                    ? 'fieldAccepted'
                    : command.state === 'attention'
                      ? 'fieldNeedsAttention'
                      : command.state === 'uploading'
                        ? 'fieldUploading'
                        : 'fieldPending'
                )
              }}
            </p>
            @if (command.error) {
              <p>{{ i18n.text('fieldRefusalReview') }}</p>
            }
          </article>
        }
      </section>
    }
  `,
})
export class FieldReport {
  protected readonly i18n = inject(I18n);
  protected readonly session = inject(FieldSession);
  protected readonly auth = inject(AuthSession);
  private readonly store = inject(FieldStore);
  protected readonly queue = inject(FieldQueue);
  private readonly updates = inject(AppUpdates);
  protected readonly error = signal<
    'fieldStorageFailure' | 'fieldContextUnavailable' | 'fieldRequestFailed' | null
  >(null);
  protected readonly busy = signal(false);
  protected readonly ready = signal(false);
  protected readonly saved = signal(false);
  protected readonly commands = signal<FieldCommand[]>([]);
  private restoring = false;
  protected readonly outcomes = [
    'RESOLVED',
    'NEEDS_FOLLOW_UP',
    'NEEDS_PARTS',
    'UNABLE_TO_COMPLETE',
  ];
  protected readonly form = new FormGroup({
    reportedCustomerName: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(255)],
    }),
    reportedCustomerPhone: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(50)],
    }),
    reportedCustomerAddress: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(2000)],
    }),
    workStartedAt: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    workEndedAt: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    outcomeCode: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    summary: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(2000)],
    }),
  });
  constructor() {
    void this.restore();
    const changes = this.form.valueChanges.subscribe(() => {
      if (!this.restoring) {
        this.saved.set(false);
        void this.save().catch(() => this.error.set('fieldStorageFailure'));
      }
    });
    effect(() => {
      this.store.revision();
      const scope = this.session.context();
      if (scope)
        void this.store
          .records(scope)
          .then((rows) =>
            this.commands.set(
              rows.filter(
                (row): row is FieldCommand => row.kind === 'command' && row.operation === 'ad-hoc',
              ),
            ),
          )
          .catch(() => this.error.set('fieldStorageFailure'));
      else {
        this.form.reset({}, { emitEvent: false });
        this.commands.set([]);
      }
    });
    const unload = (event: BeforeUnloadEvent) => {
      if (!this.saved() && this.form.dirty) event.preventDefault();
    };
    window.addEventListener('beforeunload', unload);
    const unregister = this.updates.beforeReload(() => this.save());
    inject(DestroyRef).onDestroy(() => {
      changes.unsubscribe();
      unregister();
      window.removeEventListener('beforeunload', unload);
    });
  }
  private async restore(): Promise<void> {
    try {
      const scope = this.session.context() ?? (await this.session.verify());
      const rows = await this.store.records(scope);
      const draft = rows.find((row) => row.kind === 'report-draft');
      if (draft?.kind === 'report-draft') {
        this.restoring = true;
        this.form.patchValue(draft.data);
        this.restoring = false;
        this.saved.set(true);
      }
    } catch {
      this.error.set('fieldContextUnavailable');
    } finally {
      this.ready.set(true);
    }
  }
  private async save(): Promise<void> {
    const scope = this.session.context();
    if (!Object.values(this.form.getRawValue()).some(Boolean)) return;
    if (!scope) throw new Error('FIELD_CONTEXT_UNAVAILABLE');
    const data = this.form.getRawValue();
    await this.store.write(scope, [
      {
        key: this.store.key(scope, 'report-draft/current'),
        kind: 'report-draft',
        partition: fieldPartition(scope),
        jobId: '',
        savedAt: Date.now(),
        data,
      },
    ]);
    this.saved.set(JSON.stringify(data) === JSON.stringify(this.form.getRawValue()));
  }
  protected async submit(): Promise<void> {
    if (this.busy() || this.form.invalid) return;
    this.busy.set(true);
    try {
      const value = this.form.getRawValue();
      const start = new Date(value.workStartedAt);
      const end = new Date(value.workEndedAt);
      if (end <= start || !this.outcomes.includes(value.outcomeCode))
        throw new Error('INVALID_REPORT');
      await this.queue.submit('', '', 'ad-hoc', {
        ...value,
        workStartedAt: start.toISOString(),
        workEndedAt: end.toISOString(),
      });
      this.form.reset({}, { emitEvent: false });
      this.saved.set(false);
    } catch {
      this.error.set('fieldRequestFailed');
    } finally {
      this.busy.set(false);
    }
  }
  protected async discard(): Promise<void> {
    const scope = this.session.context();
    if (!scope || !window.confirm(this.i18n.text('fieldDiscardConfirm'))) return;
    try {
      await this.store.write(scope, [], [this.store.key(scope, 'report-draft/current')]);
      this.form.reset({}, { emitEvent: false });
      this.saved.set(false);
    } catch {
      this.error.set('fieldStorageFailure');
    }
  }
}
