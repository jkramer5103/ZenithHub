// The blocks of the flow editor (components/flows). The manager checks and runs them: manager/.../flows/FlowSpec.java.
import type { Capability, Flow, FlowCondition, FlowStep, FlowTrigger } from './types';

export type CategoryId = 'control' | 'move' | 'hands' | 'chat' | 'data' | 'connection';

export interface Category {
	id: CategoryId;
	label: string;
	/** block fill; white text on it stays readable */
	fill: string;
	/** the darker rail of C-blocks and the picker chips */
	rail: string;
}

export const HAT = { fill: 'bg-yellow-700', rail: 'bg-yellow-800' };
export const categories: Category[] = [
	{ id: 'control', label: 'Control', fill: 'bg-orange-700', rail: 'bg-orange-800' },
	{ id: 'move', label: 'Movement', fill: 'bg-blue-600', rail: 'bg-blue-700' },
	{ id: 'hands', label: 'Hands', fill: 'bg-teal-700', rail: 'bg-teal-800' },
	{ id: 'chat', label: 'Chat & alerts', fill: 'bg-violet-600', rail: 'bg-violet-700' },
	{ id: 'data', label: 'Variables & signals', fill: 'bg-lime-700', rail: 'bg-lime-800' },
	{ id: 'connection', label: 'Connection', fill: 'bg-rose-700', rail: 'bg-rose-800' }
];

export interface BlockDef {
	key: string;
	cat: CategoryId;
	/** how it reads in the picker */
	label: string;
	needs: Capability;
	make: () => Omit<FlowStep, 'id'>;
}

const act =
	(action: string, extra: Record<string, unknown> = {}) =>
	() => ({ type: 'action' as const, run: { action, ...extra } });

