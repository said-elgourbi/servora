import { FieldWorkflow } from '../../field/ui/field-workflow';
import { AuthSession } from '../../auth/data-access/auth-session';
import { PageTitle } from '../../../shared/ui/page-title';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { Icon } from '../../../shared/ui/icon';
import { WorkStatus } from '../../../shared/ui/work-status';
import { HttpErrorResponse } from '@angular/common/http';
import { JobsApi } from '../data-access/jobs-api';
import { OfficeJobWorkflow } from '../ui/office-job-workflow';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import type { OfficeJobDetails } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import { JobContact } from '../ui/job-contact';
import { JobVisit } from '../ui/job-visit';
import { addressText, directionsUrl } from '../../../shared/ui/work-format';
@Component({
  selector: 'app-job-details',
  imports: [
    FieldWorkflow,
    OfficeJobWorkflow,
    PageTitle,
    RouterLink,
    MatButtonModule,
    JobVisit,
    JobContact,
    Icon,
    WorkStatus,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './job-details.html',
  styleUrl: './job-details.scss',
})
export class JobDetails {
  protected readonly i18n = inject(I18n);
  protected readonly auth = inject(AuthSession);
  protected readonly officeAllowed = computed(() => this.auth.hasPermission('customers.view'));
  private readonly fieldAllowed = computed(() => this.auth.hasPermission('VISIT_VIEW_ASSIGNED'));
  protected fieldView(): boolean {
    return this.query()?.get('view') === 'field' || (!this.officeAllowed() && this.fieldAllowed());
  }
  protected jobId(): string {
    return this.params()?.get('jobId') ?? '';
  }
  private readonly route = inject(ActivatedRoute);
  private readonly params = toSignal(this.route.paramMap);
  protected readonly query = toSignal(this.route.queryParamMap);
  private readonly api = inject(JobsApi);
  private readonly revision = signal(0);
  protected readonly job = signal<OfficeJobDetails | null>(null);
  protected readonly officeBusy = signal(false);
  protected readonly loading = signal(false);
  protected readonly error = signal<
    'forbiddenDescription' | 'jobUnavailable' | 'connectionError' | 'unexpectedError' | null
  >(null);
  protected readonly address = addressText;
  protected readonly directions = directionsUrl;
  constructor() {
    effect((cleanup) => {
      const id = this.params()?.get('jobId');
      this.revision();
      this.auth.boundary();
      const officeAllowed = this.officeAllowed();
      this.officeBusy.set(false);
      this.job.set(null);
      this.error.set(null);
      this.loading.set(false);
      if (!id || this.fieldView()) return;
      if (!officeAllowed) {
        this.error.set('forbiddenDescription');
        return;
      }
      this.loading.set(true);
      const request = this.api.detail(id).subscribe({
        next: (job) => {
          this.job.set(job);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.error.set(
            error instanceof HttpErrorResponse
              ? error.status === 403
                ? 'forbiddenDescription'
                : error.status === 404
                  ? 'jobUnavailable'
                  : error.status === 0
                    ? 'connectionError'
                    : 'unexpectedError'
              : 'unexpectedError',
          );
        },
      });
      cleanup(() => request.unsubscribe());
    });
  }
  protected visitAttention(job: OfficeJobDetails, visitId: string): string[] {
    return job.attention
      .filter((item) => item.relevantVisitId === visitId)
      .map((item) => item.code);
  }
  protected hasRequestedVisit(job: OfficeJobDetails): boolean {
    return job.visits.some((visit) => visit.id === this.query()?.get('visit'));
  }
  protected refresh(): void {
    this.officeBusy.set(false);
    this.revision.update((value) => value + 1);
  }
}
