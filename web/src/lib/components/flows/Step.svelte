<script lang="ts">
	import { categoryOf, cloneStep, keyOf } from '#lib/flow-blocks.js';
	import type { FlowStep } from '#lib/types.js';
	import {
		ArrowDown,
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
		replayStop: VideoOff
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
		replayStop: 'stop recording'
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
			: step.type === 'repeat' || step.type === 'repeatUntil' || step.type === 'forever'
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
		{:else if step.type === 'change'}
			<span>change</span>
			{@render varName()}
			<span>by</span>
			<input class="{SLOT} w-14 text-center" inputmode="numeric" aria-label="By" bind:value={step.by} />
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
			<input class="{SLOT} w-16 text-center" inputmode="numeric" placeholder="x" aria-label="X" value={run.x ?? ''} oninput={(e) => (run.x = num(e.currentTarget.value))} />
			<input class="{SLOT} w-14 text-center" inputmode="numeric" placeholder="y" aria-label="Y" value={run.y ?? ''} oninput={(e) => (run.y = num(e.currentTarget.value))} />
			<input class="{SLOT} w-16 text-center" inputmode="numeric" placeholder="z" aria-label="Z" value={run.z ?? ''} oninput={(e) => (run.z = num(e.currentTarget.value))} />
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
			<input
				class="{SLOT} field-sizing-content min-w-28"
				placeholder="nearest player"
				maxlength="16"
				autocapitalize="off"
				spellcheck="false"
				aria-label="Player"
				value={run.player ?? ''}
				oninput={(e) => (run.player = e.currentTarget.value.trim() || null)}
			/>
		{:else if key === 'loadPearl'}
			<span>load</span>
			<select class={SELECT} aria-label="Pearl" bind:value={run.pearl}>
				<option value="" disabled>{ctx.pearls.length ? 'pick a pearl' : 'no pearls yet'}</option>
				{#each ctx.pearls as p}<option value={p.id}>{p.id}'s pearl</option>{/each}
			</select>
		{:else}
			<span class="font-mono text-xs">{run.action}</span>
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