export const blocks: BlockDef[] = [
	{ key: 'wait', cat: 'control', label: 'wait … seconds', needs: 'CONTROL', make: () => ({ type: 'wait', seconds: 5 }) },
	{
		key: 'waitFor',
		cat: 'control',
		label: 'wait for something to happen',
		needs: 'CONTROL',
		make: () => ({ type: 'waitFor', event: { kind: 'whisper', contains: '', from: '' }, timeout: 60 })
	},
	{
		key: 'waitUntil',
		cat: 'control',
		label: 'wait until … is true',
		needs: 'CONTROL',
		make: () => ({ type: 'waitUntil', cond: { kind: 'online', not: false }, timeout: 300 })
	},
	{
		key: 'if',
		cat: 'control',
		label: 'if … then … else',
		needs: 'CONTROL',
		make: () => ({ type: 'if', cond: { kind: 'health', below: 10, not: false }, then: [], else: [] })
	},
	{ key: 'repeat', cat: 'control', label: 'repeat … times', needs: 'CONTROL', make: () => ({ type: 'repeat', times: 3, steps: [] }) },
	{
		key: 'repeatUntil',
		cat: 'control',
		label: 'repeat until …',
		needs: 'CONTROL',
		make: () => ({ type: 'repeatUntil', cond: { kind: 'health', below: 10, not: false }, steps: [] })
	},
	{ key: 'forever', cat: 'control', label: 'repeat forever', needs: 'CONTROL', make: () => ({ type: 'forever', steps: [] }) },
	{ key: 'stop', cat: 'control', label: 'stop this flow', needs: 'CONTROL', make: () => ({ type: 'stop' }) },

	{ key: 'goto', cat: 'move', label: 'walk to x y z', needs: 'PLAY', make: act('goto', { x: 0, y: null, z: 0 }) },
	{ key: 'gotoWaypoint', cat: 'move', label: 'walk to waypoint', needs: 'PLAY', make: act('gotoWaypoint', { waypoint: '' }) },
	{ key: 'follow', cat: 'move', label: 'follow a player', needs: 'PLAY', make: act('follow', { player: '' }) },
	{ key: 'stop-moving', cat: 'move', label: 'stop moving', needs: 'PLAY', make: act('stop') },
	{ key: 'afkEnter', cat: 'move', label: 'go into the AFK zone', needs: 'PLAY', make: act('afkEnter') },
	{ key: 'afkLeave', cat: 'move', label: 'leave the AFK zone', needs: 'PLAY', make: act('afkLeave') },

	{ key: 'respawn', cat: 'hands', label: 'respawn', needs: 'PLAY', make: act('respawn') },
	{ key: 'clickRight', cat: 'hands', label: 'right click once', needs: 'PLAY', make: act('click', { button: 'right' }) },
	{ key: 'useItem', cat: 'hands', label: 'use the held item', needs: 'PLAY', make: act('click', { button: 'right', mode: 'useItem' }) },
	{ key: 'holdRight', cat: 'hands', label: 'keep right clicking', needs: 'PLAY', make: act('click', { button: 'right', mode: 'hold' }) },
	{ key: 'holdLeft', cat: 'hands', label: 'keep left clicking', needs: 'PLAY', make: act('click', { button: 'left', mode: 'hold' }) },
	{ key: 'clickStop', cat: 'hands', label: 'stop clicking', needs: 'PLAY', make: act('clickStop') },
	{ key: 'loadPearl', cat: 'hands', label: 'load a stasis pearl', needs: 'PEARLS', make: act('loadPearl', { pearl: '' }) },

	{ key: 'say', cat: 'chat', label: 'say …', needs: 'CHAT', make: () => ({ type: 'say', text: '' }) },
	{ key: 'whisper', cat: 'chat', label: 'whisper to a player', needs: 'CHAT', make: () => ({ type: 'whisper', player: '{player}', text: '' }) },
	{ key: 'notify', cat: 'chat', label: 'notify me …', needs: 'CONTROL', make: () => ({ type: 'notify', text: '' }) },

	{ key: 'set', cat: 'data', label: 'set a variable', needs: 'CONTROL', make: () => ({ type: 'set', name: 'last', value: '{player}' }) },
	{ key: 'change', cat: 'data', label: 'change a counter by …', needs: 'CONTROL', make: () => ({ type: 'change', name: 'count', by: 1 }) },
	{ key: 'broadcast', cat: 'data', label: 'broadcast to other flows', needs: 'CONTROL', make: () => ({ type: 'broadcast', name: 'go' }) },
	{ key: 'log', cat: 'data', label: 'write to the run log', needs: 'CONTROL', make: () => ({ type: 'log', text: '' }) },

	{ key: 'connect', cat: 'connection', label: 'connect', needs: 'CONTROL', make: () => ({ type: 'connect' }) },
	{ key: 'disconnect', cat: 'connection', label: 'disconnect', needs: 'CONTROL', make: () => ({ type: 'disconnect' }) },
	{ key: 'requeue', cat: 'connection', label: 'requeue (2b2t)', needs: 'CONTROL', make: act('requeue') },
	{ key: 'kick', cat: 'connection', label: 'kick everyone off the proxy', needs: 'CONTROL', make: act('kick') }
];

/** Which block a step is, for its colour and picker entry. */
export function keyOf(step: FlowStep): string {
	if (step.type !== 'action') return step.type;
	const run = step.run ?? { action: '' };
	if (run.action === 'click')
		return run.mode === 'useItem' ? 'useItem' : run.mode === 'hold' ? (run.button === 'left' ? 'holdLeft' : 'holdRight') : 'clickRight';
	if (run.action === 'stop') return 'stop-moving';
	return run.action;
}

export function categoryOf(step: FlowStep): Category {
	const def = blocks.find((b) => b.key === keyOf(step));
	return categories.find((c) => c.id === def?.cat) ?? categories[0];
}

// --- triggers ---

export interface TriggerDef {
	kind: string;
	label: string;
	group: string;
	/** values the blocks below can use, as {name} */
	gives: string[];
	/** can "wait until" wait for it */
	waitable: boolean;
	defaults?: Record<string, unknown>;
}

const zone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';

