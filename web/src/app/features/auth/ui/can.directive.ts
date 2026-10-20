import { Directive, TemplateRef, ViewContainerRef, effect, inject, input } from '@angular/core';
import { AuthSession } from '../data-access/auth-session';
/** Use *appCan="'permission.code'" for action visibility; the API still authorizes every action. */
@Directive({ selector: '[appCan]' })
export class CanDirective {
  readonly appCan = input.required<string>();
  private readonly session = inject(AuthSession);
  private readonly template = inject(TemplateRef<unknown>);
  private readonly container = inject(ViewContainerRef);
  constructor() {
    effect(() => {
      this.container.clear();
      if (this.session.hasPermission(this.appCan()))
        this.container.createEmbeddedView(this.template);
    });
  }
}
