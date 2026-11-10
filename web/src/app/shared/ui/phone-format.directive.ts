import { Directive, ElementRef, HostListener, inject } from '@angular/core';
import { NgControl } from '@angular/forms';
import { AsYouType } from 'libphonenumber-js';

@Directive({ selector: 'input[appPhoneFormat]', standalone: true })
export class PhoneFormatDirective {
  private readonly element = inject<ElementRef<HTMLInputElement>>(ElementRef);
  private readonly control = inject(NgControl, { self: true, optional: true });

  @HostListener('input')
  protected formatPhone(): void {
    const input = this.element.nativeElement;
    const original = input.value;
    if (!original || /[^+\d\s().-]/.test(original)) return;

    const caret = input.selectionStart ?? original.length;
    const digitsBeforeCaret = (original.slice(0, caret).match(/\d/g) ?? []).length;
    const formatted = new AsYouType('CA').input(original);
    if (formatted === original) return;

    input.value = formatted;
    this.control?.control?.setValue(formatted);
    const nextCaret = this.positionAfterDigits(formatted, digitsBeforeCaret);
    queueMicrotask(() => input.setSelectionRange(nextCaret, nextCaret));
  }

  private positionAfterDigits(value: string, count: number): number {
    if (count === 0) return value.startsWith('+') ? 1 : 0;
    let seen = 0;
    for (let index = 0; index < value.length; index++) {
      if (/\d/.test(value.charAt(index))) seen++;
      if (seen === count) return index + 1;
    }
    return value.length;
  }
}
