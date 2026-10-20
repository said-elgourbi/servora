import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import type { HomeVisitDto } from '@servora/shared-types';
import { I18n } from '../../../core/i18n/i18n';
import { Icon } from '../../../shared/ui/icon';
import { WorkStatus } from '../../../shared/ui/work-status';
import { WorkLabel } from '../../../shared/ui/work-label';
import { addressText } from '../../../shared/ui/work-format';
@Component({
  selector: 'app-visit-card',
  imports: [RouterLink, DatePipe, WorkLabel, Icon, WorkStatus],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<a
    class="visit-card"
    [routerLink]="['/work/jobs', visit().jobId]"
    [queryParams]="{ visit: visit().visitId, view: view() }"
  >
    <div class="visit-meta">
      <span
        >{{ visit().scheduledStart | date: 'medium' : undefined : i18n.language() }} –
        {{ visit().scheduledEnd | date: 'shortTime' : undefined : i18n.language() }}</span
      ><app-work-status [code]="visit().visitStatus" />
    </div>
    <h3>{{ visit().jobTitle }}<app-icon name="arrow" /></h3>
    <p class="card-customer">
      <app-icon name="building" />{{ visit().customerName
      }}<span class="job-number">#{{ visit().jobNumber }}</span>
    </p>
    @if (visit().address?.propertyName; as name) {
      <p class="property-name">{{ name }}</p>
    }
    <p class="card-address">
      <app-icon name="pin" />{{ address(visit().address) || i18n.text('noAddress') }}
    </p>
    <div class="visit-meta">
      <span>{{ visit().purposeCode | workLabel }}</span>
      @if (visit().overdue) {
        <strong class="condition">{{ i18n.text('overdue') }}</strong>
      }
      @if (staleStarted()) {
        <strong class="condition">{{ i18n.text('staleStarted') }}</strong>
      }
    </div>
    <p class="card-crew">
      <app-icon name="people" />{{ i18n.text('crew') }}:
      @for (member of visit().technicians; track member.membershipId; let last = $last) {
        {{ member.name || i18n.text('unnamedMember') }}{{ last ? '' : ', ' }}
      } @empty {
        {{ i18n.text('noCrew') }}
      }
    </p>
  </a>`,
  styleUrl: './visit-card.scss',
})
export class VisitCard {
  readonly visit = input.required<HomeVisitDto>();
  readonly view = input.required<string>();
  readonly staleStarted = input(false);
  protected readonly i18n = inject(I18n);
  protected readonly address = addressText;
}
