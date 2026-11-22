<script lang="ts">
	import { PATHING, categoryOf, cloneStep, forEachLists, keyOf, modules, scopes } from '#lib/flow-blocks.js';
	import type { FlowStep } from '#lib/types.js';
	import {
		Anchor,
		ArrowDown,
		ArrowLeftRight,
		ArrowRightLeft,
		ArrowUpRight,
		Box,
		Crosshair,
		Eraser,
		Eye,
		Hammer,
		Hand,
		Hash,
		ListOrdered,
		MapPinOff,
		MapPinPlus,
		Package,
		PackageMinus,
		PackageOpen,
		PackagePlus,
		Pickaxe,
		Plane,
		RefreshCw,
		RotateCw,
		ShieldAlert,
		ShieldCheck,
		SquareMousePointer,
		Target,
		ToggleRight,
		UserCheck,
		UserMinus,
		Webhook,
		Workflow,
		ArrowUp,
		Bell,
		ClipboardList,
		Copy,
		Ellipsis,
		EyeOff,
		Footprints,
		GitBranch,
		GripVertical,
		HeartPulse,
		Hourglass,
		Infinity as InfinityIcon,
		ListRestart,
		MapPin,
		MessageSquare,
		MessageSquareLock,
		MousePointerClick,
		Navigation,
		OctagonX,
		Orbit,
		Plug,
		Plus,
		Radio,
		Repeat,
		Repeat1,
		Sparkles,
		Square,
		Tally5,
		Timer,
		TimerReset,
		Trash2,
		Unplug,
		UserX,
		Variable,
		Video,
		VideoOff
	} from '@lucide/svelte';
	import Condition from './Condition.svelte';
	import { SELECT, SLOT, editor } from './context.js';
	import EntityFilter from './EntityFilter.svelte';
	import StepList from './StepList.svelte';
	import TriggerFields from './TriggerFields.svelte';

	let { step, list, index }: { step: FlowStep; list: FlowStep[]; index: number } = $props();
	const ctx = editor();

	const cat = $derived(categoryOf(step));
	const key = $derived(keyOf(step));
	const icons: Record<string, typeof Timer> = {
		wait: Timer,
		waitFor: Hourglass,
		waitUntil: TimerReset,
		if: GitBranch,
		repeat: Repeat,
		repeatUntil: Repeat1,
		forever: InfinityIcon,
		stop: OctagonX,
		goto: Navigation,
		gotoWaypoint: MapPin,
		follow: Footprints,
		'stop-moving': Square,
		afkEnter: Orbit,
		afkLeave: Orbit,
		respawn: HeartPulse,
		clickRight: MousePointerClick,
		useItem: MousePointerClick,
		holdRight: MousePointerClick,
		holdLeft: MousePointerClick,
		clickStop: MousePointerClick,
		loadPearl: Sparkles,
		say: MessageSquare,
		whisper: MessageSquareLock,
		notify: Bell,
		log: ClipboardList,
		set: Variable,
		change: Tally5,
		broadcast: Radio,
		connect: Plug,
		disconnect: Unplug,
		requeue: ListRestart,
		kick: UserX,
		replayStart: Video,
		replayStop: VideoOff,
		try: ShieldAlert,
		forEach: ListOrdered,
		runFlow: Workflow,
		reconnect: RefreshCw,
		webhook: Webhook,
		elytra: Plane,
		near: Crosshair,
		getTo: Target,
		thisway: ArrowUpRight,
		lookAt: Eye,
		rotate: RotateCw,
		mine: Pickaxe,
		breakBlock: Hammer,
		placeBlock: Box,
		clearArea: Eraser,
		pickup: Hand,
		clickBlock: SquareMousePointer,
		clickEntity: SquareMousePointer,
		holdItem: Hand,
		dropItem: PackageMinus,
		hold: Hash,
		swap: ArrowLeftRight,
		drop: PackageMinus,
		clickSlot: SquareMousePointer,
		withdraw: PackageOpen,
		deposit: PackagePlus,
		closeContainer: Package,
		transfer: ArrowRightLeft,
		kickPlayer: UserX,
		makePlayer: UserCheck,
		makeSpectator: UserMinus,
		module: ToggleRight,
		afkKeep: Anchor,
		addWaypoint: MapPinPlus,
		deleteWaypoint: MapPinOff
	};
	const Icon = $derived(icons[key] ?? Square);
	const fixed: Record<string, string> = {
		stop: 'stop this flow',
		forever: 'repeat forever',
		'stop-moving': 'stop moving',
		afkEnter: 'go into the AFK zone',
		afkLeave: 'leave the AFK zone',
		respawn: 'respawn',
		clickRight: 'right click once',
		useItem: 'use the held item',
		holdRight: 'keep right clicking',
		holdLeft: 'keep left clicking',
		clickStop: 'stop clicking',
		connect: 'connect',
		disconnect: 'disconnect',
		requeue: 'requeue',
		kick: 'kick everyone off the proxy',
		replayStart: 'start recording a replay',
		replayStop: 'stop recording',
		withdraw: 'take everything from the open chest',
		deposit: 'put everything into the open chest',
		closeContainer: 'close the open chest',
		makeSpectator: 'turn the player into a spectator'
	};

	const running = $derived(ctx.live.step === step.id);
	const failed = $derived(!running && ctx.live.error === step.id);
	/** the lists inside a C-block; every one after the first gets a label bar */
	const inner = $derived(
		step.type === 'if'
			? [
					{ label: '', list: step.then ?? [] },
					{ label: 'else', list: step.else ?? [] }
				]
			: step.type === 'try'
				? [
						{ label: '', list: step.steps ?? [] },
						{ label: 'if it fails', list: step.catch ?? [] }
					]
				: step.type === 'repeat' || step.type === 'repeatUntil' || step.type === 'forever' || step.type === 'forEach'
					? [{ label: '', list: step.steps ?? [] }]
					: []
	);
	const isC = $derived(inner.length > 0);

	let menuOpen = $state(false);
	let menuEl: HTMLElement | undefined = $state();
	/** only the grip starts a drag, so text in the block's inputs stays selectable */
	let grabbed = $state(false);
	let dropAt = $state<'above' | 'below' | null>(null);

	function move(by: number) {
		const to = index + by;
		if (to < 0 || to >= list.length) return;
		const [item] = list.splice(index, 1);
		list.splice(to, 0, item);
		menuOpen = false;
	}
	function duplicate() {
		list.splice(index + 1, 0, cloneStep(step));
		menuOpen = false;
	}
	function remove() {
		list.splice(index, 1);
		menuOpen = false;
	}

	function dragOver(e: DragEvent) {
		if (!ctx.drag || ctx.drag.step === step || !ctx.canDrop(list)) return;
		e.preventDefault();
		e.stopPropagation();
		const box = (e.currentTarget as HTMLElement).getBoundingClientRect();
		// C-blocks are tall: only their top row counts, the inside has targets of its own
		const mid = isC ? box.top + 20 : box.top + box.height / 2;
		dropAt = e.clientY < mid ? 'above' : 'below';
	}

	const run = $derived(step.run ?? { action: '' });
	const num = (v: string) => (v.trim() === '' ? null : Number(v));
	/** a number, or a {value} that becomes one when the block runs */
	const nv = (v: string) => (v.trim() === '' ? null : v.includes('{') ? v.trim() : Number(v));
	const paths = $derived(step.type === 'action' && PATHING.has(run.action));
	const limits = [
		[30, '30 s'],
		[60, '1 min'],
		[300, '5 min'],
		[600, '10 min'],
		[1800, '30 min'],
		[3600, '1 hour']
	] as const;
	const hotbar = [1, 2, 3, 4, 5, 6, 7, 8, 9];
	const took = (ms: number) => (ms < 1000 ? `${ms} ms` : ms < 60_000 ? `${(ms / 1000).toFixed(1)} s` : `${Math.round(ms / 60_000)} min`);
	const slotHelp = 'Slot numbers: 9–35 the inventory, 36–44 the hotbar, 45 the off hand, 5–8 armour';
