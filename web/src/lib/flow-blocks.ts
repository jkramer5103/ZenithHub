// The blocks of the flow editor (components/flows). The manager checks and runs them: manager/.../flows/FlowSpec.java.
import type { Capability, Flow, FlowCondition, FlowStep, FlowTrigger } from './types';

export type CategoryId = 'control' | 'move' | 'world' | 'hands' | 'inventory' | 'chat' | 'data' | 'connection' | 'bot';

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
	{ id: 'world', label: 'Mining & building', fill: 'bg-amber-700', rail: 'bg-amber-800' },
	{ id: 'hands', label: 'Hands', fill: 'bg-teal-700', rail: 'bg-teal-800' },
	{ id: 'inventory', label: 'Inventory', fill: 'bg-cyan-700', rail: 'bg-cyan-800' },
	{ id: 'chat', label: 'Chat & alerts', fill: 'bg-violet-600', rail: 'bg-violet-700' },
	{ id: 'data', label: 'Variables & signals', fill: 'bg-lime-700', rail: 'bg-lime-800' },
	{ id: 'connection', label: 'Connection', fill: 'bg-rose-700', rail: 'bg-rose-800' },
	{ id: 'bot', label: 'Modules & waypoints', fill: 'bg-slate-600', rail: 'bg-slate-700' }
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
	(action: string, extra: Record<string, unknown> = {}, step: Partial<FlowStep> = {}) =>
	() => ({ type: 'action' as const, run: { action, ...extra }, ...step });

/** pathfinder actions: wait until they're done (with a time limit), or let them run for a while */
export const PATHING = new Set([
	'goto',
	'gotoWaypoint',
	'elytra',
	'thisway',
	'getTo',
	'mine',
	'pickup',
	'near',
	'clickBlock',
	'clickEntity',
	'breakBlock',
	'placeBlock',
	'clearArea',
	'follow'
]);
const done = (timeout = 600): Partial<FlowStep> => ({ wait: 'done', timeout });