export const triggers: TriggerDef[] = [
	{ kind: 'manual', label: 'I click Run', group: 'Start', gives: [], waitable: false },
	{ kind: 'every', label: 'a timer goes off every', group: 'Start', gives: [], waitable: false, defaults: { minutes: 30 } },
	{ kind: 'daily', label: 'it’s a time of day', group: 'Start', gives: [], waitable: false, defaults: { at: '08:00', days: '1234567' } },
	{ kind: 'receive', label: 'another flow broadcasts', group: 'Start', gives: ['from'], waitable: true, defaults: { name: 'go' } },
	{ kind: 'joined', label: 'the bot gets into the game', group: 'Connection', gives: [], waitable: true },
	{ kind: 'left', label: 'the bot leaves the game', group: 'Connection', gives: ['message'], waitable: true, defaults: { reason: '' } },
	{ kind: 'crashed', label: 'the bot crashes', group: 'Connection', gives: ['message'], waitable: false },
	{ kind: 'queueBelow', label: 'the queue drops below', group: 'Connection', gives: ['position'], waitable: true, defaults: { position: 20 } },
	{ kind: 'queueComplete', label: 'the queue is done', group: 'Connection', gives: [], waitable: true },
	{ kind: 'serverRestarting', label: 'the server restarts soon', group: 'Connection', gives: ['message'], waitable: false },
	{ kind: 'chat', label: 'someone chats', group: 'Chat', gives: ['player', 'message'], waitable: true, defaults: { contains: '', from: '' } },
	{ kind: 'whisper', label: 'someone whispers', group: 'Chat', gives: ['player', 'message'], waitable: true, defaults: { contains: '', from: '' } },
	{ kind: 'player', label: 'someone comes into view', group: 'Players', gives: ['player', 'x', 'y', 'z'], waitable: true, defaults: { who: 'stranger' } },
	{ kind: 'playerLeft', label: 'someone leaves view', group: 'Players', gives: ['player'], waitable: true, defaults: { who: 'anyone' } },
	{ kind: 'controlled', label: 'someone starts or stops playing', group: 'Players', gives: ['player'], waitable: true, defaults: { on: 'start' } },
	{ kind: 'attacked', label: 'a player attacks the bot', group: 'Danger', gives: ['player'], waitable: true },
	{ kind: 'health', label: 'health drops below', group: 'Danger', gives: [], waitable: true, defaults: { below: 8 } },
	{ kind: 'food', label: 'food drops below', group: 'Danger', gives: [], waitable: true, defaults: { below: 6 } },
	{ kind: 'totem', label: 'a totem pops', group: 'Danger', gives: ['totemsLeft'], waitable: true },
	{ kind: 'noTotems', label: 'the last totem is gone', group: 'Danger', gives: [], waitable: false },
	{ kind: 'death', label: 'the bot dies', group: 'Danger', gives: ['x', 'y', 'z'], waitable: true },
	{ kind: 'afk', label: 'the AFK zone changes', group: 'AFK zone', gives: [], waitable: true, defaults: { on: 'earningStopped' } }
];

/** values every message can use, whatever the trigger */
export const always = ['health', 'food', 'x', 'y', 'z', 'dimension', 'server', 'time', 'date'];

export function triggerDef(kind: string) {
	return triggers.find((t) => t.kind === kind) ?? triggers[0];
}

export function makeTrigger(kind: string): FlowTrigger {
	const t: FlowTrigger = { kind, ...structuredClone(triggerDef(kind).defaults ?? {}) };
	if (kind === 'daily') t.zone = zone();
	return t;
}

const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
export const weekdays = DAYS.map((label, i) => ({ label, value: String(i + 1) }));

function daysText(days: string) {
	if (!days || days.length === 7) return 'every day';
	if (days === '12345') return 'on weekdays';
	if (days === '67') return 'on weekends';
	return 'on ' + [...days].map((d) => DAYS[Number(d) - 1]).join(', ');
}

const afkText: Record<string, string> = {
	earningStopped: 'the bot stops earning',
	earningStarted: 'the bot starts earning',
	leftZone: 'the bot leaves the AFK zone',
	enteredZone: 'the bot enters the AFK zone'
};
export const afkOptions = Object.entries(afkText).map(([value, label]) => ({ value, label }));