</script>

<svelte:window
	onpointerdown={(e) => {
		if (menuOpen && menuEl && !menuEl.contains(e.target as Node)) menuOpen = false;
	}}
	onpointerup={() => (grabbed = false)}
/>

{#snippet menu()}
	<div class="absolute top-1.5 right-1.5" bind:this={menuEl}>
		<button
			type="button"
			class="rounded-md p-1 text-white/70 hover:bg-black/20 hover:text-white"
			aria-label="Block options"
			aria-expanded={menuOpen}
			onclick={() => (menuOpen = !menuOpen)}><Ellipsis size={16} /></button
		>
		{#if menuOpen}
			<div class="absolute top-full right-0 z-20 mt-1 w-48 overflow-hidden rounded-lg border border-zinc-700 bg-zinc-900 py-1 text-sm font-normal text-zinc-200 shadow-xl">
				<button type="button" class="flex w-full items-center gap-2 px-3 py-1.5 hover:bg-zinc-800" onclick={() => ((menuOpen = false), ctx.pick(list, index + 1))}
					><Plus size={14} /> Add block below</button
				>
				<button type="button" class="flex w-full items-center gap-2 px-3 py-1.5 hover:bg-zinc-800 disabled:opacity-40" disabled={index === 0} onclick={() => move(-1)}
					><ArrowUp size={14} /> Move up</button
				>
				<button
					type="button"
					class="flex w-full items-center gap-2 px-3 py-1.5 hover:bg-zinc-800 disabled:opacity-40"
					disabled={index === list.length - 1}
					onclick={() => move(1)}><ArrowDown size={14} /> Move down</button
				>
				<button type="button" class="flex w-full items-center gap-2 px-3 py-1.5 hover:bg-zinc-800" onclick={duplicate}><Copy size={14} /> Duplicate</button>
				<button type="button" class="flex w-full items-center gap-2 px-3 py-1.5 hover:bg-zinc-800" onclick={() => ((step.off = !step.off), (menuOpen = false))}
					><EyeOff size={14} /> {step.off ? 'Turn on' : 'Skip this block'}</button
				>
				<button
					type="button"
					class="flex w-full items-center gap-2 px-3 py-1.5 text-left hover:bg-zinc-800"
					onclick={() => {
						if (step.keepGoing) delete step.keepGoing;
						else step.keepGoing = true;
						menuOpen = false;
					}}><ShieldCheck size={14} class="shrink-0" /> {step.keepGoing ? 'Stop the flow if it fails' : 'Keep going if it fails'}</button
				>
				<button type="button" class="flex w-full items-center gap-2 px-3 py-1.5 text-red-300 hover:bg-zinc-800" onclick={remove}><Trash2 size={14} /> Delete</button>
			</div>
		{/if}
	</div>
{/snippet}

{#snippet text(max: number, placeholder: string)}
	<input
		class="{SLOT} field-sizing-content max-w-full min-w-32"
		{placeholder}
		maxlength={max}
		aria-label="Message"
		bind:value={step.text}
		onfocus={() => ctx.focusText({ obj: step as unknown as Record<string, unknown>, key: 'text' })}
	/>
{/snippet}

{#snippet timeout()}
	<span>, give up after</span>
	<select class={SELECT} aria-label="Time limit" bind:value={step.timeout}>
		<option value={0}>never</option>
		<option value={10}>10 s</option>
		<option value={30}>30 s</option>
		<option value={60}>1 min</option>
		<option value={300}>5 min</option>
		<option value={1800}>30 min</option>
		<option value={3600}>1 hour</option>
	</select>
{/snippet}

{#snippet n(obj: Record<string, unknown>, k: string, placeholder: string, width = 'w-16')}
	<input
		class="{SLOT} {width} text-center"
		inputmode="numeric"
		{placeholder}
		aria-label={placeholder}
		value={obj[k] ?? ''}
		oninput={(e) => (obj[k] = nv(e.currentTarget.value))}
		onfocus={() => ctx.focusText({ obj, key: k, replace: true })}
	/>
{/snippet}

{#snippet xyz(obj: Record<string, unknown>, yOptional = false)}
	{@render n(obj, 'x', 'x')}
	{@render n(obj, 'y', yOptional ? 'y (any)' : 'y', 'w-14')}
	{@render n(obj, 'z', 'z')}
{/snippet}

{#snippet id(obj: Record<string, unknown>, k: string, placeholder: string, list = '', values = false)}
	<input
		class="{SLOT} field-sizing-content min-w-24 font-mono text-xs"
		{placeholder}
		maxlength="74"
		autocapitalize="off"
		spellcheck="false"
		aria-label={placeholder}
		list={list || undefined}
		value={obj[k] ?? ''}
		oninput={(e) => (obj[k] = e.currentTarget.value.trim())}
		onfocus={() => ctx.focusText(values ? { obj, key: k, replace: true } : null)}
	/>
{/snippet}

{#snippet player(obj: Record<string, unknown>, k: string, placeholder = 'name or {player}')}
	<input
		class="{SLOT} field-sizing-content min-w-24"
		{placeholder}
		maxlength="40"
		autocapitalize="off"
		spellcheck="false"
		aria-label="Player"
		value={obj[k] ?? ''}
		oninput={(e) => (obj[k] = e.currentTarget.value.trim() || null)}
		onfocus={() => ctx.focusText({ obj, key: k, replace: true })}
	/>
{/snippet}

{#snippet button(obj: Record<string, unknown>)}
	<select class={SELECT} aria-label="Mouse button" bind:value={obj.button}>
		<option value="right">right-click</option>
		<option value="left">left-click</option>
	</select>
{/snippet}

{#snippet limit(label: string)}
	<span>{label}</span>
	<select class={SELECT} aria-label="Time limit" bind:value={step.timeout}>
		{#each limits as [v, l]}<option value={v}>{l}</option>{/each}
	</select>
{/snippet}

{#snippet pathWait()}
	<select
		class="{SELECT} text-xs"
		aria-label="Wait for it"
		value={step.wait ?? 'no'}
		onchange={(e) => {
			const v = (e.currentTarget as HTMLSelectElement).value as 'no' | 'done' | 'for';
			step.wait = v;
			if (v === 'no') delete step.timeout;
			else if (!step.timeout) step.timeout = 600;
		}}
	>
		<option value="no">then go on</option>
		<option value="done">and wait until done</option>
		<option value="for">for a while, then stop</option>
	</select>
	{#if step.wait === 'done'}
		{@render limit('give up after')}
	{:else if step.wait === 'for'}
		{@render limit('stop after')}
	{/if}
{/snippet}

{#snippet scope()}
	<select class="{SELECT} text-xs" aria-label="Whose variable" bind:value={() => step.scope ?? 'flow', (v) => (step.scope = v)}>
		{#each scopes as o}<option value={o.value}>in {o.label}</option>{/each}
	</select>
{/snippet}

{#snippet varName()}
	<input
		class="{SLOT} field-sizing-content min-w-16 font-mono text-xs"
		placeholder="name"
		maxlength="24"
		autocapitalize="off"
		spellcheck="false"
		aria-label="Variable name"
		list="flow-vars"
		bind:value={step.name}
	/>
{/snippet}

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div
	class="relative {step.off ? 'opacity-45' : ''} {ctx.drag?.step === step ? 'opacity-40' : ''}"
	data-step={step.id}
	draggable={grabbed}
	ondragstart={(e) => {
		e.stopPropagation();
		e.dataTransfer?.setData('text/plain', step.id);
		if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move';
		ctx.startDrag(list, index);
	}}
	ondragend={() => ((grabbed = false), (dropAt = null), ctx.endDrag())}
	ondragover={dragOver}
	ondragleave={(e) => {
		if (!(e.currentTarget as HTMLElement).contains(e.relatedTarget as Node)) dropAt = null;
	}}
	ondrop={(e) => {
		if (!dropAt) return;
		e.preventDefault();
		e.stopPropagation();
		const at = dropAt === 'above' ? index : index + 1;
		dropAt = null;
		ctx.drop(list, at);
	}}
>
	{#if dropAt === 'above'}<div class="pointer-events-none absolute -top-1 right-0 left-0 h-0.5 rounded bg-white"></div>{/if}
	<div
		class="relative flex flex-wrap items-center gap-x-1.5 gap-y-1 py-2 pr-9 pl-3 text-sm font-medium text-white shadow-sm sm:pl-1 {cat.fill} {isC
			? 'rounded-t-lg rounded-br-lg'
			: 'rounded-lg'} {running ? 'ring-2 ring-white ring-offset-2 ring-offset-zinc-950' : failed ? 'ring-2 ring-red-400 ring-offset-2 ring-offset-zinc-950' : ''}"
	>
		<span class="hidden cursor-grab text-white/35 hover:text-white/80 sm:inline" title="Drag to move" onpointerdown={() => (grabbed = true)}
			><GripVertical size={14} /></span
		>
		<Icon size={15} strokeWidth={2.4} class="shrink-0 opacity-90" />
		{#if fixed[key]}
			<span>{fixed[key]}</span>
		{:else if step.type === 'wait'}
			<span>wait</span>
			<input class="{SLOT} w-14 text-center" inputmode="numeric" aria-label="Seconds" bind:value={step.seconds} />
			{#if step.upTo !== undefined}
				<span>to</span>
				<input class="{SLOT} w-14 text-center" inputmode="numeric" aria-label="Up to seconds" bind:value={step.upTo} />
				<span>seconds, at random</span>
				<button type="button" class="text-xs font-normal text-white/60 hover:text-white" onclick={() => delete step.upTo}>(exact)</button>
			{:else}
				<span>seconds</span>
				<button type="button" class="text-xs font-normal text-white/60 hover:text-white" onclick={() => (step.upTo = Number(step.seconds ?? 0) * 2 || 10)}
					>(random)</button
				>
			{/if}
		{:else if step.type === 'waitFor' && step.event}
			<span>wait until</span>
			<TriggerFields bind:trigger={step.event} waiting />
			{@render timeout()}
		{:else if step.type === 'waitUntil' && step.cond}
			<span>wait until</span>
			<Condition bind:cond={step.cond} />
			{@render timeout()}
		{:else if step.type === 'if' && step.cond}
			<span>if</span>
			<Condition bind:cond={step.cond} />
		{:else if step.type === 'repeatUntil' && step.cond}
			<span>repeat until</span>
			<Condition bind:cond={step.cond} />
		{:else if step.type === 'repeat'}
			<span>repeat</span>
			<input class="{SLOT} w-12 text-center" inputmode="numeric" aria-label="Times" bind:value={step.times} />
			<span>times</span>
		{:else if step.type === 'say'}
			<span>say</span>
			{@render text(256, 'Hi {player}!')}
		{:else if step.type === 'whisper'}
			<span>whisper to</span>
			<input
				class="{SLOT} field-sizing-content min-w-20"
				placeholder="name"
				maxlength="40"
				autocapitalize="off"
				spellcheck="false"
				aria-label="Whisper to"
				bind:value={step.player}
				onfocus={() => ctx.focusText(null)}
			/>
			{@render text(200, 'Hey!')}
		{:else if step.type === 'notify'}
			<span>notify me</span>
			{@render text(200, 'Something happened')}
		{:else if step.type === 'log'}
			<span>log</span>
			{@render text(200, 'A note for the run list')}
		{:else if step.type === 'set'}
			<span>set</span>
			{@render varName()}
			<span>to</span>
			<input
				class="{SLOT} field-sizing-content min-w-16"
				placeholder="value"
				maxlength="200"
				aria-label="Value"
				bind:value={step.value}
				onfocus={() => ctx.focusText({ obj: step as unknown as Record<string, unknown>, key: 'value' })}
			/>
			{@render scope()}
		{:else if step.type === 'change'}
			<span>change</span>
			{@render varName()}
			<span>by</span>
			<input class="{SLOT} w-14 text-center" inputmode="numeric" aria-label="By" bind:value={step.by} />
			{@render scope()}
		{:else if step.type === 'broadcast'}
			<span>broadcast</span>
			<input
				class="{SLOT} field-sizing-content min-w-16"
				placeholder="name"
				maxlength="32"
				aria-label="Broadcast name"
				bind:value={step.name}
				onfocus={() => ctx.focusText(null)}
			/>
			<span>to</span>
			<select class={SELECT} aria-label="Which bots" bind:value={() => step.to ?? '', (v) => (step.to = v)}>
				<option value="">this bot’s flows</option>
				<option value="all">every bot I control</option>
				{#if ctx.bots.length}
					<optgroup label="One bot">
						{#each ctx.bots as b (b.id)}<option value={b.id}>{b.name}</option>{/each}
					</optgroup>
				{/if}
				{#if step.to && step.to !== 'all' && !ctx.bots.some((b) => b.id === step.to)}<option value={step.to}>a bot you can’t control</option>{/if}
			</select>
		{:else if key === 'goto'}
			<span>walk to</span>
			{@render xyz(run, true)}
		{:else if key === 'gotoWaypoint'}
			<span>walk to waypoint</span>
			{#if ctx.waypoints.length}
				<select class={SELECT} aria-label="Waypoint" bind:value={run.waypoint}>
					<option value="" disabled>pick one</option>
					{#each ctx.waypoints as w}<option value={w}>{w}</option>{/each}
					{#if run.waypoint && !ctx.waypoints.includes(String(run.waypoint))}<option value={run.waypoint}>{run.waypoint}</option>{/if}
				</select>
			{:else}
				<input class="{SLOT} field-sizing-content min-w-20" placeholder="name" maxlength="32" aria-label="Waypoint" bind:value={run.waypoint} />
			{/if}
		{:else if key === 'follow'}
			<span>follow</span>
			{@render player(run, 'player', 'nearest player')}
		{:else if step.type === 'try'}
			<span>try</span>
		{:else if step.type === 'forEach'}
			<span>for each</span>
			<select class={SELECT} aria-label="What to go through" bind:value={step.list}>
				{#each forEachLists as l}<option value={l.value}>{l.label}</option>{/each}
			</select>
			<span class="text-xs font-normal text-white/60">as {'{each}'}</span>
		{:else if step.type === 'runFlow'}
			<span>run the flow</span>
			<select class={SELECT} aria-label="Flow" bind:value={step.flow}>
				<option value="" disabled>{ctx.flows.length ? 'pick one' : 'no other flows yet'}</option>
				{#each ctx.flows as f (f.id)}<option value={f.id}>{f.name}</option>{/each}
			</select>
			<select class={SELECT} aria-label="Wait for it" bind:value={step.wait}>
				<option value={true}>and wait for it</option>
				<option value={false}>and go on</option>
			</select>
		{:else if step.type === 'webhook'}
			<select class={SELECT} aria-label="Method" bind:value={step.method}>
				<option value="POST">send to</option>
				<option value="GET">open</option>
			</select>
			<input
				class="{SLOT} field-sizing-content max-w-full min-w-48 font-mono text-xs"
				placeholder="https://example.com/hook"
				maxlength="500"
				autocapitalize="off"
				spellcheck="false"
				aria-label="Web address"
				bind:value={step.url}
				onfocus={() => ctx.focusText({ obj: step as unknown as Record<string, unknown>, key: 'url' })}
			/>
			{#if step.method === 'POST'}
				<span>the text</span>
				<input
					class="{SLOT} field-sizing-content max-w-full min-w-32 font-mono text-xs"
					placeholder={'{"text": "{player}"}'}
					maxlength="2000"
					aria-label="Body"
					bind:value={step.body}
					onfocus={() => ctx.focusText({ obj: step as unknown as Record<string, unknown>, key: 'body' })}
				/>
			{/if}
		{:else if step.type === 'reconnect'}
			<span>reconnect after</span>
			<input class="{SLOT} w-14 text-center" inputmode="numeric" aria-label="Seconds" bind:value={step.seconds} />
			{#if step.upTo !== undefined}
				<span>to</span>
				<input class="{SLOT} w-14 text-center" inputmode="numeric" aria-label="Up to seconds" bind:value={step.upTo} />
				<span>seconds</span>
				<button type="button" class="text-xs font-normal text-white/60 hover:text-white" onclick={() => delete step.upTo}>(exact)</button>
			{:else}
				<span>seconds</span>
				<button type="button" class="text-xs font-normal text-white/60 hover:text-white" onclick={() => (step.upTo = Number(step.seconds ?? 0) * 2 || 60)}
					>(random)</button
				>
			{/if}
			<span>, wait until in the game for</span>
			<select class={SELECT} aria-label="Time limit" bind:value={step.timeout}>
				<option value={0}>no time (don’t wait)</option>
				{#each limits as [v, l]}<option value={v}>{l}</option>{/each}
			</select>
		{:else if step.type === 'connect'}
			<span>connect</span>
			<select
				class="{SELECT} text-xs"
				aria-label="Wait until in the game"
				value={step.wait ? 'yes' : 'no'}
				onchange={(e) => {
					if ((e.currentTarget as HTMLSelectElement).value === 'yes') [step.wait, step.timeout] = [true, step.timeout || 120];
					else (delete step.wait, delete step.timeout);
				}}
			>
				<option value="no">then go on</option>
				<option value="yes">and wait until in the game</option>
			</select>
			{#if step.wait}{@render limit('give up after')}{/if}
		{:else if step.type === 'holdItem'}
			<span>hold</span>
			{@render id(step, 'item', 'item id', 'flow-items')}
		{:else if step.type === 'dropItem'}
			<span>drop</span>
			<select class={SELECT} aria-label="How many" bind:value={step.all}>
				<option value={true}>every</option>
				<option value={false}>one stack of</option>
			</select>
			{@render id(step, 'item', 'item id', 'flow-items')}
		{:else if step.type === 'lookAt'}
			<span>look at</span>
			<select
				class={SELECT}
				aria-label="What to look at"
				value={step.target}
				onchange={(e) => {
					const v = (e.currentTarget as HTMLSelectElement).value as 'entity' | 'position';
					step.target = v;
					if (v === 'position') [step.x, step.y, step.z] = [step.x ?? 0, step.y ?? 64, step.z ?? 0];
					else [step.what, step.radius] = [step.what ?? 'anyone', step.radius ?? 16];
				}}
			>
				<option value="entity">the nearest</option>
				<option value="position">the block at</option>
			</select>
			{#if step.target === 'position'}
				{@render xyz(step)}
			{:else}
				<EntityFilter filter={step} />
			{/if}
		{:else if key === 'elytra'}
			<span>fly with the elytra to</span>
			{@render xyz(run, true)}
		{:else if key === 'near'}
			<span>walk within</span>
			{@render n(run, 'range', 'blocks', 'w-12')}
			<span>blocks of</span>
			{@render xyz(run)}
		{:else if key === 'getTo'}
			<span>walk to the nearest</span>
			{@render id(run, 'block', 'block id', 'flow-blocks')}
		{:else if key === 'thisway'}
			<span>walk</span>
			{@render n(run, 'blocks', 'blocks')}
			<span>blocks straight ahead</span>
		{:else if key === 'rotate'}
			<span>turn to yaw</span>
			{@render n(run, 'yaw', 'yaw', 'w-14')}
			<span>pitch</span>
			{@render n(run, 'pitch', 'pitch', 'w-14')}
			<span class="text-xs font-normal text-white/60">(yaw 0 is south, 90 west; pitch 90 looks down)</span>
		{:else if key === 'mine'}
			<span>mine every</span>
			{@render id(run, 'block', 'block id', 'flow-blocks')}
			<span>nearby</span>
		{:else if key === 'breakBlock'}
			<span>break the block at</span>
			{@render xyz(run)}
		{:else if key === 'placeBlock'}
			<span>place</span>
			{@render id(run, 'item', 'block id', 'flow-blocks')}
			<span>at</span>
			{@render xyz(run)}
		{:else if key === 'clearArea'}
			<span>clear everything from</span>
			{@render xyz(run.from as Record<string, unknown>)}
			<span>to</span>
			{@render xyz(run.to as Record<string, unknown>)}
		{:else if key === 'pickup'}
			<span>pick up</span>
			<input
				class="{SLOT} field-sizing-content min-w-24 font-mono text-xs"
				placeholder="every item"
				maxlength="74"
				autocapitalize="off"
				spellcheck="false"
				aria-label="Item"
				list="flow-items"
				value={run.item ?? ''}
				oninput={(e) => (run.item = e.currentTarget.value.trim() || null)}
			/>
			<span>nearby</span>
		{:else if key === 'clickBlock'}
			<span>walk to and</span>
			{@render button(run)}
			{#if run.waypoint !== undefined}
				<span>the block at waypoint</span>
				{@render id(run, 'waypoint', 'waypoint', 'flow-waypoints')}
				<button
					type="button"
					class="text-xs font-normal text-white/60 hover:text-white"
					onclick={() => {
						delete run.waypoint;
						[run.x, run.y, run.z] = [0, 64, 0];
					}}>(position)</button
				>
			{:else}
				<span>the block at</span>
				{@render xyz(run)}
				<button
					type="button"
					class="text-xs font-normal text-white/60 hover:text-white"
					onclick={() => {
						delete run.x, delete run.y, delete run.z;
						run.waypoint = ctx.waypoints[0] ?? '';
					}}>(waypoint)</button
				>
			{/if}
		{:else if key === 'clickEntity'}
			<span>walk to and</span>
			{@render button(run)}
			<span>the nearest</span>
			{@render id(run, 'entity', 'mob id', 'flow-mobs')}
		{:else if key === 'hold'}
			<span>select hotbar slot</span>
			<select class={SELECT} aria-label="Hotbar slot" value={Number(run.slot) - 35} onchange={(e) => (run.slot = Number((e.currentTarget as HTMLSelectElement).value) + 35)}>
				{#each hotbar as h}<option value={h}>{h}</option>{/each}
			</select>
		{:else if key === 'swap'}
			<span title={slotHelp}>swap slot</span>
			{@render n(run, 'from', 'slot', 'w-12')}
			<span>with slot</span>
			{@render n(run, 'to', 'slot', 'w-12')}
		{:else if key === 'drop'}
			<span>drop</span>
			<select class={SELECT} aria-label="How many" bind:value={run.stack}>
				<option value={true}>the stack</option>
				<option value={false}>one item</option>
			</select>
			<span title={slotHelp}>from slot</span>
			{@render n(run, 'slot', 'slot', 'w-12')}
		{:else if key === 'clickSlot'}
			{@render button(run)}
			<span title={slotHelp}>slot</span>
			{@render n(run, 'slot', 'slot', 'w-12')}
			<span class="text-xs font-normal text-white/60">(of the open chest, or the inventory)</span>
		{:else if key === 'transfer'}
			<span>move to the server</span>
			<input
				class="{SLOT} field-sizing-content min-w-32 font-mono text-xs"
				placeholder="play.example.com"
				maxlength="253"
				autocapitalize="off"
				spellcheck="false"
				aria-label="Server address"
				bind:value={run.address}
			/>
			{@render n(run, 'port', 'port', 'w-16')}
		{:else if key === 'kickPlayer'}
			<span>kick</span>
			{@render player(run, 'player')}
			<span>off the proxy</span>
		{:else if key === 'makePlayer'}
			<span>let</span>
			{@render player(run, 'player')}
			<span>take over</span>
		{:else if key === 'module'}
			<span>switch</span>
			<select class={SELECT} aria-label="Module" bind:value={run.module}>
				{#each modules as m}<option value={m.value}>{m.label}</option>{/each}
			</select>
			<select class={SELECT} aria-label="On or off" bind:value={run.on}>
				<option value={true}>on</option>
				<option value={false}>off</option>
			</select>
		{:else if key === 'afkKeep'}
			<select class={SELECT} aria-label="Keep or not" bind:value={run.keep}>
				<option value={true}>keep</option>
				<option value={false}>stop keeping</option>
			</select>
			<span>the bot in the AFK zone</span>
		{:else if key === 'addWaypoint'}
			<span>save waypoint</span>
			{@render id(run, 'id', 'name')}
			<span>at</span>
			{@render xyz(run)}
		{:else if key === 'deleteWaypoint'}
			<span>delete waypoint</span>
			{@render id(run, 'id', 'name', 'flow-waypoints')}
		{:else if key === 'loadPearl'}
			<span>load</span>
			<select class={SELECT} aria-label="Pearl" bind:value={run.pearl}>
				<option value="" disabled>{ctx.pearls.length ? 'pick a pearl' : 'no pearls yet'}</option>
				{#each ctx.pearls as p}<option value={p.id}>{p.id}'s pearl</option>{/each}
			</select>
		{:else}
			<span class="font-mono text-xs">{run.action}</span>
		{/if}
		{#if paths}{@render pathWait()}{/if}
		{#if ctx.live.timings?.[step.id] !== undefined && !isC}
			<span class="ml-auto text-xs font-normal text-white/55 tabular-nums" title="How long this block took in the last run">{took(ctx.live.timings[step.id])}</span>
		{/if}
		{#if step.keepGoing}
			<span class="inline-flex items-center gap-1 rounded bg-black/20 px-1.5 py-px text-xs font-normal text-white/80" title="If this block fails, the flow goes on with {'{error}'}"
				><ShieldCheck size={11} /> keeps going if it fails</span
			>
		{/if}
		{@render menu()}
	</div>

	{#each inner as part, i}
		{#if i > 0}
			<div class="rounded-r-lg px-3 py-1 text-xs font-semibold text-white/90 {cat.fill}">{part.label}</div>
		{/if}
		<div class="flex">
			<div class="w-3 shrink-0 {cat.fill}"></div>
			<div class="min-w-0 flex-1 py-1.5 pl-2"><StepList list={part.list} /></div>
		</div>
	{/each}
	{#if isC}<div class="h-3 w-24 rounded-r-lg rounded-bl-lg {cat.fill}"></div>{/if}

	{#if failed && ctx.live.message}
		<p class="mt-1 text-xs text-red-300">{ctx.live.message}</p>
	{/if}
	{#if dropAt === 'below'}<div class="pointer-events-none absolute right-0 -bottom-1 left-0 h-0.5 rounded bg-white"></div>{/if}
</div>