/** the modules a flow can switch on and off (manager/.../instances/Actions.MODULES) */
export const modules = [
	{ value: 'autoEat', label: 'Auto eat' },
	{ value: 'antiAFK', label: 'Anti AFK' },
	{ value: 'autoReconnect', label: 'Auto reconnect' },
	{ value: 'autoRespawn', label: 'Auto respawn' },
	{ value: 'visualRange', label: 'Visual range alerts' },
	{ value: 'autoDisconnect', label: 'Auto disconnect' },
	{ value: 'killAura', label: 'Kill aura' },
	{ value: 'autoTotem', label: 'Auto totem' },
	{ value: 'autoArmor', label: 'Auto armour' },
	{ value: 'autoFish', label: 'Auto fish' },
	{ value: 'autoMend', label: 'Auto mend' },
	{ value: 'spammer', label: 'Spammer' },
	{ value: 'autoReply', label: 'Auto reply' },
	{ value: 'autoDrop', label: 'Auto drop' },
	{ value: 'spook', label: 'Spook' }
];

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
	{ key: 'try', cat: 'control', label: 'try … if it fails …', needs: 'CONTROL', make: () => ({ type: 'try', steps: [], catch: [] }) },
	{ key: 'forEach', cat: 'control', label: 'for each nearby player …', needs: 'CONTROL', make: () => ({ type: 'forEach', list: 'nearbyPlayers', steps: [] }) },
	{ key: 'runFlow', cat: 'control', label: 'run another flow', needs: 'CONTROL', make: () => ({ type: 'runFlow', flow: '', wait: true }) },

	{ key: 'goto', cat: 'move', label: 'walk to x y z', needs: 'PLAY', make: act('goto', { x: 0, y: null, z: 0 }, done()) },
	{ key: 'gotoWaypoint', cat: 'move', label: 'walk to waypoint', needs: 'PLAY', make: act('gotoWaypoint', { waypoint: '' }, done()) },
	{ key: 'near', cat: 'move', label: 'walk near x y z', needs: 'PLAY', make: act('near', { x: 0, y: 64, z: 0, range: 5 }, done()) },
	{ key: 'getTo', cat: 'move', label: 'walk to the nearest block of a kind', needs: 'PLAY', make: act('getTo', { block: 'crafting_table' }, done()) },
	{ key: 'thisway', cat: 'move', label: 'walk … blocks straight ahead', needs: 'PLAY', make: act('thisway', { blocks: 100 }, done()) },
	{ key: 'elytra', cat: 'move', label: 'fly with the elytra to x z', needs: 'PLAY', make: act('elytra', { x: 0, y: null, z: 0 }, done(3600)) },
	{ key: 'follow', cat: 'move', label: 'follow a player', needs: 'PLAY', make: act('follow', { player: '' }) },
	{
		key: 'lookAt',
		cat: 'move',
		label: 'look at a player, mob or block',
		needs: 'PLAY',
		make: () => ({ type: 'lookAt', target: 'entity', what: 'anyone', radius: 16 })
	},
	{ key: 'rotate', cat: 'move', label: 'turn to a direction', needs: 'PLAY', make: act('rotate', { yaw: 0, pitch: 0 }) },
	{ key: 'stop-moving', cat: 'move', label: 'stop moving', needs: 'PLAY', make: act('stop') },
	{ key: 'afkEnter', cat: 'move', label: 'go into the AFK zone', needs: 'PLAY', make: act('afkEnter') },
	{ key: 'afkLeave', cat: 'move', label: 'leave the AFK zone', needs: 'PLAY', make: act('afkLeave') },

	{ key: 'mine', cat: 'world', label: 'mine a block type', needs: 'PLAY', make: act('mine', { block: 'diamond_ore' }, { wait: 'for', timeout: 300 }) },
	{ key: 'breakBlock', cat: 'world', label: 'break the block at x y z', needs: 'PLAY', make: act('breakBlock', { x: 0, y: 64, z: 0 }, done()) },
	{
		key: 'placeBlock',
		cat: 'world',
		label: 'place a block at x y z',
		needs: 'PLAY',
		make: act('placeBlock', { x: 0, y: 64, z: 0, item: 'cobblestone' }, done())
	},
	{
		key: 'clearArea',
		cat: 'world',
		label: 'clear an area',
		needs: 'PLAY',
		make: act('clearArea', { from: { x: 0, y: 64, z: 0 }, to: { x: 5, y: 66, z: 5 } }, done(3600))
	},
	{ key: 'pickup', cat: 'world', label: 'pick up items nearby', needs: 'PLAY', make: act('pickup', { item: null }, done(120)) },
	{
		key: 'clickBlock',
		cat: 'world',
		label: 'walk to a block and click it',
		needs: 'PLAY',
		make: act('clickBlock', { button: 'right', x: 0, y: 64, z: 0 }, done())
	},
	{
		key: 'clickEntity',
		cat: 'world',
		label: 'walk to a mob and click it',
		needs: 'PLAY',
		make: act('clickEntity', { button: 'right', entity: 'villager' }, done())
	},

	{ key: 'respawn', cat: 'hands', label: 'respawn', needs: 'PLAY', make: act('respawn') },
	{ key: 'clickRight', cat: 'hands', label: 'right click once', needs: 'PLAY', make: act('click', { button: 'right' }) },
	{ key: 'useItem', cat: 'hands', label: 'use the held item', needs: 'PLAY', make: act('click', { button: 'right', mode: 'useItem' }) },
	{ key: 'holdRight', cat: 'hands', label: 'keep right clicking', needs: 'PLAY', make: act('click', { button: 'right', mode: 'hold' }) },
	{ key: 'holdLeft', cat: 'hands', label: 'keep left clicking', needs: 'PLAY', make: act('click', { button: 'left', mode: 'hold' }) },
	{ key: 'clickStop', cat: 'hands', label: 'stop clicking', needs: 'PLAY', make: act('clickStop') },
	{ key: 'loadPearl', cat: 'hands', label: 'load a stasis pearl', needs: 'PEARLS', make: act('loadPearl', { pearl: '' }) },

	{ key: 'holdItem', cat: 'inventory', label: 'hold an item', needs: 'PLAY', make: () => ({ type: 'holdItem', item: 'totem_of_undying' }) },
	{ key: 'dropItem', cat: 'inventory', label: 'drop an item', needs: 'PLAY', make: () => ({ type: 'dropItem', item: 'cobblestone', all: true }) },
	{ key: 'hold', cat: 'inventory', label: 'select a hotbar slot', needs: 'PLAY', make: act('hold', { slot: 36 }) },
	{ key: 'swap', cat: 'inventory', label: 'swap two slots', needs: 'PLAY', make: act('swap', { from: 9, to: 36 }) },
	{ key: 'drop', cat: 'inventory', label: 'drop what’s in a slot', needs: 'PLAY', make: act('drop', { slot: 9, stack: true }) },
	{ key: 'clickSlot', cat: 'inventory', label: 'click a slot', needs: 'PLAY', make: act('clickSlot', { button: 'left', slot: 0 }) },
	{ key: 'withdraw', cat: 'inventory', label: 'take everything from the open chest', needs: 'PLAY', make: act('withdraw') },
	{ key: 'deposit', cat: 'inventory', label: 'put everything into the open chest', needs: 'PLAY', make: act('deposit') },
	{ key: 'closeContainer', cat: 'inventory', label: 'close the open chest', needs: 'PLAY', make: act('closeContainer') },

	{ key: 'say', cat: 'chat', label: 'say …', needs: 'CHAT', make: () => ({ type: 'say', text: '' }) },
	{ key: 'whisper', cat: 'chat', label: 'whisper to a player', needs: 'CHAT', make: () => ({ type: 'whisper', player: '{player}', text: '' }) },
	{ key: 'notify', cat: 'chat', label: 'notify me …', needs: 'CONTROL', make: () => ({ type: 'notify', text: '' }) },
	{
		key: 'webhook',
		cat: 'chat',
		label: 'call a web address (webhook)',
		needs: 'CONTROL',
		make: () => ({ type: 'webhook', url: 'https://', method: 'POST', body: '{"text": "{player} came by"}' })
	},

	{ key: 'set', cat: 'data', label: 'set a variable', needs: 'CONTROL', make: () => ({ type: 'set', name: 'last', value: '{player}', scope: 'flow' }) },
	{ key: 'change', cat: 'data', label: 'change a counter by …', needs: 'CONTROL', make: () => ({ type: 'change', name: 'count', by: 1, scope: 'flow' }) },
	{ key: 'broadcast', cat: 'data', label: 'broadcast to other flows or bots', needs: 'CONTROL', make: () => ({ type: 'broadcast', name: 'go', to: '' }) },
	{ key: 'log', cat: 'data', label: 'write to the run log', needs: 'CONTROL', make: () => ({ type: 'log', text: '' }) },

	{ key: 'connect', cat: 'connection', label: 'connect', needs: 'CONTROL', make: () => ({ type: 'connect', wait: true, timeout: 120 }) },
	{ key: 'disconnect', cat: 'connection', label: 'disconnect', needs: 'CONTROL', make: () => ({ type: 'disconnect' }) },
	{ key: 'reconnect', cat: 'connection', label: 'reconnect after … seconds', needs: 'CONTROL', make: () => ({ type: 'reconnect', seconds: 30, timeout: 120 }) },
	{ key: 'requeue', cat: 'connection', label: 'requeue (2b2t)', needs: 'CONTROL', make: act('requeue') },
	{ key: 'transfer', cat: 'connection', label: 'move to another server', needs: 'CONTROL', make: act('transfer', { address: '', port: null, player: null }) },
	{ key: 'kick', cat: 'connection', label: 'kick everyone off the proxy', needs: 'CONTROL', make: act('kick') },
	{ key: 'kickPlayer', cat: 'connection', label: 'kick a player off the proxy', needs: 'CONTROL', make: act('kick', { player: '{player}' }) },
	{ key: 'makePlayer', cat: 'connection', label: 'let a spectator take over', needs: 'CONTROL', make: act('makePlayer', { player: '{player}' }) },
	{ key: 'makeSpectator', cat: 'connection', label: 'turn the player into a spectator', needs: 'CONTROL', make: act('makeSpectator') },
	{ key: 'replayStart', cat: 'connection', label: 'start recording a replay', needs: 'CONTROL', make: act('replayStart') },
	{ key: 'replayStop', cat: 'connection', label: 'stop recording', needs: 'CONTROL', make: act('replayStop') },

	{ key: 'module', cat: 'bot', label: 'switch a module on or off', needs: 'SETTINGS', make: act('module', { module: 'autoEat', on: true }) },
	{ key: 'afkKeep', cat: 'bot', label: 'keep the bot in the AFK zone', needs: 'CONTROL', make: act('afkKeep', { keep: true }) },
	{ key: 'addWaypoint', cat: 'bot', label: 'save a waypoint', needs: 'SETTINGS', make: act('addWaypoint', { id: 'here', x: '{x}', y: '{y}', z: '{z}' }) },
	{ key: 'deleteWaypoint', cat: 'bot', label: 'delete a waypoint', needs: 'SETTINGS', make: act('deleteWaypoint', { id: '' }) }
];

