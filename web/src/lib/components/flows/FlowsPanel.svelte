<script lang="ts">
	import { api } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { HAT, countSteps, examples, fromShareCode, newId, triggerText } from '#lib/flow-blocks.js';
	import { relative } from '#lib/format.js';
	import { attempt } from '#lib/toast.svelte.js';
	import type { Capability, Flow } from '#lib/types.js';
	import { Blocks, ChevronRight, Import, Loader, Pause, Plus, Zap } from '@lucide/svelte';
	import Modal from '../Modal.svelte';
	import Switch from '../settings/Switch.svelte';
	import FlowEditor from './FlowEditor.svelte';

	let {
		instanceId,
		capabilities,
		running,
		editing = $bindable(false)
	}: { instanceId: string; capabilities: Capability[]; running: boolean; editing?: boolean } = $props();

	let flows = $state<Flow[] | null>(null);
	let error = $state('');
	let open = $state<{ flow: Flow; isNew: boolean } | null>(null);
	let showExamples = $state(false);
	let importing = $state(false);
	let code = $state('');
	let importError = $state('');

	function importCode() {
		try {
			const flow = fromShareCode(code);
			importing = false;
			code = importError = '';
			open = { flow, isNew: true };
		} catch (e) {
			importError = e instanceof Error ? e.message : String(e);
		}
	}

	function nextRun(at: number) {
		const d = new Date(at);
		const sameDay = d.toDateString() === new Date().toDateString();
		const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
		return sameDay ? `next today at ${time}` : `next ${d.toLocaleDateString([], { weekday: 'short' })} at ${time}`;
	}
	$effect(() => {
		editing = open !== null;
	});

	async function load() {
		try {
			flows = await api.get<Flow[]>(`/instances/${instanceId}/flows`);
			error = '';
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		}
	}
	$effect(() => {
		void load();
		const offFlow = app.onFlow((id, e) => {
			if (id !== instanceId) return;
			if (e.state === 'changed' || e.state === 'done' || e.state === 'error') void load();
			else if (flows) {
				const f = flows.find((f) => f.id === e.flowId);
				if (!f) return;
				const paused = e.state === 'paused' ? true : e.state === 'resumed' ? false : f.running?.paused;
				f.running = { startedAt: f.running?.startedAt ?? Date.now(), step: e.step ?? f.running?.step, paused };
			}
		});
		const offReconnect = app.onReconnect(() => void load());
		return () => {
			offFlow();
			offReconnect();
		};
	});

	function create(from?: Omit<Flow, 'id'>) {
		showExamples = false;
		const flow: Flow = from
			? { ...JSON.parse(JSON.stringify(from)), id: newId() }
			: { id: newId(), name: 'New flow', enabled: true, cooldown: 10, mode: 'ignore', trigger: { kind: 'manual' }, steps: [] };
		delete (flow as { blurb?: string }).blurb;
		open = { flow, isNew: true };
	}

	async function toggle(f: Flow, on: boolean) {
		const body = JSON.parse(JSON.stringify(f));
		delete body.running;
		delete body.last;
		f.enabled = on;
		if (!(await attempt(() => api.put(`/instances/${instanceId}/flows/${f.id}`, { ...body, enabled: on })))) f.enabled = !on;
	}
</script>