/** A trigger in words, for the flow list. */
export function triggerText(t: FlowTrigger): string {
	switch (t.kind) {
		case 'manual':
			return 'When you click Run';
		case 'every':
			return `Every ${t.minutes} min`;
		case 'chat':
		case 'whisper': {
			const what = t.kind === 'chat' ? 'chat' : 'a whisper';
			const from = t.from ? ` from ${t.from}` : '';
			return t.contains ? `When ${what}${from} says “${t.contains}”` : `On every ${t.kind === 'chat' ? 'chat message' : 'whisper'}${from}`;
		}
		case 'player':
			return t.who === 'anyone' ? 'When a player comes into view' : t.who === 'stranger' ? 'When a stranger comes into view' : `When ${t.who} comes into view`;
		case 'playerLeft':
			return t.who === 'anyone' ? 'When a player leaves view' : t.who === 'stranger' ? 'When a stranger leaves view' : `When ${t.who} leaves view`;
		case 'health':
			return `When health drops below ${Number(t.below) / 2} ♥`;
		case 'food':
			return `When food drops below ${Number(t.below) / 2}`;
		case 'daily':
			return `At ${t.at} ${daysText(String(t.days ?? ''))}`;
		case 'receive':
			return `When “${t.name}” is broadcast`;
		case 'queueBelow':
			return `When the queue drops below ${t.position}`;
		case 'left':
			return t.reason ? `When the bot leaves with “${t.reason}”` : 'When the bot leaves the game';
		case 'controlled':
			return t.on === 'start' ? 'When someone starts playing' : 'When nobody is playing anymore';
		case 'afk':
			return 'When ' + (afkText[String(t.on)] ?? 'the AFK zone changes');
		default:
			return 'When ' + triggerDef(t.kind).label;
	}
}

export interface ConditionDef {
	kind: string;
	label: string;
	group: string;
	defaults?: () => Record<string, unknown>;
}

export const conditions: ConditionDef[] = [
	{ kind: 'health', label: 'health is below', group: 'Bot', defaults: () => ({ below: 10 }) },
	{ kind: 'food', label: 'food is below', group: 'Bot', defaults: () => ({ below: 10 }) },
	{ kind: 'online', label: 'the bot is in the game', group: 'Bot' },
	{ kind: 'inQueue', label: 'the bot is in the queue', group: 'Bot' },
	{ kind: 'playing', label: 'someone is playing on the account', group: 'Bot' },
	{ kind: 'hasItem', label: 'the inventory has', group: 'Bot', defaults: () => ({ item: 'totem_of_undying', count: 1 }) },
	{ kind: 'near', label: 'the bot is near', group: 'Where', defaults: () => ({ x: 0, z: 0, radius: 32 }) },
	{ kind: 'dimension', label: 'the bot is in', group: 'Where', defaults: () => ({ is: 'the_nether' }) },
	{ kind: 'inAfkZone', label: 'the bot is in the AFK zone', group: 'Where' },
	{ kind: 'earning', label: 'the bot earns in the AFK zone', group: 'Where' },
	{ kind: 'text', label: 'the message contains', group: 'Event', defaults: () => ({ contains: '' }) },
	{ kind: 'friend', label: 'the player is a friend', group: 'Event' },
	{ kind: 'var', label: 'the variable', group: 'Other', defaults: () => ({ name: 'count', op: '>', value: '0' }) },
	{ kind: 'time', label: 'the time is between', group: 'Other', defaults: () => ({ from: '22:00', to: '06:00', zone: zone() }) },
	{ kind: 'chance', label: 'a dice roll, with chance', group: 'Other', defaults: () => ({ percent: 50 }) }
];

export function makeCondition(kind: string, not = false): FlowCondition {
	return { kind, not, ...(conditions.find((c) => c.kind === kind)?.defaults?.() ?? {}) };
}

