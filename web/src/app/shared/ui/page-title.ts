import { Directive, effect, inject, input } from '@angular/core';
import { Title } from '@angular/platform-browser';

@Directive({ selector: '[appPageTitle]' })
export class PageTitle {
  readonly appPageTitle = input.required<string>();
  private readonly title = inject(Title);

  constructor() {
    effect(() => this.title.setTitle(`${this.appPageTitle()} | Servora`));
  }
}
