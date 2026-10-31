<script lang="ts">
	import { api } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { runAction, type Action } from '#lib/actions.js';
	import { DetailsPoller } from '#lib/details.svelte.js';
	import { duration } from '#lib/format.js';
	import { attempt } from '#lib/toast.svelte.js';
	import type { Capability, Pearl, ScheduledTask, TaskEvent } from '#lib/types.js';
	import { Bell, CalendarClock, Plus, Trash2, X } from '@lucide/svelte';

	let { instanceId, capabilities, running }: { instanceId: string; capabilities: Capability[]; running: boolean } = $props();

	// the page remounts this per instance ({#key}), so the first id is the only one
	// svelte-ignore state_referenced_locally
	const poller = new DetailsPoller(instanceId, 5000);
	$effect(() => poller.start());
	const details = $derived(poller.details);
	const tasks = $derived(details?.tasks ?? []);

	// ticks every second so "in 4 min" counts down between polls
	let now = $state(Date.now());
	$effect(() => {
		const timer = setInterval(() => (now = Date.now()), 1000);
		return () => clearInterval(timer);
	});

	// what "When…" can wait for: Zenith's own events, and ZenithHub's triggers with settings
	interface When {
		id: string;
		label: string;
		group: string;
		/** Zenith's event id, for the plain events */
		event?: TaskEvent;
	}
	const whens: When[] = [
		{ id: 'online', event: 'online', label: 'the bot gets into the game', group: 'Connection' },
		{ id: 'connect', event: 'connect', label: 'the bot connects', group: 'Connection' },
		{ id: 'disconnect', event: 'disconnect', label: 'the bot disconnects', group: 'Connection' },
		{ id: 'onlineFor', label: 'the bot has been online for a while', group: 'Connection' },
		{ id: 'afk:leftZone', label: 'the bot leaves the AFK zone', group: 'AFK zone' },
		{ id: 'afk:earningStopped', label: 'the bot stops earning AFK rewards', group: 'AFK zone' },
		{ id: 'afk:earningStarted', label: 'the bot starts earning AFK rewards', group: 'AFK zone' },
		{ id: 'player', label: 'a player comes into view', group: 'Players' },
		{ id: 'unattended', label: 'nobody has played for a while', group: 'Players' },
		{ id: 'playerConnect', event: 'playerConnect', label: 'a player joins the proxy', group: 'Players' },
		{ id: 'playerDisconnect', event: 'playerDisconnect', label: 'a player leaves the proxy', group: 'Players' },
		{ id: 'health', label: 'health drops low', group: 'Danger' },
		{ id: 'attacked', label: 'a player attacks the bot', group: 'Danger' },
		{ id: 'totem', label: 'a totem pops', group: 'Danger' },
		{ id: 'death', event: 'death', label: 'the bot dies', group: 'Danger' },
		{ id: 'arrived', label: 'the bot arrives somewhere', group: 'Places' }
	];
	const groups = [...new Set(whens.map((w) => w.group))];
	const DAYS = ['Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa', 'Su'];

	function spec(t: ScheduledTask): Record<string, string> {
		const [kind, ...rest] = (t.trigger ?? '').split(';');
		const params: Record<string, string> = { kind };
		for (const part of rest) {
			const eq = part.indexOf('=');
			if (eq > 0) params[part.slice(0, eq)] = part.slice(eq + 1);
		}
		return params;
	}

	/** "1m" rather than "1m 0s" */
	function short(seconds: number) {
		return duration(seconds).replace(/ 0s$/, '');
	}

	function minutes(n: string | undefined) {
		return short(Number(n ?? 0) * 60);
	}

	/** what a trigger task waits for, in words */
	function triggerText(t: ScheduledTask) {
		const p = spec(t);
		switch (p.kind) {
			case 'afk':
				return whens.find((w) => w.id === `afk:${p.on}`)?.label ?? 'the AFK zone changes';
			case 'player':
				return p.who === 'anyone' ? 'a player comes into view' : p.who === 'stranger' ? 'a non-friend comes into view' : `${p.who} comes into view`;
			case 'health':
				return `health drops below ${Number(p.below) / 2} hearts`;
			case 'unattended':
				return `nobody has played for ${minutes(p.minutes)}`;
			case 'onlineFor':
				return `the bot has been online for ${minutes(p.minutes)}`;
			case 'arrived':
				return `the bot gets within ${p.radius} blocks of ${p.x}, ${p.z}`;
			default:
				return whens.find((w) => w.id === p.kind)?.label ?? p.kind;
		}
	}

	function dailyText(t: ScheduledTask) {
		const p = spec(t);
		const days = p.days && p.days.length < 7 ? [...p.days].map((d) => DAYS[Number(d) - 1]).join(', ') : 'Every day';
		const next = t.next ? new Date(t.next).toLocaleString([], { weekday: 'short', hour: '2-digit', minute: '2-digit', hour12: false }) : null;
		return `${days} at ${p.at}${Number(p.jitter) ? ` (up to ${p.jitter} min later)` : ''}${next ? `, next ${next}` : ''}`;
	}

	/** the cooldown, delay and "nobody playing" settings of a trigger task */
	function triggerExtras(t: ScheduledTask) {
		const p = spec(t);
		const parts = [];
		if (p.kind !== 'daily') parts.push(`at most every ${short(Number(p.cooldown ?? 60))}`);
		if (Number(p.delay)) parts.push(`after up to ${short(Number(p.delay))}`);
		if (p.ifIdle === '1') parts.push('only if nobody is playing');
		return parts.join(', ');
	}

	function when(t: ScheduledTask) {
		if (t.type === 'event') {
			const label = whens.find((w) => w.event === t.event)?.label ?? t.event;
			return `${t.repeat ? 'Every time' : 'Next time'} ${label}`;
		}
		if (t.type === 'trigger') {
			if (spec(t).kind === 'daily') return dailyText(t);
			return `${t.repeat ? 'Every time' : 'Next time'} ${triggerText(t)}`;
		}
		const next = t.next ? Math.max(0, Math.round((t.next - now) / 1000)) : null;
		const inText = next == null ? '' : next === 0 ? 'now' : `in ${duration(next)}`;
		if (t.type === 'interval') return `Every ${duration(t.intervalSeconds ?? 0)}${inText ? `, next ${inText}` : ''}`;
		if (t.type === 'timed') return `Once, ${inText}`;
		return '';
	}

	// --- what a task does ---
	interface Choice {
		id: string;
		label: string;
		needs: Capability;
		devOnly?: boolean;
	}
	const choices: Choice[] = [
		{ id: 'notify', label: 'Notify me', needs: 'CONTROL' },
		{ id: 'loadPearl', label: 'Load a stasis pearl', needs: 'PEARLS' },
		{ id: 'respawn', label: 'Respawn', needs: 'PLAY' },
		{ id: 'goto', label: 'Walk to coordinates', needs: 'PLAY' },
		{ id: 'gotoWaypoint', label: 'Walk to a waypoint', needs: 'PLAY' },
		{ id: 'stop', label: 'Stop moving', needs: 'PLAY' },
		{ id: 'afkEnter', label: 'Go into the AFK zone', needs: 'PLAY' },
		{ id: 'afkLeave', label: 'Leave the AFK zone', needs: 'PLAY' },
		{ id: 'clickRight', label: 'Right click once', needs: 'PLAY' },
		{ id: 'useItem', label: 'Use the held item', needs: 'PLAY' },
		{ id: 'holdRight', label: 'Keep right clicking', needs: 'PLAY' },
		{ id: 'holdLeft', label: 'Keep left clicking', needs: 'PLAY' },
		{ id: 'clickStop', label: 'Stop clicking', needs: 'PLAY' },
		{ id: 'replayStart', label: 'Start recording', needs: 'CONTROL' },
		{ id: 'replayStop', label: 'Stop recording', needs: 'CONTROL' },
		{ id: 'kick', label: 'Kick everyone off the proxy', needs: 'CONTROL' },
		{ id: 'requeue', label: 'Requeue (2b2t)', needs: 'CONTROL' },
		{ id: 'command', label: 'Run a command', needs: 'COMMANDS', devOnly: true }
	];
	const available = $derived(choices.filter((c) => capabilities.includes(c.needs) && (!c.devOnly || app.devMode)));
	// these move or act in the game, so by default they wait until nobody is playing
	const MOVES = new Set(['goto', 'gotoWaypoint', 'stop', 'afkEnter', 'afkLeave', 'clickRight', 'useItem', 'holdRight', 'holdLeft', 'clickStop', 'respawn']);

	let adding = $state(false);
	let name = $state('');
	let trigger = $state<'after' | 'every' | 'daily' | 'event'>('after');
	let amount = $state('30');
	let unit = $state<1 | 60 | 3600>(60);
	let event = $state('online');
	let who = $state<'anyone' | 'stranger' | 'named'>('stranger');
	let whoName = $state('');
	let hearts = $state(5);
	let waitMinutes = $state('10');
	let px = $state('');
	let pz = $state('');
	let radius = $state('5');
	let at = $state('08:00');
	let days = $state([true, true, true, true, true, true, true]);
	let jitter = $state(0);
	let cooldown = $state(60);
	let delay = $state(0);
	let ifIdle = $state(false);
	let message = $state('');
	const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
	let permission = $state(typeof Notification === 'undefined' ? 'unsupported' : Notification.permission);
	let repeat = $state(true);
	let what = $state('loadPearl');
	let pearl = $state('');
	let waypoint = $state('');
	let x = $state('');
	let y = $state('');
	let z = $state('');
	let command = $state('');
	let busy = $state(false);
	let pearls = $state<Pearl[]>([]);

	$effect(() => {
		if (adding && capabilities.includes('PEARLS')) {
			api.get<Pearl[]>(`/instances/${instanceId}/pearls`).then((list) => {
				pearls = list;
				if (!pearl && list.length) pearl = list[0].id;
			}, () => {});
		}
	});

	function startAdd() {
		let n = tasks.length + 1;
		while (tasks.some((t) => t.id === `task${n}`)) n++;
		name = `task${n}`;
		setWhat(available[0]?.id ?? '');
		adding = true;
	}

	function setWhat(id: string) {
		what = id;
		ifIdle = MOVES.has(id);
	}

	async function allowNotifications() {
		permission = await Notification.requestPermission();
	}

	function run(): Action | null {
		const num = (s: string) => (s.trim() === '' ? null : Number(s));
		switch (what) {
			case 'loadPearl':
				return pearl ? { action: 'loadPearl', pearl } : null;
			case 'goto':
				return x.trim() && z.trim() ? { action: 'goto', x: num(x), y: num(y), z: num(z) } : null;
			case 'gotoWaypoint':
				return waypoint.trim() ? { action: 'gotoWaypoint', waypoint: waypoint.trim() } : null;
			case 'clickRight':
				return { action: 'click', button: 'right' };
			case 'useItem':
				return { action: 'click', button: 'right', mode: 'useItem' };
			case 'holdRight':
				return { action: 'click', button: 'right', mode: 'hold' };
			case 'holdLeft':
				return { action: 'click', button: 'left', mode: 'hold' };
			case 'command':
				return command.trim() ? { action: 'command', command: command.trim() } : null;
			case 'notify':
				return message.trim() ? { action: 'notify', text: message.trim() } : null;
			default:
				return { action: what };
		}
	}

	/** the settings of a ZenithHub trigger, or null for Zenith's plain events */
	function triggerBody(): Record<string, unknown> | null {
		const extras = { cooldown, delay, ifIdle };
		if (trigger === 'daily') {
			const picked = days.flatMap((on, i) => (on ? [String(i + 1)] : [])).join('');
			return { kind: 'daily', at, days: picked.length === 7 ? null : picked, zone, jitter, delay, ifIdle };
		}
		const [kind, on] = event.split(':');
		switch (kind) {
			case 'afk':
				return { kind, on, ...extras };
			case 'player':
				return { kind, who: who === 'named' ? whoName.trim() : who, ...extras };
			case 'health':
				return { kind, below: hearts * 2, ...extras };
			case 'unattended':
			case 'onlineFor':
				return { kind, minutes: Number(waitMinutes), ...extras };
			case 'arrived': {
				return { kind, x: Number(px), z: Number(pz), radius: Number(radius), ...extras };
			}
			case 'attacked':
			case 'totem':
				return { kind, ...extras };
			default:
				return null;
		}
	}

	const triggerValid = $derived.by(() => {
		if (trigger === 'daily') return /^\d{2}:\d{2}$/.test(at) && days.some((d) => d);
		const kind = event.split(':')[0];
		if (kind === 'player' && who === 'named') return /^[A-Za-z0-9_]{1,16}$/.test(whoName.trim());
		if (kind === 'unattended' || kind === 'onlineFor') return Number.isInteger(Number(waitMinutes)) && Number(waitMinutes) >= 1;
		if (kind === 'arrived') return /^-?\d+$/.test(px.trim()) && /^-?\d+$/.test(pz.trim()) && Number.isInteger(Number(radius)) && Number(radius) >= 1;
		return true;
	});

	const seconds = $derived(Math.round(Number(amount) * unit));
	const valid = $derived(
		/^[A-Za-z0-9_.-]{1,32}$/.test(name.trim()) &&
			!tasks.some((t) => t.id === name.trim()) &&
			(trigger === 'event' || trigger === 'daily' ? triggerValid : Number.isFinite(seconds) && seconds >= 1) &&
			run() !== null
	);

	async function add() {
		const action = run();
		if (!action) return;
		const body: Action = { action: 'addTask', id: name.trim(), when: trigger, run: action };
		if (trigger === 'after') body.delaySeconds = seconds;
		if (trigger === 'every') {
			// Zenith waits the start delay plus one interval before the first run
			body.intervalSeconds = seconds;
			body.startSeconds = 0;
		}
		const settings = trigger === 'event' || trigger === 'daily' ? triggerBody() : null;
		if (settings) {
			body.when = 'trigger';
			body.trigger = settings;
			body.repeat = trigger === 'daily' || repeat;
		} else if (trigger === 'event') {
			body.event = event;
			body.repeat = repeat;
		}
		busy = true;
		const ok = await runAction(instanceId, body, true);
		busy = false;
		if (ok) {
			adding = false;
			void poller.refresh();
		}
	}

	async function remove(t: ScheduledTask) {
		busy = true;
		await runAction(instanceId, { action: 'deleteTask', id: t.id }, true);
		busy = false;
		void poller.refresh();
	}

	async function enableTasks() {
		await attempt(() => api.patch(`/instances/${instanceId}/settings`, { set: { 'client.extra.tasks.enabled': true } }), 'Tasks turned on');
		void poller.refresh();
	}
