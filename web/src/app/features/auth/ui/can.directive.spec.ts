import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthSession } from '../data-access/auth-session';
import { CanDirective } from './can.directive';
@Component({
  template: `<button *appCan="'customers.create'">Create customer</button>`,
  imports: [CanDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class Host {}
it('shows only an explicitly granted action and removes it when the session ends', () => {
  const authenticated = signal(true);
  const permissions = signal<string[]>([]);
  TestBed.configureTestingModule({
    providers: [
      {
        provide: AuthSession,
        useValue: {
          hasPermission: (permission: string) =>
            authenticated() && permissions().includes(permission),
        },
      },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  expect(fixture.nativeElement.querySelector('button')).toBeNull();
  permissions.set(['customers.create']);
  fixture.detectChanges();
  expect(fixture.nativeElement.querySelector('button')?.textContent).toBe('Create customer');
  authenticated.set(false);
  fixture.detectChanges();
  expect(fixture.nativeElement.querySelector('button')).toBeNull();
});