/** Which block a step is, for its colour and picker entry. */
export function keyOf(step: FlowStep): string {
	if (step.type !== 'action') return step.type;
	const run = step.run ?? { action: '' };
	if (run.action === 'click')
		return run.mode === 'useItem' ? 'useItem' : run.mode === 'hold' ? (run.button === 'left' ? 'holdLeft' : 'holdRight') : 'clickRight';
	if (run.action === 'stop') return 'stop-moving';
	if (run.action === 'kick' && run.player) return 'kickPlayer';
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
	{ kind: 'receive', label: 'another flow broadcasts', group: 'Start', gives: ['from', 'fromBot'], waitable: true, defaults: { name: 'go' } },
	{ kind: 'joined', label: 'the bot gets into the game', group: 'Connection', gives: [], waitable: true },
	{ kind: 'onlineFor', label: 'the bot has been online for', group: 'Connection', gives: [], waitable: false, defaults: { minutes: 60 } },
	{ kind: 'left', label: 'the bot leaves the game', group: 'Connection', gives: ['message', 'why'], waitable: true, defaults: { reason: '', why: 'any' } },
	{ kind: 'crashed', label: 'the bot crashes', group: 'Connection', gives: ['message'], waitable: false },
	{ kind: 'queueBelow', label: 'the queue drops below', group: 'Connection', gives: ['position'], waitable: true, defaults: { position: 20 } },
	{ kind: 'queueComplete', label: 'the queue is done', group: 'Connection', gives: [], waitable: true },
	{ kind: 'serverRestarting', label: 'the server restarts soon', group: 'Connection', gives: ['message'], waitable: false },
	{ kind: 'tpsBelow', label: 'the server’s TPS drops below', group: 'Connection', gives: ['tps'], waitable: true, defaults: { tps: 15 } },
	{
		kind: 'chat',
		label: 'someone chats',
		group: 'Chat',
		gives: ['player', 'message'],
		waitable: true,
		defaults: { contains: '', from: '', match: 'contains' }
	},
	{
		kind: 'whisper',
		label: 'someone whispers',
		group: 'Chat',
		gives: ['player', 'message'],
		waitable: true,
		defaults: { contains: '', from: '', match: 'contains' }
	},
	{ kind: 'player', label: 'someone comes into view', group: 'Players', gives: ['player', 'x', 'y', 'z'], waitable: true, defaults: { who: 'stranger' } },
	{ kind: 'playerLeft', label: 'someone leaves view', group: 'Players', gives: ['player'], waitable: true, defaults: { who: 'anyone' } },
	{
		kind: 'entityNear',
		label: 'a player or mob comes near',
		group: 'Players',
		gives: ['entity', 'player', 'distance', 'x', 'y', 'z'],
		waitable: true,
		defaults: { what: 'stranger', radius: 16 }
	},
	{
		kind: 'entityLeft',
		label: 'a player or mob moves away',
		group: 'Players',
		gives: ['entity', 'player', 'distance', 'x', 'y', 'z'],
		waitable: true,
		defaults: { what: 'anyone', radius: 16 }
	},
	{ kind: 'serverJoin', label: 'someone joins the server', group: 'Players', gives: ['player'], waitable: true, defaults: { who: 'anyone' } },
	{ kind: 'serverLeave', label: 'someone leaves the server', group: 'Players', gives: ['player'], waitable: true, defaults: { who: 'anyone' } },
	{ kind: 'controlled', label: 'someone starts or stops playing', group: 'Players', gives: ['player'], waitable: true, defaults: { on: 'start' } },
	{ kind: 'unattended', label: 'nobody has played for', group: 'Players', gives: [], waitable: false, defaults: { minutes: 30 } },
	{ kind: 'proxyJoin', label: 'someone joins the proxy', group: 'Players', gives: ['player'], waitable: true, defaults: { who: 'anyone' } },
	{ kind: 'proxyLeave', label: 'someone leaves the proxy', group: 'Players', gives: ['player'], waitable: true, defaults: { who: 'anyone' } },
	{ kind: 'attacked', label: 'a player attacks the bot', group: 'Danger', gives: ['player'], waitable: true },
	{ kind: 'health', label: 'health drops below', group: 'Danger', gives: [], waitable: true, defaults: { below: 8 } },
	{ kind: 'damaged', label: 'the bot takes damage of at least', group: 'Danger', gives: ['previous'], waitable: true, defaults: { amount: 4 } },
	{ kind: 'food', label: 'food drops below', group: 'Danger', gives: [], waitable: true, defaults: { below: 6 } },
	{ kind: 'outOfFood', label: 'the bot has nothing left to eat', group: 'Danger', gives: [], waitable: true },
	{ kind: 'totem', label: 'a totem pops', group: 'Danger', gives: ['totemsLeft'], waitable: true },
	{ kind: 'noTotems', label: 'the last totem is gone', group: 'Danger', gives: [], waitable: false },
	{ kind: 'death', label: 'the bot dies', group: 'Danger', gives: ['x', 'y', 'z'], waitable: true },
	{ kind: 'respawned', label: 'the bot respawns', group: 'Danger', gives: [], waitable: true },
	{ kind: 'arrived', label: 'the bot arrives near', group: 'Where', gives: [], waitable: true, defaults: { x: 0, y: null, z: 0, radius: 5 } },
	{ kind: 'pathDone', label: 'the pathfinder finishes', group: 'Where', gives: [], waitable: true },
	{ kind: 'dimension', label: 'the bot changes dimension', group: 'Where', gives: ['dimension', 'previous'], waitable: true, defaults: { to: 'any' } },
	{ kind: 'yBelow', label: 'the bot falls below height', group: 'Where', gives: [], waitable: true, defaults: { y: 0 } },
	{ kind: 'afk', label: 'the AFK zone changes', group: 'Where', gives: [], waitable: true, defaults: { on: 'earningStopped' } },
	{
		kind: 'blockChanged',
		label: 'the block at x y z changes',
		group: 'Where',
		gives: ['block', 'previous'],
		waitable: true,
		defaults: { x: 0, y: 64, z: 0 }
	},
	{ kind: 'weather', label: 'the weather changes', group: 'Where', gives: [], waitable: true, defaults: { on: 'rainStart' } },
	{
		kind: 'itemBelow',
		label: 'an item runs low',
		group: 'Inventory',
		gives: ['item', 'count', 'previous'],
		waitable: true,
		defaults: { item: 'totem_of_undying', count: 2 }
	},
	{
		kind: 'itemAbove',
		label: 'the bot collects more than',
		group: 'Inventory',
		gives: ['item', 'count', 'previous'],
		waitable: true,
		defaults: { item: 'diamond', count: 63 }
	},
	{ kind: 'inventoryFull', label: 'the inventory is full', group: 'Inventory', gives: [], waitable: true },
	{
		kind: 'durability',
		label: 'a tool or armour piece wears below',
		group: 'Inventory',
		gives: ['item', 'count', 'percent'],
		waitable: true,
		defaults: { percent: 10 }
	},
	{ kind: 'containerOpened', label: 'a chest opens', group: 'Inventory', gives: ['message'], waitable: true },
	{ kind: 'containerClosed', label: 'a chest closes', group: 'Inventory', gives: [], waitable: true },
	{ kind: 'flowFailed', label: 'another flow fails', group: 'Start', gives: ['flow', 'error'], waitable: false, defaults: { flow: '' } }
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

/** why the bot left, for the "leaves the game" trigger */
export const whyOptions = [
	{ value: 'any', label: 'for any reason' },
	{ value: 'kicked', label: 'because it was kicked' },
	{ value: 'banned', label: 'because it was banned' },
	{ value: 'network', label: 'because the connection broke' },
	{ value: 'me', label: 'because we disconnected it' }
];
const whyText = Object.fromEntries(whyOptions.map((o) => [o.value, o.label]));

/** who or what "a player or mob comes within" looks for */
export const entityKinds = [
	{ value: 'stranger', label: 'a stranger (not a friend)' },
	{ value: 'anyone', label: 'any player' },
	{ value: 'friend', label: 'a friend' },
	{ value: 'player', label: 'this player' },
	{ value: 'hostile', label: 'a hostile mob' },
	{ value: 'mob', label: 'any mob' },
	{ value: 'type', label: 'this mob' },
	{ value: 'item', label: 'a dropped item' },
	{ value: 'vehicle', label: 'a boat or minecart' },
	{ value: 'any', label: 'anything' }
];

export function entityText(f: Record<string, unknown>): string {
	if (f.what === 'player') return String(f.name || 'a player');
	if (f.what === 'type') return 'a ' + String(f.mob || 'mob').replace(/_/g, ' ');
	return entityKinds.find((k) => k.value === f.what)?.label.replace(/ \(.*\)$/, '') ?? 'a player';
}

/** what "for each" goes through, and the values each round gets */
export const forEachLists = [
	{ value: 'nearbyPlayers', label: 'nearby player' },
	{ value: 'nearbyEntities', label: 'nearby player or mob' },
	{ value: 'proxyPlayers', label: 'player on the proxy' },
	{ value: 'inventory', label: 'item in the inventory' }
];
export const forEachGives = ['each', 'eachCount', 'eachDistance'];

function minutesText(m: number) {
	return m % 60 === 0 ? `${m / 60} h` : `${m} min`;
}

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
		case 'left': {
			const why = t.why && t.why !== 'any' ? ' ' + (whyText[String(t.why)] ?? '') : '';
			return t.reason ? `When the bot leaves${why} with “${t.reason}”` : `When the bot leaves the game${why}`;
		}
		case 'entityNear':
			return `When ${entityText(t)} comes within ${t.radius} blocks`;
		case 'entityLeft':
			return `When ${entityText(t)} goes further than ${t.radius} blocks`;
		case 'serverJoin':
		case 'serverLeave': {
			const who = t.who === 'stranger' ? 'a stranger' : t.who === 'friend' ? 'a friend' : !t.who || t.who === 'anyone' ? 'someone' : String(t.who);
			return `When ${who} ${t.kind === 'serverJoin' ? 'joins' : 'leaves'} the server`;
		}
		case 'damaged':
			return `When the bot loses ${Number(t.amount) / 2} ♥ at once`;
		case 'dimension':
			return !t.to || t.to === 'any' ? 'When the bot changes dimension' : `When the bot gets to ${dimensions.find((d) => d.value === t.to)?.label ?? t.to}`;
		case 'yBelow':
			return `When the bot falls below y ${t.y}`;
		case 'itemBelow':
			return `When ${t.item} drops below ${t.count}`;
		case 'itemAbove':
			return `When the bot has more than ${t.count} ${t.item}`;
		case 'durability':
			return `When a tool or armour piece wears below ${t.percent}%`;
		case 'tpsBelow':
			return `When TPS drops below ${t.tps}`;
		case 'weather':
			return t.on === 'rainStart' ? 'When it starts raining' : 'When it stops raining';
		case 'blockChanged':
			return `When the block at ${t.x} ${t.y} ${t.z} changes`;
		case 'flowFailed':
			return t.flow ? 'When a chosen flow fails' : 'When another flow fails';
		case 'controlled':
			return t.on === 'start' ? 'When someone starts playing' : 'When nobody is playing anymore';
		case 'afk':
			return 'When ' + (afkText[String(t.on)] ?? 'the AFK zone changes');
		case 'onlineFor':
			return `After ${minutesText(Number(t.minutes))} online`;
		case 'unattended':
			return `When nobody has played for ${minutesText(Number(t.minutes))}`;
		case 'arrived':
			return `When the bot arrives within ${t.radius} blocks of ${t.x}, ${t.y != null && t.y !== '' ? t.y + ', ' : ''}${t.z}`;
		case 'proxyJoin':
		case 'proxyLeave': {
			const who = !t.who || t.who === 'anyone' ? 'someone' : String(t.who);
			return `When ${who} ${t.kind === 'proxyJoin' ? 'joins' : 'leaves'} the proxy`;
		}
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
	{ kind: 'holding', label: 'the bot holds', group: 'Bot', defaults: () => ({ item: 'diamond_pickaxe' }) },
	{ kind: 'inventoryFull', label: 'the inventory is full', group: 'Bot' },
	{ kind: 'containerOpen', label: 'a chest is open', group: 'Bot' },
	{ kind: 'pathing', label: 'the pathfinder is busy', group: 'Bot' },
	{ kind: 'alive', label: 'the bot is alive', group: 'Bot' },
	{ kind: 'near', label: 'the bot is near', group: 'Where', defaults: () => ({ x: 0, y: null, z: 0, radius: 32 }) },
	{ kind: 'entityNear', label: 'there is', group: 'Where', defaults: () => ({ what: 'stranger', radius: 32 }) },
	{ kind: 'block', label: 'the block at', group: 'Where', defaults: () => ({ x: 0, y: 64, z: 0, is: 'chest' }) },
	{ kind: 'raining', label: 'it’s raining', group: 'Where' },
	{ kind: 'dimension', label: 'the bot is in', group: 'Where', defaults: () => ({ is: 'the_nether' }) },
	{ kind: 'inAfkZone', label: 'the bot is in the AFK zone', group: 'Where' },
	{ kind: 'earning', label: 'the bot earns in the AFK zone', group: 'Where' },
	{ kind: 'text', label: 'the message contains', group: 'Event', defaults: () => ({ contains: '' }) },
	{ kind: 'friend', label: 'the player is a friend', group: 'Event' },
	{ kind: 'result', label: 'the last wait or try', group: 'Event', defaults: () => ({ is: 'timeout' }) },
	{ kind: 'var', label: 'the variable', group: 'Other', defaults: () => ({ name: 'count', op: '>', value: '0', scope: 'flow' }) },
	{ kind: 'all', label: 'all of these are true', group: 'Groups', defaults: () => ({ conds: [{ kind: 'online', not: false }] }) },
	{ kind: 'any', label: 'any of these is true', group: 'Groups', defaults: () => ({ conds: [{ kind: 'online', not: false }] }) },
	{ kind: 'time', label: 'the time is between', group: 'Other', defaults: () => ({ from: '22:00', to: '06:00', zone: zone() }) },
	{ kind: 'chance', label: 'a dice roll, with chance', group: 'Other', defaults: () => ({ percent: 50 }) }
];