</script>

<div class="mx-auto w-full max-w-3xl space-y-4">
	{#if !running}
		<div class="card p-6 text-center text-sm text-zinc-400">Connect the instance to see and add tasks.</div>
	{:else}
		{#if details && details.tasksEnabled === false}
			<div class="flex flex-wrap items-center gap-3 rounded-lg border border-amber-900/60 bg-amber-950/30 px-4 py-3 text-sm text-amber-200">
				<span class="flex-1">Tasks are turned off in the settings, so none of them run.</span>
				{#if capabilities.includes('SETTINGS')}<button class="btn-secondary py-1.5" onclick={enableTasks}>Turn on</button>{/if}
			</div>
		{/if}

		<section class="card">
			<div class="flex items-center gap-2 border-b border-zinc-800 px-4 py-3">
				<div class="flex-1">
					<h2 class="text-sm font-semibold text-zinc-200">Scheduled tasks</h2>
					<p class="text-xs text-zinc-500">Let the bot do something later, on a timer, or when something happens.</p>
				</div>
				{#if !adding && available.length}
					<button class="btn-secondary shrink-0 py-1.5" onclick={startAdd}><Plus size={15} /> Add</button>
				{/if}
			</div>

			{#if adding}
				<form
					class="space-y-4 border-b border-zinc-800 bg-zinc-950/40 px-4 py-4"
					onsubmit={(e) => {
						e.preventDefault();
						if (valid) add();
					}}
				>
					<div class="flex items-center">
						<h3 class="flex-1 text-sm font-medium text-zinc-200">New task</h3>
						<button type="button" class="btn-ghost p-1.5" aria-label="Cancel" onclick={() => (adding = false)}><X size={16} /></button>
					</div>

					<div>
						<span class="label">When</span>
						<div class="mb-2 grid grid-cols-2 gap-1 sm:grid-cols-4">
							{#each [['after', 'Once, later'], ['every', 'Repeat'], ['daily', 'At a time'], ['event', 'When…']] as [id, label]}
								<button
									type="button"
									class="rounded-lg border px-2 py-1.5 text-sm {trigger === id
										? 'border-emerald-600 bg-emerald-950/50 text-emerald-200'
										: 'border-zinc-700 text-zinc-400 hover:text-zinc-200'}"
									onclick={() => (trigger = id as typeof trigger)}>{label}</button
								>
							{/each}
						</div>
						{#if trigger === 'event'}
							<div class="grid gap-2 sm:grid-cols-[1fr_auto]">
								<select class="input" bind:value={event} aria-label="Event">
									{#each groups as g}
										<optgroup label={g}>
											{#each whens.filter((w) => w.group === g) as w}<option value={w.id}>When {w.label}</option>{/each}
										</optgroup>
									{/each}
								</select>
								<select class="input" bind:value={repeat} aria-label="How often">
									<option value={true}>Every time</option>
									<option value={false}>Only once</option>
								</select>
							</div>
							{@const kind = event.split(':')[0]}
							{#if kind === 'player'}
								<div class="mt-2 grid gap-2 sm:grid-cols-[auto_1fr]">
									<select class="input" bind:value={who} aria-label="Which player">
										<option value="stranger">Anyone who isn't a friend</option>
										<option value="anyone">Anyone</option>
										<option value="named">A certain player</option>
									</select>
									{#if who === 'named'}
										<input class="input" placeholder="Player name" maxlength="16" autocapitalize="off" spellcheck="false" aria-label="Player name" bind:value={whoName} />
									{/if}
								</div>
								<p class="mt-1 text-xs text-zinc-500">Friends are the players on the Friends list in Settings. Spectators on the proxy don't count.</p>
							{:else if kind === 'health'}
								<div class="mt-2 flex items-center gap-2">
									<span class="text-sm text-zinc-400">Below</span>
									<select class="input w-28" bind:value={hearts} aria-label="Hearts">
										{#each [1, 2, 3, 4, 5, 6, 7, 8, 9] as h}<option value={h}>{h} ♥</option>{/each}
									</select>
									<span class="text-sm text-zinc-400">of 10 hearts</span>
								</div>
							{:else if kind === 'unattended' || kind === 'onlineFor'}
								<div class="mt-2 flex items-center gap-2">
									<span class="text-sm text-zinc-400">{kind === 'unattended' ? 'Nobody for' : 'Online for'}</span>
									<input class="input w-24" inputmode="numeric" aria-label="Minutes" bind:value={waitMinutes} />
									<span class="text-sm text-zinc-400">minutes</span>
								</div>
								<p class="mt-1 text-xs text-zinc-500">
									{kind === 'unattended'
										? 'Counts while the bot is in the game, from when it got in or someone last played on it.'
										: 'Counts from when the bot got into the game, again after every reconnect.'}
								</p>
							{:else if kind === 'arrived'}
								{#if details?.waypoints?.length}
									<select
										class="input mt-2"
										aria-label="Waypoint"
										onchange={(e) => {
											const w = details?.waypoints.find((w) => w.id === (e.currentTarget as HTMLSelectElement).value);
											if (w) [px, pz] = [String(Math.round(w.x)), String(Math.round(w.z))];
										}}
									>
										<option value="">Pick a waypoint, or type coordinates</option>
										{#each details.waypoints as w}<option value={w.id}>{w.id}</option>{/each}
									</select>
								{/if}
								<div class="mt-2 grid grid-cols-3 gap-2">
									<input class="input" placeholder="X" inputmode="numeric" aria-label="X" bind:value={px} />
									<input class="input" placeholder="Z" inputmode="numeric" aria-label="Z" bind:value={pz} />
									<label class="flex items-center gap-1.5">
										<input class="input min-w-0" inputmode="numeric" aria-label="Radius in blocks" bind:value={radius} />
										<span class="text-xs text-zinc-500">blocks</span>
									</label>
								</div>
								<p class="mt-1 text-xs text-zinc-500">When it comes within that many blocks, not when it's already there.</p>
							{:else if kind === 'afk'}
								<p class="mt-1 text-xs text-zinc-500">Set the AFK zone up on the AFK tab first.</p>
							{/if}
						{:else if trigger === 'daily'}
							<div class="flex flex-wrap items-center gap-2">
								<input class="input w-32" type="time" aria-label="Time" bind:value={at} />
								<select class="input w-auto" bind:value={jitter} aria-label="Random offset">
									<option value={0}>exactly</option>
									<option value={5}>up to 5 min later</option>
									<option value={15}>up to 15 min later</option>
									<option value={30}>up to 30 min later</option>
									<option value={60}>up to an hour later</option>
								</select>
							</div>
							<div class="mt-2 grid grid-cols-7 gap-1">
								{#each DAYS as d, i}
									<button
										type="button"
										aria-pressed={days[i]}
										class="rounded-lg border py-1.5 text-sm {days[i]
											? 'border-emerald-600 bg-emerald-950/50 text-emerald-200'
											: 'border-zinc-700 text-zinc-500 hover:text-zinc-200'}"
										onclick={() => (days[i] = !days[i])}>{d}</button
									>
								{/each}
							</div>
							<p class="mt-1 text-xs text-zinc-500">Your time zone ({zone}). If the bot is off at that time, it waits for the next one.</p>
						{:else}
							<div class="flex items-center gap-2">
								<span class="text-sm text-zinc-400">{trigger === 'after' ? 'In' : 'Every'}</span>
								<input class="input w-24" inputmode="decimal" aria-label="Amount" bind:value={amount} />
								<select class="input w-32" bind:value={unit} aria-label="Unit">
									<option value={1}>seconds</option>
									<option value={60}>minutes</option>
									<option value={3600}>hours</option>
								</select>
							</div>
						{/if}
					</div>

					<div>
						<label class="label" for="task-what">Do</label>
						<select id="task-what" class="input" value={what} onchange={(e) => setWhat((e.currentTarget as HTMLSelectElement).value)}>
							{#each available as c}<option value={c.id}>{c.label}</option>{/each}
						</select>
						<div class="mt-2">
							{#if what === 'loadPearl'}
								{#if pearls.length}
									<select class="input" bind:value={pearl} aria-label="Pearl">
										{#each pearls as p}<option value={p.id}>{p.id}'s pearl</option>{/each}
									</select>
								{:else}
									<p class="text-xs text-zinc-500">Add a pearl on the Pearls tab first.</p>
								{/if}
							{:else if what === 'goto'}
								<div class="grid grid-cols-3 gap-2">
									<input class="input" placeholder="X" inputmode="numeric" aria-label="X" bind:value={x} />
									<input class="input" placeholder="Y (any)" inputmode="numeric" aria-label="Y" bind:value={y} />
									<input class="input" placeholder="Z" inputmode="numeric" aria-label="Z" bind:value={z} />
								</div>
							{:else if what === 'gotoWaypoint'}
								<select class="input" bind:value={waypoint} aria-label="Waypoint">
									<option value="" disabled>Pick a waypoint</option>
									{#each details?.waypoints ?? [] as w}<option value={w.id}>{w.id}</option>{/each}
								</select>
							{:else if what === 'notify'}
								<input class="input" placeholder="e.g. A stranger is near the base" maxlength="200" aria-label="Message" bind:value={message} />
								<p class="mt-1 text-xs text-zinc-500">
									Shows up here in ZenithHub for you, and in the instance's Discord channel unless that's switched off under Settings → Discord.
								</p>
								{#if permission === 'default'}
									<button type="button" class="btn-ghost mt-1 px-0 py-1 text-xs" onclick={allowNotifications}>
										<Bell size={13} /> Also show it when this tab is in the background
									</button>
								{:else if permission === 'unsupported' && !window.isSecureContext}
									<p class="mt-1 text-xs text-zinc-500">Over plain http the browser only shows it while ZenithHub is open.</p>
								{/if}
							{:else if what === 'command'}
								<input class="input font-mono" placeholder="e.g. say hello" autocapitalize="off" spellcheck="false" bind:value={command} />
							{/if}
						</div>
					</div>

					{#if trigger === 'event' || trigger === 'daily'}
						{@const custom = trigger === 'daily' || !whens.find((w) => w.id === event)?.event}
						{#if custom}
							<div class="space-y-2">
								<span class="label">Options</span>
								{#if trigger === 'event'}
									<div class="flex flex-wrap items-center gap-2 text-sm text-zinc-400">
										At most once every
										<select class="input w-auto" bind:value={cooldown} aria-label="Cooldown">
											<option value={10}>10 seconds</option>
											<option value={60}>minute</option>
											<option value={300}>5 minutes</option>
											<option value={900}>15 minutes</option>
											<option value={3600}>hour</option>
										</select>
									</div>
								{/if}
								<div class="flex flex-wrap items-center gap-2 text-sm text-zinc-400">
									Then wait
									<select class="input w-auto" bind:value={delay} aria-label="Random wait">
										<option value={0}>no time</option>
										<option value={10}>up to 10 seconds</option>
										<option value={30}>up to 30 seconds</option>
										<option value={120}>up to 2 minutes</option>
										<option value={600}>up to 10 minutes</option>
									</select>
									<span class="text-xs text-zinc-500">at random, so it doesn't look scripted</span>
								</div>
								<label class="flex items-center gap-2 text-sm text-zinc-300">
									<input type="checkbox" class="size-4 accent-emerald-600" bind:checked={ifIdle} />
									Only if nobody is playing on the account
								</label>
							</div>
						{/if}
					{/if}

					<label class="block">
						<span class="label">Name</span>
						<input class="input" maxlength="32" autocapitalize="off" spellcheck="false" bind:value={name} />
						{#if tasks.some((t) => t.id === name.trim())}
							<span class="mt-1 block text-xs text-red-300">There's already a task with that name.</span>
						{:else if name.trim() && !/^[A-Za-z0-9_.-]{1,32}$/.test(name.trim())}
							<span class="mt-1 block text-xs text-red-300">Letters, numbers, _ . and - only, no spaces.</span>
						{/if}
					</label>

					<button class="btn-primary" disabled={!valid || busy}>Add task</button>
				</form>
			{/if}

			{#if !details}
				<p class="px-4 py-6 text-sm {poller.error ? 'text-red-300' : 'text-zinc-500'}">
					{poller.error ? `Couldn't load the tasks: ${poller.error}` : 'Loading…'}
				</p>
			{:else if !tasks.length && !adding}
				<div class="flex flex-col items-center gap-2 px-4 py-10 text-center text-sm text-zinc-400">
					<CalendarClock size={28} class="text-zinc-600" />
					No tasks yet. For example: load your pearl in 30 minutes, respawn every time the bot dies, or tell you when a stranger comes near.
				</div>
			{:else}
				<ul class="divide-y divide-zinc-800">
					{#each tasks as t (t.id)}
						<li class="flex items-center gap-3 px-4 py-3">
							<div class="min-w-0 flex-1">
								<!-- stacked on phones, so neither the name nor the trigger is cut to a few letters -->
								<div class="sm:flex sm:items-baseline sm:gap-2">
									<div class="truncate font-medium text-zinc-100 sm:shrink-0">{t.id}</div>
									<div class="text-xs text-zinc-400 sm:truncate">{when(t)}</div>
								</div>
								{#if t.type === 'trigger' && triggerExtras(t)}<div class="text-xs text-zinc-500 sm:truncate">{triggerExtras(t)}</div>{/if}
								<div class="truncate font-mono text-xs text-zinc-500">{t.command}</div>
							</div>
							{#if capabilities.includes('CONTROL')}
								<button class="btn-ghost p-2" title="Delete" aria-label="Delete task {t.id}" disabled={busy} onclick={() => remove(t)}>
									<Trash2 size={15} />
								</button>
							{/if}
						</li>
					{/each}
				</ul>
			{/if}
		</section>
	{/if}
</div>
