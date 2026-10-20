import type { ScheduleResponse, ScheduleVisit } from '@servora/shared-types';
import { visit } from './workspace';
export const crew = [
  { membershipId: 'alex', name: 'Alex Tremblay' },
  { membershipId: 'sarah', name: 'Sarah Chen' },
  { membershipId: 'marc', name: 'Marc Dubois' },
  { membershipId: 'jo', name: 'Jo Martin' },
];
function item(
  id: string,
  title: string,
  start: string,
  end: string,
  members: number[],
  purpose = 'WORK',
  status = 'SCHEDULED',
): ScheduleVisit {
  return {
    ...visit,
    overdue: id === '1',
    visitId: id,
    jobId: 'job-one',
    jobNumber: 1042 + Number(id),
    jobTitle: title,
    scheduledStart: `2026-10-20T${start}:00Z`,
    scheduledEnd: `2026-10-20T${end}:00Z`,
    purposeCode: purpose,
    visitStatus: status,
    technicians: members.map((i, index) => ({
      ...crew[i]!,
      roleCode: index === 0 ? 'LEAD' : 'TECHNICIAN',
    })),
  };
}
export const dispatch: ScheduleResponse = {
  generatedAt: '2026-10-20T14:00:00Z',
  day: {
    localDate: '2026-10-20',
    timeZone: 'America/Toronto',
    start: '2026-10-20T04:00:00Z',
    end: '2026-10-21T04:00:00Z',
  },
  scope: { kind: 'ORGANIZATION', membershipId: 'manager' },
  technicians: crew,
  visits: [
    item('1', 'Furnace repair', '12:00', '13:30', [0, 1]),
    item('2', 'Boiler assessment', '13:00', '14:00', [0], 'ASSESSMENT'),
    item('3', 'Heat pump inspection', '14:30', '16:00', [1]),
    item('4', 'Preventive maintenance', '12:30', '14:30', [2], 'WORK', 'COMPLETED'),
    item('5', 'Ventilation service', '17:00', '18:00', [0]),
    item('6', 'Thermostat replacement', '16:00', '17:00', [2]),
  ],
  unassigned: {
    total: 3,
    items: [
      { ...item('7', 'Water heater inspection', '15:00', '16:00', []), overdue: false },
      {
        ...item('8', 'Boiler service', '15:00', '16:00', []),
        scheduledStart: null,
        scheduledEnd: null,
        overdue: false,
      },
    ],
  },
};
