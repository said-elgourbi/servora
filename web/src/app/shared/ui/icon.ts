import { ChangeDetectionStrategy, Component, input } from '@angular/core';

const paths = {
  sun: 'M12 3V1M12 23v-2M3 12H1M23 12h-2M5.6 5.6 4.2 4.2M19.8 19.8l-1.4-1.4M5.6 18.4l-1.4 1.4M19.8 4.2l-1.4 1.4M17 12a5 5 0 1 1-10 0 5 5 0 0 1 10 0Z',
  moon: 'M21 13a9 9 0 0 1-10-10A9 9 0 1 0 21 13Z',
  monitor: 'M3 3h18v14H3ZM12 17v4M8 21h8',
  search: 'M21 21l-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z',
  plus: 'M12 5v14M5 12h14',
  home: 'M3 10.5 12 3l9 7.5M5 9v12h5v-7h4v7h5V9',
  arrow: 'm9 5 7 7-7 7',
  back: 'm14 6-6 6 6 6M8 12h13',
  refresh: 'M20 7v5h-5M4 17v-5h5M6.1 7a7 7 0 0 1 11.8-1L20 9M4 15l2.1 3a7 7 0 0 0 11.8-1',
  pin: 'M20 10c0 6-8 11-8 11S4 16 4 10a8 8 0 1 1 16 0ZM15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z',
  phone: 'm7 3 3 5-3 3a17 17 0 0 0 6 6l3-3 5 3-1 4C10 23 1 14 3 4Z',
  mail: 'M3 5h18v14H3ZM3 6l9 7 9-7',
  calendar: 'M5 5h14a2 2 0 0 1 2 2v13H3V7a2 2 0 0 1 2-2ZM7 3v5M17 3v5M3 11h18M7 15h2M13 15h4',
  people:
    'M15 8a3 3 0 1 1-6 0 3 3 0 0 1 6 0ZM5 21v-3a7 7 0 0 1 14 0v3M18 4a3 3 0 0 1 0 6M21 21v-3a7 7 0 0 0-3-5',
  building: 'M5 21V3h14v18M2 21h20M9 7h1M14 7h1M9 11h1M14 11h1M10 21v-6h4v6',
  briefcase: 'M3 7h18v13H3ZM8 7V3h8v4M3 12a22 22 0 0 0 18 0M10 13h4',
  external: 'M14 3h7v7M21 3 10 14M10 3H3v18h18v-7',
  note: 'M4 4h16v12H9l-5 4ZM8 8h8M8 12h5',
  photo: 'M3 6h4l2-3h6l2 3h4v15H3ZM16 13a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z',
  audio: 'M9 5a3 3 0 0 1 6 0v7a3 3 0 0 1-6 0ZM5 10v2a7 7 0 0 0 14 0v-2M12 19v3M8 22h8',
  more: 'M12 5h.01M12 12h.01M12 19h.01',
  check: 'm5 12 4 4L19 6',
} as const;

@Component({
  selector: 'app-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.7"
    stroke-linecap="round"
    stroke-linejoin="round"
    aria-hidden="true"
    focusable="false"
  >
    <path [attr.d]="paths[name()]" />
  </svg>`,
  styles: `
    :host {
      display: inline-flex;
      width: 20px;
      height: 20px;
      flex: 0 0 auto;
      vertical-align: middle;
    }
    svg {
      width: 100%;
      height: 100%;
    }
  `,
})
export class Icon {
  readonly name = input.required<keyof typeof paths>();
  protected readonly paths = paths;
}