/** what blocks with a condition read like, in the picker and the list */
export const dimensions = [
	{ value: 'overworld', label: 'the Overworld' },
	{ value: 'the_nether', label: 'the Nether' },
	{ value: 'the_end', label: 'the End' }
];

// --- helpers ---

export function newId() {
	return Math.random().toString(36).slice(2, 10);
}

export function makeStep(def: BlockDef): FlowStep {
	return { id: newId(), ...structuredClone(def.make()) } as FlowStep;
}

/** A copy with fresh ids, for duplicating. */
export function cloneStep(step: FlowStep): FlowStep {
	const copy = JSON.parse(JSON.stringify(step)) as FlowStep;
	const renew = (s: FlowStep) => {
		s.id = newId();
		[s.then, s.else, s.steps].forEach((list) => list?.forEach(renew));
	};
	renew(copy);
	return copy;
}

const children = (s: FlowStep) => [...(s.then ?? []), ...(s.else ?? []), ...(s.steps ?? [])];

export function countSteps(steps: FlowStep[]): number {
	return steps.reduce((n, s) => n + 1 + countSteps(children(s)), 0);
}

/** the variables a flow sets, for the value chips */
export function variablesIn(steps: FlowStep[], into = new Set<string>()): string[] {
	for (const s of steps) {
		if ((s.type === 'set' || s.type === 'change') && s.name) into.add(s.name);
		variablesIn(children(s), into);
	}
	return [...into].sort();
}

/** whether target sits inside step (or is it), so a block can't be dropped into itself */
export function contains(step: FlowStep, list: FlowStep[]): boolean {
	return [step.then, step.else, step.steps].some((l) => l === list || l?.some((c) => contains(c, list)));
}

export function findStep(steps: FlowStep[], id: string): FlowStep | undefined {
	for (const s of steps) {
		if (s.id === id) return s;
		const inner = findStep(children(s), id);
		if (inner) return inner;
	}
	return undefined;
}

// --- share codes: a flow as text, to paste into another instance or send to a friend ---

const PREFIX = 'ZHF1:';

export function shareCode(flow: Flow): string {
	const { name, enabled, cooldown, mode, trigger, steps } = flow;
	const bytes = new TextEncoder().encode(JSON.stringify({ name, enabled, cooldown, mode, trigger, steps }));
	let bin = '';
	bytes.forEach((b) => (bin += String.fromCharCode(b)));
	return PREFIX + btoa(bin);
}

/** A flow from a share code, with fresh ids; throws on anything else. */
export function fromShareCode(code: string): Flow {
	const text = code.trim();
	if (!text.startsWith(PREFIX)) throw new Error('That isn’t a flow code. They start with ' + PREFIX);
	let parsed: Partial<Flow>;
	try {
		const bin = atob(text.slice(PREFIX.length));
		parsed = JSON.parse(new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0))));
	} catch {
		throw new Error('The code is damaged. Copy it again.');
	}
	if (!parsed || typeof parsed.name !== 'string' || !parsed.trigger || !Array.isArray(parsed.steps)) throw new Error('The code has no flow in it');
	return {
		id: newId(),
		name: parsed.name.slice(0, 60),
		enabled: false,
		cooldown: Number(parsed.cooldown ?? 10),
		mode: parsed.mode === 'restart' ? 'restart' : 'ignore',
		trigger: parsed.trigger,
		steps: parsed.steps.map(cloneStep)
	};
}

/** A copy of a flow under a new id, switched off so it doesn't fire twice. */
export function copyFlow(flow: Flow, name = flow.name + ' (copy)'): Flow {
	return {
		id: newId(),
		name: name.slice(0, 60),
		enabled: false,
		cooldown: flow.cooldown,
		mode: flow.mode ?? 'ignore',
		trigger: JSON.parse(JSON.stringify(flow.trigger)),
		steps: flow.steps.map(cloneStep)
	};
}

// --- examples, so nobody starts from an empty page ---

type Example = Omit<Flow, 'id'> & { blurb: string };
const s = (step: Omit<FlowStep, 'id'>): FlowStep => ({ id: newId(), ...step }) as FlowStep;

