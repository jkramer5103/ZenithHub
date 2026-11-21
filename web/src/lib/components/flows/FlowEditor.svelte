<script lang="ts">
	import { ApiError, api } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { copyText } from '#lib/clipboard.js';
	import { HAT, always, contains, copyFlow, countSteps, makeStep, shareCode, triggerDef, variablesIn, type BlockDef } from '#lib/flow-blocks.js';
	import { attempt, toasts } from '#lib/toast.svelte.js';
	import type { Capability, Flow, FlowStep, Pearl } from '#lib/types.js';
	import { ArrowLeft, Copy, CopyPlus, Ellipsis, Play, Redo2, Send, Square, Trash2, Undo2, Zap } from '@lucide/svelte';
	import Modal from '../Modal.svelte';
	import Switch from '../settings/Switch.svelte';
	import BlockPicker from './BlockPicker.svelte';
	import { SELECT, provideEditor } from './context.js';
	import FlowActivity from './FlowActivity.svelte';
	import StepList from './StepList.svelte';
	import TriggerFields from './TriggerFields.svelte';

	let {
		instanceId,
		capabilities,
		running,
		initial,
		isNew,
		onclose,
		onopen
	}: {
		instanceId: string;
		capabilities: Capability[];
		running: boolean;
		initial: Flow;
		isNew: boolean;
		onclose: (changed: boolean) => void;
		/** opens another flow, e.g. a duplicate */
		onopen: (flow: Flow) => void;
	} = $props();

	// the editor is mounted per flow, so the first value is the one to edit
	// svelte-ignore state_referenced_locally
	let flow = $state<Flow>({ mode: 'ignore', ...JSON.parse(JSON.stringify(initial)) });
	// svelte-ignore state_referenced_locally
	let saved = $state(isNew ? '' : snapshot(initial));
	let everSaved = $state(false);
	const dirty = $derived(snapshot(flow) !== saved);
	let busy = $state(false);

	/** what the user edits; also what undo goes back through */
	function snapshot(f: Flow) {
		const { name, enabled, cooldown, mode, trigger, steps } = f;
		return JSON.stringify({ name, enabled, cooldown, mode: mode ?? 'ignore', trigger, steps });
	}
	function apply(json: string) {
		Object.assign(flow, JSON.parse(json));
	}

	// --- undo and redo: each pause in typing is a step ---
	let past = $state<string[]>([]);
	let future = $state<string[]>([]);
	// svelte-ignore state_referenced_locally
	let current = $state(snapshot(flow));
	$effect(() => {
		const now = snapshot(flow);
		if (now === current) return;
		const t = setTimeout(commit, 400);
		return () => clearTimeout(t);
	});
	function commit() {
		const now = snapshot(flow);
		if (now === current) return;
		past = [...past.slice(-99), current];
		future = [];
		current = now;
	}
	function undo() {
		commit();
		const prev = past.at(-1);
		if (prev === undefined) return;
		past = past.slice(0, -1);
		future = [...future, current];
		current = prev;
		apply(prev);
	}
	function redo() {
		const next = future.at(-1);
		if (next === undefined) return;
		future = future.slice(0, -1);
		past = [...past, current];
		current = next;
		apply(next);
	}
	function onkeydown(e: KeyboardEvent) {
		if (!(e.ctrlKey || e.metaKey) || e.altKey) return;
		const k = e.key.toLowerCase();
		if (k === 's') {
			e.preventDefault();
			if (dirty && !busy) void save();
			return;
		}
		// text fields keep their own undo
		const t = e.target as HTMLElement;
		if (t.matches('input:not([type=checkbox]), textarea')) return;
		if (k === 'z' && !e.shiftKey) (e.preventDefault(), undo());
		else if ((k === 'z' && e.shiftKey) || k === 'y') (e.preventDefault(), redo());
	}

	// --- live: the running block, or where the last run stopped ---
	const live = $state<{ step?: string; error?: string; message?: string }>({
		// svelte-ignore state_referenced_locally
		step: initial.running?.step,
		// svelte-ignore state_referenced_locally
		error: initial.last && !initial.last.ok ? initial.last.step : undefined,
		// svelte-ignore state_referenced_locally
		message: initial.last?.message && 'Last run stopped here: ' + initial.last.message
	});
	// svelte-ignore state_referenced_locally
	let isRunning = $state(!!initial.running);
	$effect(() =>
		app.onFlow((id, e) => {
			if (id !== instanceId || e.flowId !== flow.id) return;
			if (e.state === 'started') {
				isRunning = true;
				live.error = live.message = undefined;
			} else if (e.state === 'step') live.step = e.step;
			else if (e.state === 'done' || e.state === 'error') {
				isRunning = false;
				live.step = undefined;
				if (e.state === 'error') [live.error, live.message] = [e.step, 'Last run stopped here: ' + e.message];
			}
		})
	);
	function show(step: string, message: string) {
		[live.error, live.message] = [step, message];
		const el = document.querySelector(`[data-step="${CSS.escape(step)}"]`);
		el?.scrollIntoView({ block: 'center', behavior: 'smooth' });
	}

	let waypoints = $state<string[]>([]);
	let pearls = $state<Pearl[]>([]);
	$effect(() => {
		api.get<{ waypoints?: { id: string }[] }>(`/instances/${instanceId}/details`).then(
			(d) => (waypoints = (d.waypoints ?? []).map((w) => w.id)),
			() => {}
		);
		if (capabilities.includes('PEARLS')) api.get<Pearl[]>(`/instances/${instanceId}/pearls`).then((p) => (pearls = p), () => {});
	});

	// --- the editor context every block reads ---
	let picking = $state<{ list: FlowStep[]; index: number } | null>(null);
	let drag = $state<{ list: FlowStep[]; index: number; step: FlowStep } | null>(null);
	let textTarget: { obj: Record<string, unknown>; key: string } | null = null;
	provideEditor({
		get capabilities() {
			return capabilities;
		},
		get waypoints() {
			return waypoints;
		},
		get pearls() {
			return pearls;
		},
		live,
		pick: (list, index) => (picking = { list, index }),
		focusText: (t) => (textTarget = t),
		get drag() {
			return drag;
		},
		startDrag: (list, index) => (drag = { list, index, step: list[index] }),
		endDrag: () => (drag = null),
		canDrop: (list) => !!drag && !contains(drag.step, list),
		drop: (list, at) => {
			if (!drag || contains(drag.step, list)) return;
			const from = drag.list.indexOf(drag.step);
			if (from < 0) return;
			const [item] = drag.list.splice(from, 1);
			list.splice(drag.list === list && from < at ? at - 1 : at, 0, item);
			drag = null;
		}
	});

	function insert(def: BlockDef) {
		if (!picking) return;
		picking.list.splice(picking.index, 0, makeStep(def));
		picking = null;
	}

	function addValue(name: string) {
		if (!textTarget) {
			toasts.show('alert', 'Click into a message first, then on a value.');
			return;
		}
		const current = String(textTarget.obj[textTarget.key] ?? '');
		textTarget.obj[textTarget.key] = (current && !current.endsWith(' ') ? current + ' ' : current) + `{${name}}`;
	}

	const gives = $derived(triggerDef(flow.trigger.kind).gives);
	const vars = $derived(variablesIn(flow.steps));
	const chips = $derived([...new Set([...gives, ...vars, ...always])]);
	const blockCount = $derived(countSteps(flow.steps));
	const timed = $derived(['manual', 'every', 'daily'].includes(flow.trigger.kind));
	// a flow that connects first can run while the bot is offline
	const connects = $derived(flow.steps.some((s) => s.type === 'connect' && !s.off));

	async function save() {
		busy = true;
		let ok = false;
		try {
			const result = await api.put<Flow>(`/instances/${instanceId}/flows/${flow.id}`, JSON.parse(snapshot(flow)));
			flow.ownerName = result.ownerName;
			saved = snapshot(flow);
			everSaved = true;
			live.error = live.message = undefined;
			toasts.success('Flow saved');
			ok = true;
		} catch (e) {
			const step = e instanceof ApiError ? e.data?.step : undefined;
			if (typeof step === 'string') show(step, e instanceof Error ? e.message : String(e));
			toasts.error(e);
		}
		busy = false;
		return ok;
	}

	async function runNow() {
		if (dirty && !(await save())) return;
		await attempt(() => api.post(`/instances/${instanceId}/flows/${flow.id}/run`));
	}

	async function stopRun() {
		await attempt(() => api.post(`/instances/${instanceId}/flows/${flow.id}/stop`));
	}

	async function remove() {
		menuOpen = false;
		if (!isNew && !confirm(`Delete the flow “${flow.name}”?`)) return;
		if (!isNew || everSaved) await attempt(() => api.del(`/instances/${instanceId}/flows/${flow.id}`), 'Flow deleted');
		onclose(true);
	}

	function back() {
		if (dirty && !confirm('Leave without saving your changes?')) return;
		onclose(!isNew || everSaved);
	}

	// --- sharing ---
	let menuOpen = $state(false);
	let menuEl: HTMLElement | undefined = $state();
	let copyingTo = $state(false);
	const others = $derived((app.overview?.instances ?? []).filter((i) => i.id !== instanceId && i.capabilities.includes('CONTROL')));

	async function copyCode() {
		menuOpen = false;
		if (await copyText(shareCode(flow))) toasts.success('Flow code copied. Paste it under Import on any instance.');
		else toasts.error('Couldn’t copy');
	}
	function duplicate() {
		menuOpen = false;
		if (dirty && !confirm('Duplicate without your unsaved changes?')) return;
		onopen(copyFlow(JSON.parse(saved || snapshot(flow))));
	}
	async function copyTo(id: string, name: string) {
		const copy = copyFlow(flow, flow.name);
		if (await attempt(() => api.put(`/instances/${id}/flows/${copy.id}`, copy), `Copied to ${name}, switched off`)) copyingTo = false;
	}

	const items = ['totem_of_undying', 'ender_pearl', 'golden_apple', 'enchanted_golden_apple', 'end_crystal', 'obsidian', 'experience_bottle', 'firework_rocket'];