{#if open}
	{#key open.flow.id}
		<FlowEditor
			{instanceId}
			{capabilities}
			{running}
			initial={open.flow}
			isNew={open.isNew}
			onclose={(changed) => {
				open = null;
				if (changed) void load();
			}}
			onopen={(flow) => {
				void load();
				open = { flow, isNew: true };
			}}
		/>
	{/key}
{:else}
	<section class="card mx-auto mb-4 w-full max-w-3xl">
		<div class="flex items-center gap-2 border-b border-zinc-800 px-4 py-3">
			<div class="min-w-0 flex-1">
				<h2 class="text-sm font-semibold text-zinc-200">Flows</h2>
				<p class="text-xs text-zinc-500">Snap blocks together: when something happens, do several things in order.</p>
			</div>
			{#if flows?.length}
				<button class="btn-ghost shrink-0 py-1.5 text-xs" onclick={() => (showExamples = true)}>Examples</button>
			{/if}
			<button class="btn-ghost shrink-0 p-1.5" aria-label="Import a flow" title="Import a flow code" onclick={() => (importing = true)}><Import size={15} /></button>
			<button class="btn-secondary shrink-0 py-1.5" onclick={() => create()}><Plus size={15} /> New</button>
		</div>

		{#if !flows}
			<p class="px-4 py-6 text-sm {error ? 'text-red-300' : 'text-zinc-500'}">{error ? `Couldn't load the flows: ${error}` : 'Loading…'}</p>
		{:else if !flows.length}
			<div class="px-4 py-5">
				<p class="mb-3 flex items-center gap-2 text-sm text-zinc-400"><Blocks size={16} class="text-zinc-600" /> No flows yet. Start from an example:</p>
				<div class="grid gap-2 sm:grid-cols-2">
					{#each examples() as ex}
						<button class="rounded-lg border border-zinc-800 bg-zinc-950/50 p-3 text-left transition-colors hover:border-zinc-600" onclick={() => create(ex)}>
							<div class="mb-1 flex items-center gap-1.5 text-sm font-medium text-zinc-100">{ex.name}</div>
							<div class="text-xs text-zinc-500">{ex.blurb}</div>
						</button>
					{/each}
				</div>
			</div>
		{:else}
			<ul class="divide-y divide-zinc-800">
				{#each flows as f (f.id)}
					<li class="flex items-center gap-3 px-4 py-3">
						<button class="flex min-w-0 flex-1 items-center gap-3 text-left" onclick={() => (open = { flow: f, isNew: false })}>
							<span class="grid size-8 shrink-0 place-items-center rounded-lg text-white {HAT.fill} {f.enabled ? '' : 'opacity-40'}"><Zap size={15} /></span>
							<span class="min-w-0 flex-1">
								<span class="block truncate font-medium text-zinc-100">{f.name}</span>
								<span class="block truncate text-xs text-zinc-400">
									{triggerText(f.trigger)}{f.next ? `, ${nextRun(f.next)}` : ''} · {countSteps(f.steps)}
									{countSteps(f.steps) === 1 ? 'block' : 'blocks'}
								</span>
								{#if f.running}
									{#if f.running.paused}
										<span class="flex items-center gap-1 text-xs text-amber-300"><Pause size={11} /> Paused</span>
									{:else}
										<span class="flex items-center gap-1 text-xs text-emerald-300"><Loader size={11} class="animate-spin" /> Running</span>
									{/if}
								{:else if f.last}
									<span class="block truncate text-xs {f.last.ok ? 'text-zinc-500' : 'text-red-300'}">
										{f.last.ok ? `Ran ${relative(f.last.at)}` : `Failed ${relative(f.last.at)}: ${f.last.message}`}
									</span>
								{/if}
							</span>
							<ChevronRight size={16} class="shrink-0 text-zinc-600" />
						</button>
						<Switch checked={f.enabled} label="{f.name} on" onchange={(v) => toggle(f, v)} />
					</li>
				{/each}
			</ul>
		{/if}
	</section>
{/if}

{#if showExamples}
	<Modal title="Start from an example" onclose={() => (showExamples = false)}>
		<div class="grid gap-2">
			{#each examples() as ex}
				<button class="rounded-lg border border-zinc-800 bg-zinc-950/50 p-3 text-left transition-colors hover:border-zinc-600" onclick={() => create(ex)}>
					<div class="mb-1 text-sm font-medium text-zinc-100">{ex.name}</div>
					<div class="text-xs text-zinc-500">{ex.blurb}</div>
				</button>
			{/each}
		</div>
	</Modal>
{/if}

{#if importing}
	<Modal title="Import a flow" onclose={() => (importing = false)}>
		<p class="mb-2 text-sm text-zinc-400">Paste a flow code. You get them from “Copy flow code” in a flow’s ⋯ menu. The flow opens for you to check before it’s saved.</p>
		<textarea
			class="input h-28 font-mono text-xs"
			placeholder="ZHF1:…"
			aria-label="Flow code"
			spellcheck="false"
			bind:value={code}
			oninput={() => (importError = '')}
		></textarea>
		{#if importError}<p class="mt-1 text-xs text-red-300">{importError}</p>{/if}
		<div class="mt-3 flex justify-end gap-2">
			<button class="btn-ghost" onclick={() => (importing = false)}>Cancel</button>
			<button class="btn-primary" disabled={!code.trim()} onclick={importCode}>Open</button>
		</div>
	</Modal>
{/if}
