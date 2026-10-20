import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { I18n } from '../../../core/i18n/i18n';
import { Appearance } from '../../../core/preferences/appearance';
import { Icon } from '../icon';
@Component({
  selector: 'app-preferences',
  imports: [MatButtonToggleModule, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: ` <mat-button-toggle-group
      [value]="i18n.language()"
      [attr.aria-label]="i18n.text('language')"
      [hideSingleSelectionIndicator]="true"
      (change)="i18n.setLanguage($event.value)"
    >
      <mat-button-toggle value="en" aria-label="English"
        ><span lang="en">EN</span></mat-button-toggle
      >
      <mat-button-toggle value="fr" aria-label="Français"
        ><span lang="fr">FR</span></mat-button-toggle
      >
    </mat-button-toggle-group>
    <mat-button-toggle-group
      [value]="appearance.mode()"
      [attr.aria-label]="i18n.text('theme')"
      [hideSingleSelectionIndicator]="true"
      (change)="appearance.setMode($event.value)"
    >
      <mat-button-toggle
        value="light"
        [aria-label]="i18n.text('light')"
        [title]="i18n.text('light')"
        ><app-icon name="sun"
      /></mat-button-toggle>
      <mat-button-toggle value="dark" [aria-label]="i18n.text('dark')" [title]="i18n.text('dark')"
        ><app-icon name="moon"
      /></mat-button-toggle>
      <mat-button-toggle
        value="system"
        [aria-label]="i18n.text('system')"
        [title]="i18n.text('system')"
        ><app-icon name="monitor"
      /></mat-button-toggle>
    </mat-button-toggle-group>`,
  styles: `
    :host {
      display: flex;
      align-items: center;
      gap: 12px;
      flex-wrap: wrap;
    }
    mat-button-toggle-group {
      --mat-button-toggle-height: 36px;
      --mat-button-toggle-shape: 10px;
      --mat-button-toggle-label-text-size: 11px;
      --mat-button-toggle-label-text-weight: 650;
      --mat-button-toggle-selected-state-background-color: var(--mat-sys-secondary-container);
      --mat-button-toggle-selected-state-text-color: var(--mat-sys-on-secondary-container);
    }
    app-icon {
      width: 16px;
      height: 16px;
    }
    @media (max-width: 900px) {
      mat-button-toggle-group {
        --mat-button-toggle-height: 44px;
      }
    }
  `,
})
export class Preferences {
  protected readonly i18n = inject(I18n);
  protected readonly appearance = inject(Appearance);
}