</script>

<svelte:window
	{onkeydown}
	onpointerdown={(e) => {
		if (menuOpen && menuEl && !menuEl.contains(e.target as Node)) menuOpen = false;
	}}
/>

<datalist id="flow-vars">{#each vars as v}<option value={v}></option>{/each}</datalist>
<datalist id="flow-items">{#each items as i}<option value={i}></option>{/each}</datalist>

<div class="mx-auto w-full max-w-3xl">
	<!-- header: name, on/off, run and save -->
	<div class="mb-3 flex flex-wrap items-center gap-2">
		<button class="btn-ghost p-2" aria-label="Back to flows" onclick={back}><ArrowLeft size={18} /></button>
		<input
			class="min-w-0 flex-1 rounded-lg border border-transparent bg-transparent px-2 py-1.5 text-lg font-semibold text-zinc-100 outline-none hover:border-zinc-800 focus:border-emerald-600"
			maxlength="60"
			aria-label="Flow name"
			bind:value={flow.name}
		/>
		<div class="flex w-full items-center justify-end gap-1.5 sm:w-auto">
			<label class="mr-auto flex items-center gap-2 text-sm text-zinc-400 sm:mr-1">
				<Switch checked={flow.enabled} label="Flow on" onchange={(v) => (flow.enabled = v)} />
				<span>{flow.enabled ? 'On' : 'Off'}</span>
			</label>
			<button class="btn-ghost hidden p-1.5 sm:inline-flex" aria-label="Undo" title="Undo (Ctrl+Z)" disabled={!past.length && snapshot(flow) === current} onclick={undo}
				><Undo2 size={16} /></button
			>
			<button class="btn-ghost hidden p-1.5 sm:inline-flex" aria-label="Redo" title="Redo (Ctrl+Shift+Z)" disabled={!future.length} onclick={redo}><Redo2 size={16} /></button>
			{#if isRunning}
				<button class="btn-secondary py-1.5" onclick={stopRun}><Square size={14} /> Stop</button>
			{:else}
				<button
					class="btn-secondary py-1.5"
					disabled={(!running && !connects) || busy}
					title={running || connects ? 'Run it now' : 'Connect the instance first, or start the flow with a “connect” block'}
					onclick={runNow}><Play size={14} /> Run</button
				>
			{/if}
			<button class="btn-primary py-1.5" disabled={busy || !dirty} title="Save (Ctrl+S)" onclick={save}>{dirty ? 'Save' : 'Saved'}</button>
			<div class="relative" bind:this={menuEl}>
				<button class="btn-ghost p-1.5" aria-label="More" aria-expanded={menuOpen} onclick={() => (menuOpen = !menuOpen)}><Ellipsis size={16} /></button>
				{#if menuOpen}
					<div class="absolute top-full right-0 z-30 mt-1 w-56 overflow-hidden rounded-lg border border-zinc-700 bg-zinc-900 py-1 text-sm text-zinc-200 shadow-xl">
						<!-- on phones undo and redo live here, the header has no room -->
						<button
							type="button"
							class="flex w-full items-center gap-2 px-3 py-1.5 hover:bg-zinc-800 disabled:opacity-40 sm:hidden"
							disabled={!past.length && snapshot(flow) === current}
							onclick={() => ((menuOpen = false), undo())}><Undo2 size={14} /> Undo</button
						>
						<button
							type="button"
							class="flex w-full items-center gap-2 px-3 py-1.5 hover:bg-zinc-800 disabled:opacity-40 sm:hidden"
							disabled={!future.length}
							onclick={() => ((menuOpen = false), redo())}><Redo2 size={14} /> Redo</button
						>
						<button type="button" class="flex w-full items-center gap-2 px-3 py-1.5 hover:bg-zinc-800" onclick={copyCode}><Copy size={14} /> Copy flow code</button>
						{#if !isNew || everSaved}
							<button type="button" class="flex w-full items-center gap-2 px-3 py-1.5 hover:bg-zinc-800" onclick={duplicate}><CopyPlus size={14} /> Duplicate</button>
						{/if}
						{#if others.length}
							<button type="button" class="flex w-full items-center gap-2 px-3 py-1.5 hover:bg-zinc-800" onclick={() => ((menuOpen = false), (copyingTo = true))}
								><Send size={14} /> Copy to another instance</button
							>
						{/if}
						<button type="button" class="flex w-full items-center gap-2 px-3 py-1.5 text-red-300 hover:bg-zinc-800" onclick={remove}
							><Trash2 size={14} /> {isNew && !everSaved ? 'Discard' : 'Delete flow'}</button
						>
					</div>
				{/if}
			</div>
		</div>
	</div>

	<!-- the blocks, on a dotted board -->
	<div
		class="rounded-xl border border-zinc-800 bg-zinc-950 bg-[radial-gradient(circle,rgb(39_39_42)_1px,transparent_1px)] [background-size:18px_18px] p-2 sm:p-5"
	>
		<div class="flex max-w-xl flex-col gap-1">
			<div
				class="rounded-t-2xl rounded-b-lg px-3 pt-2.5 pb-2 text-sm font-medium text-white shadow-sm {HAT.fill} {live.error === 'trigger'
					? 'ring-2 ring-red-400 ring-offset-2 ring-offset-zinc-950'
					: ''}"
				data-step="trigger"
			>
				<div class="flex flex-wrap items-center gap-x-1.5 gap-y-1">
					<Zap size={15} strokeWidth={2.4} class="shrink-0" />
					<span>When</span>
					<TriggerFields bind:trigger={flow.trigger} />
				</div>
				<div class="mt-1.5 flex flex-wrap items-center gap-1 text-xs font-normal text-white/75">
					Use in messages:
					{#each chips as g (g)}
						<button
							type="button"
							class="rounded px-1.5 py-px font-mono hover:bg-black/40 {vars.includes(g)
								? 'bg-lime-800 text-white'
								: gives.includes(g)
									? 'bg-black/30 text-white'
									: 'bg-black/15 text-white/70'}"
							title={vars.includes(g) ? 'A variable of this flow' : gives.includes(g) ? 'From the trigger' : 'From the bot'}
							onmousedown={(e) => e.preventDefault()}
							onclick={() => addValue(g)}>{`{${g}}`}</button
						>
					{/each}
				</div>
				{#if live.error === 'trigger' && live.message}<p class="mt-1 text-xs text-red-200">{live.message}</p>{/if}
			</div>
			<StepList list={flow.steps} />
		</div>
	</div>

	<!-- the few settings around the blocks -->
	<div class="mt-3 flex flex-wrap items-center gap-x-4 gap-y-2 text-sm text-zinc-400">
		{#if !timed}
			<label class="flex items-center gap-2">
				At most once every
				<select class="{SELECT} bg-zinc-900 text-zinc-200" bind:value={flow.cooldown}>
					<option value={0}>time</option>
					<option value={3}>3 seconds</option>
					<option value={10}>10 seconds</option>
					<option value={30}>30 seconds</option>
					<option value={60}>minute</option>
					<option value={300}>5 minutes</option>
					<option value={3600}>hour</option>
				</select>
			</label>
		{/if}
		<label class="flex items-center gap-2">
			If it’s still running:
			<select class="{SELECT} bg-zinc-900 text-zinc-200" bind:value={flow.mode}>
				<option value="ignore">let it finish</option>
				<option value="restart">start over</option>
			</select>
		</label>
		<span class="text-xs text-zinc-500">
			{blockCount}
			{blockCount === 1 ? 'block' : 'blocks'}{flow.ownerName ? ` · runs with ${flow.ownerName === app.user?.username ? 'your' : `${flow.ownerName}’s`} permissions` : ''}
		</span>
	</div>

	{#if !isNew || everSaved}
		<FlowActivity {instanceId} flowId={flow.id} onshow={(step, message) => show(step, 'This run stopped here: ' + message)} />
	{/if}
</div>

{#if picking}
	<BlockPicker {capabilities} onpick={insert} onclose={() => (picking = null)} />
{/if}

{#if copyingTo}
	<Modal title="Copy “{flow.name}” to…" onclose={() => (copyingTo = false)}>
		<p class="mb-3 text-sm text-zinc-400">The copy starts switched off, so you can check it there first.</p>
		<div class="grid gap-1.5">
			{#each others as i (i.id)}
				<button class="rounded-lg border border-zinc-800 px-3 py-2 text-left text-sm text-zinc-200 hover:border-zinc-600" onclick={() => copyTo(i.id, i.name)}>{i.name}</button>
			{/each}
		</div>
	</Modal>
{/if}