export function examples(): Example[] {
	return [
		{
			name: 'Warn me about strangers',
			blurb: 'A notification when someone who isn’t a friend comes near.',
			enabled: true,
			cooldown: 60,
			trigger: { kind: 'player', who: 'stranger' },
			steps: [s({ type: 'notify', text: '{player} came into view at {x}, {z}' })]
		},
		{
			name: 'Leave at low health',
			blurb: 'Disconnects before the bot can die.',
			enabled: true,
			cooldown: 30,
			trigger: { kind: 'health', below: 8 },
			steps: [
				s({
					type: 'if',
					cond: { kind: 'playing', not: true },
					then: [s({ type: 'notify', text: 'Health {health}, leaving' }), s({ type: 'disconnect' })],
					else: []
				})
			]
		},
		{
			name: 'Answer whispers',
			blurb: 'Replies when someone whispers “where”.',
			enabled: true,
			cooldown: 10,
			trigger: { kind: 'whisper', contains: 'where', from: '' },
			steps: [s({ type: 'wait', seconds: 2 }), s({ type: 'whisper', player: '{player}', text: 'AFK right now, back later' })]
		},
		{
			name: 'Count deaths',
			blurb: 'Keeps a death counter and tells you where it happened.',
			enabled: true,
			cooldown: 3,
			trigger: { kind: 'death' },
			steps: [s({ type: 'change', name: 'deaths', by: 1 }), s({ type: 'notify', text: 'Death #{deaths} at {x} {y} {z} in {dimension}' })]
		},
		{
			name: 'Connect every morning',
			blurb: 'Gets in the game at 8:00 on weekdays, and tells you once it’s through the queue.',
			enabled: true,
			cooldown: 10,
			trigger: { kind: 'daily', at: '08:00', days: '12345', zone: zone() },
			steps: [
				s({ type: 'if', cond: { kind: 'online', not: true }, then: [s({ type: 'connect' })], else: [] }),
				s({ type: 'waitUntil', cond: { kind: 'online', not: false }, timeout: 3600 }),
				s({ type: 'notify', text: 'In the game on {server} since {time}' })
			]
		},
		{
			name: 'Queue almost done',
			blurb: 'A heads-up when the 2b2t queue drops below 20.',
			enabled: true,
			cooldown: 300,
			trigger: { kind: 'queueBelow', position: 20 },
			steps: [s({ type: 'notify', text: 'Queue position {position}, get ready' })]
		},
		{
			name: 'Back into the AFK zone',
			blurb: 'Walks back into the AFK zone when the bot stops earning and nobody is playing.',
			enabled: true,
			cooldown: 60,
			trigger: { kind: 'afk', on: 'earningStopped' },
			steps: [
				s({ type: 'wait', seconds: 10 }),
				s({
					type: 'if',
					cond: { kind: 'playing', not: true },
					then: [s({ type: 'action', run: { action: 'afkEnter' } }), s({ type: 'log', text: 'Walked back into the zone' })],
					else: []
				})
			]
		},
		{
			name: 'Low on totems',
			blurb: 'After a totem pops: a warning once fewer than 2 are left.',
			enabled: true,
			cooldown: 10,
			trigger: { kind: 'totem' },
			steps: [
				s({ type: 'wait', seconds: 1 }),
				s({
					type: 'if',
					cond: { kind: 'hasItem', item: 'totem_of_undying', count: 2, not: true },
					then: [s({ type: 'notify', text: 'Only {totemsLeft} totems left' })],
					else: []
				})
			]
		},
		{
			name: 'Respawn and walk home',
			blurb: 'After a death: respawn, then walk to the “home” waypoint.',
			enabled: true,
			cooldown: 30,
			trigger: { kind: 'death' },
			steps: [
				s({ type: 'wait', seconds: 3 }),
				s({ type: 'action', run: { action: 'respawn' } }),
				s({ type: 'wait', seconds: 2 }),
				s({ type: 'action', run: { action: 'gotoWaypoint', waypoint: 'home' } })
			]
		}
	];
}