export function makeCondition(kind: string, not = false): FlowCondition {
	return { kind, not, ...(conditions.find((c) => c.kind === kind)?.defaults?.() ?? {}) };
}

export const scopes = [
	{ value: 'flow', label: 'this flow' },
	{ value: 'bot', label: 'all flows of this bot' },
	{ value: 'shared', label: 'all my bots' }
];

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
		[s.then, s.else, s.steps, s.catch].forEach((list) => list?.forEach(renew));
	};
	renew(copy);
	return copy;
}

const children = (s: FlowStep) => [...(s.then ?? []), ...(s.else ?? []), ...(s.steps ?? []), ...(s.catch ?? [])];

export function countSteps(steps: FlowStep[]): number {
	return steps.reduce((n, s) => n + 1 + countSteps(children(s)), 0);
}

/** the variables a flow sets, for the value chips */
export function variablesIn(steps: FlowStep[], into = new Set<string>()): string[] {
	for (const s of steps) {
		if ((s.type === 'set' || s.type === 'change') && s.name && (s.scope ?? 'flow') === 'flow') into.add(s.name);
		variablesIn(children(s), into);
	}
	return [...into].sort();
}

/** values a block can use because of the blocks it sits in: "for each" gives {each} */
export function valuesIn(steps: FlowStep[], into = new Set<string>()): string[] {
	for (const s of steps) {
		if (s.type === 'forEach') forEachGives.forEach((v) => into.add(v));
		if (
			s.type === 'waitFor' ||
			s.type === 'waitUntil' ||
			s.type === 'try' ||
			s.type === 'connect' ||
			s.type === 'reconnect' ||
			s.type === 'runFlow' ||
			s.type === 'webhook' ||
			s.wait === 'done'
		)
			into.add('result');
		if (s.type === 'try' || s.keepGoing) into.add('error');
		if (s.type === 'webhook') ['response', 'code'].forEach((v) => into.add(v));
		if (s.type === 'lookAt') into.add('entity');
		valuesIn(children(s), into);
	}
	return [...into];
}

