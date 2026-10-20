import { ChangeDetectionStrategy, Component, effect, inject } from '@angular/core';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import type { PropertyDetails } from '@servora/shared-types';
import { AuthSession } from '../../auth/data-access/auth-session';
import { I18n } from '../../../core/i18n/i18n';
@Component({
  selector: 'app-property-confirm',
  imports: [MatDialogModule, MatButtonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<h2 mat-dialog-title>
      {{
        i18n.text(
          data.action === 'archive'
            ? 'propertyArchive'
            : data.action === 'restore'
              ? 'propertyRestore'
              : 'propertyDelete'
        )
      }}
    </h2>
    <mat-dialog-content
      ><p>{{ data.property.name || data.property.addressLine1 }}</p>
      <p>
        {{
          i18n.text(
            data.action === 'archive'
              ? 'propertyArchiveDescription'
              : data.action === 'restore'
                ? 'propertyRestoreDescription'
                : 'propertyDeleteDescription'
          )
        }}
      </p>
      @if (data.action === 'archive') {
        <p>{{ i18n.text('propertyOpenWork') }}</p>
        <p>
          {{ data.property.archiveImpact.activeJobCount }} {{ i18n.text('propertyActiveJobs') }} ·
          {{ data.property.archiveImpact.activeVisitCount }} {{ i18n.text('propertyActiveVisits') }}
        </p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end"
      ><button mat-button [mat-dialog-close]="false" cdkFocusInitial>
        {{ i18n.text('cancel') }}</button
      ><button mat-flat-button [mat-dialog-close]="true">
        {{ i18n.text('customerConfirm') }}
      </button></mat-dialog-actions
    >`,
})
export class PropertyConfirm {
  protected readonly i18n = inject(I18n);
  protected readonly data = inject<{
    action: 'archive' | 'restore' | 'delete';
    property: PropertyDetails;
  }>(MAT_DIALOG_DATA);
  constructor() {
    const auth = inject(AuthSession),
      ref = inject(MatDialogRef<PropertyConfirm>),
      boundary = auth.boundary();
    effect(() => {
      if (auth.boundary() !== boundary) ref.close(false);
    });
  }
}
