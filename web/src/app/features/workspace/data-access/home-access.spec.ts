import { homeView } from './home-access';
it('chooses a deterministic Home and permits mixed users to switch', () => {
  expect(homeView(true, true, null)).toBe('office');
  expect(homeView(true, true, 'field')).toBe('field');
  expect(homeView(true, true, 'unknown')).toBe('office');
});
it('never selects a projection the user cannot read', () => {
  expect(homeView(false, true, 'office')).toBe('field');
  expect(homeView(true, false, 'field')).toBe('office');
  expect(homeView(false, false, 'office')).toBeNull();
});