/** whether target sits inside step (or is it), so a block can't be dropped into itself */
export function contains(step: FlowStep, list: FlowStep[]): boolean {
	return [step.then, step.else, step.steps, step.catch].some((l) => l === list || l?.some((c) => contains(c, list)));
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
	const { name, enabled, cooldown, mode, burst, trigger, steps } = flow;
	const bytes = new TextEncoder().encode(JSON.stringify({ name, enabled, cooldown, mode, burst, trigger, steps }));
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
		...(parsed.burst ? { burst: parsed.burst } : {}),
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
		...(flow.burst ? { burst: { ...flow.burst } } : {}),
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
			name: 'Reconnect when kicked',
			blurb: 'Gets back in 30 to 90 seconds after a kick or a broken connection, and tells you if that fails.',
			enabled: true,
			cooldown: 30,
			trigger: { kind: 'left', reason: '', why: 'kicked' },
			steps: [
				s({ type: 'notify', text: 'Kicked: {message}. Reconnecting…' }),
				s({
					type: 'try',
					steps: [s({ type: 'reconnect', seconds: 30, upTo: 90, timeout: 300 })],
					catch: [s({ type: 'notify', text: 'Couldn’t get back in: {error}' })]
				})
			]
		},
		{
			name: 'Mob alarm',
			blurb: 'Notifies you when a hostile mob gets within 8 blocks while nobody is playing.',
			enabled: true,
			cooldown: 60,
			trigger: { kind: 'entityNear', what: 'hostile', radius: 8 },
			steps: [
				s({
					type: 'if',
					cond: { kind: 'playing', not: true },
					then: [s({ type: 'notify', text: 'A {entity} is {distance} blocks away at {x} {y} {z}' })],
					else: []
				})
			]
		},
		{
			name: 'Watch the stash',
			blurb: 'Tells you when the chest at your stash is broken or swapped. Set its position first.',
			enabled: false,
			cooldown: 10,
			trigger: { kind: 'blockChanged', x: 0, y: 64, z: 0 },
			steps: [s({ type: 'notify', text: 'The stash block is now {block} (was {previous})' })]
		},
		{
			name: 'Mine until full',
			blurb: 'Mines a block type until the inventory is full, then walks home and empties into the chest there.',
			enabled: true,
			cooldown: 10,
			trigger: { kind: 'manual' },
			steps: [
				s({ type: 'action', run: { action: 'mine', block: 'diamond_ore' }, wait: 'no' }),
				s({ type: 'waitFor', event: { kind: 'inventoryFull' }, timeout: 3600 }),
				s({ type: 'action', run: { action: 'stop' } }),
				s({ type: 'action', run: { action: 'clickBlock', button: 'right', waypoint: 'home' }, wait: 'done', timeout: 600 }),
				s({ type: 'wait', seconds: 1 }),
				s({ type: 'action', run: { action: 'deposit' } }),
				s({ type: 'action', run: { action: 'closeContainer' } })
			]
		},
		{
			name: 'Tool almost broken',
			blurb: 'A heads-up when a tool or armour piece drops below 10% durability.',
			enabled: true,
			cooldown: 60,
			trigger: { kind: 'durability', percent: 10 },
			steps: [s({ type: 'notify', text: '{item} has {count} uses left' })]
		},
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
