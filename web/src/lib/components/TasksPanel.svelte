<script lang="ts">
	import { api } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { runAction, type Action } from '#lib/actions.js';
	import { DetailsPoller } from '#lib/details.svelte.js';
	import { duration } from '#lib/format.js';
	import { attempt } from '#lib/toast.svelte.js';
	import type { Capability, Pearl, ScheduledTask, TaskEvent } from '#lib/types.js';
	import { CalendarClock, Plus, Trash2, X } from '@lucide/svelte';

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

	const events: { id: TaskEvent; label: string }[] = [
		{ id: 'online', label: 'the bot gets into the game' },
		{ id: 'connect', label: 'the bot connects' },
		{ id: 'disconnect', label: 'the bot disconnects' },
		{ id: 'death', label: 'the bot dies' },
		{ id: 'playerConnect', label: 'a player joins the proxy' },
		{ id: 'playerDisconnect', label: 'a player leaves the proxy' }
	];

	function when(t: ScheduledTask) {
		if (t.type === 'event') {
			const label = events.find((e) => e.id === t.event)?.label ?? t.event;
			return `${t.repeat ? 'Every time' : 'Next time'} ${label}`;
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
		{ id: 'loadPearl', label: 'Load a stasis pearl', needs: 'PEARLS' },
		{ id: 'respawn', label: 'Respawn', needs: 'PLAY' },
		{ id: 'goto', label: 'Walk to coordinates', needs: 'PLAY' },
		{ id: 'gotoWaypoint', label: 'Walk to a waypoint', needs: 'PLAY' },
		{ id: 'stop', label: 'Stop moving', needs: 'PLAY' },
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

	let adding = $state(false);
	let name = $state('');
	let trigger = $state<'after' | 'every' | 'event'>('after');
	let amount = $state('30');
	let unit = $state<1 | 60 | 3600>(60);
	let event = $state<TaskEvent>('online');
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
		what = available[0]?.id ?? '';
		adding = true;
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
			default:
				return { action: what };
		}
	}

	const seconds = $derived(Math.round(Number(amount) * unit));
	const valid = $derived(
		/^[A-Za-z0-9_.-]{1,32}$/.test(name.trim()) &&
			!tasks.some((t) => t.id === name.trim()) &&
			(trigger === 'event' || (Number.isFinite(seconds) && seconds >= 1)) &&
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
		if (trigger === 'event') {
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
						<div class="mb-2 grid grid-cols-3 gap-1">
							{#each [['after', 'Once, later'], ['every', 'Repeat'], ['event', 'When…']] as [id, label]}
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
									{#each events as e}<option value={e.id}>When {e.label}</option>{/each}
								</select>
								<select class="input" bind:value={repeat} aria-label="How often">
									<option value={true}>Every time</option>
									<option value={false}>Only once</option>
								</select>
							</div>
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
						<select id="task-what" class="input" bind:value={what}>
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
							{:else if what === 'command'}
								<input class="input font-mono" placeholder="e.g. say hello" autocapitalize="off" spellcheck="false" bind:value={command} />
							{/if}
						</div>
					</div>

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
				<p class="px-4 py-6 text-sm text-zinc-500">Loading…</p>
			{:else if !tasks.length && !adding}
				<div class="flex flex-col items-center gap-2 px-4 py-10 text-center text-sm text-zinc-400">
					<CalendarClock size={28} class="text-zinc-600" />
					No tasks yet. For example: load your pearl in 30 minutes, or respawn every time the bot dies.
				</div>
			{:else}
				<ul class="divide-y divide-zinc-800">
					{#each tasks as t (t.id)}
						<li class="flex items-center gap-3 px-4 py-3">
							<div class="min-w-0 flex-1">
								<div class="flex items-baseline gap-2">
									<span class="truncate font-medium text-zinc-100">{t.id}</span>
									<span class="truncate text-xs text-zinc-400">{when(t)}</span>
								</div>
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
