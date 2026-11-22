<script lang="ts">
	// a flow's last runs (with what their "log" blocks wrote) and its variables
	import { api } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { duration, relative } from '#lib/format.js';
	import { attempt } from '#lib/toast.svelte.js';
	import type { FlowActivity } from '#lib/types.js';
	import { CircleCheck, CircleX, Loader, RotateCcw } from '@lucide/svelte';

	let {
		instanceId,
		flowId,
		onshow,
		ontimings
	}: {
		instanceId: string;
		flowId: string;
		onshow: (step: string, message: string) => void;
		/** how long each block took in the last run, so the editor can show it */
		ontimings?: (timings: Record<string, number> | undefined) => void;
	} = $props();

	let tab = $state<'runs' | 'variables'>('runs');
	let activity = $state<FlowActivity | null>(null);
	let liveLog = $state<string[] | null>(null);

	async function load() {
		try {
			activity = await api.get<FlowActivity>(`/instances/${instanceId}/flows/${flowId}/activity`);
			ontimings?.(activity.runs[0]?.timings);
		} catch {
			// a flow that isn't saved yet has none
			activity = { runs: [], variables: {} };
		}
	}
	$effect(() => {
		void load();
		return app.onFlow((id, e) => {
			if (id !== instanceId || e.flowId !== flowId) return;
			if (e.state === 'started') (liveLog = []), ontimings?.(undefined);
			else if (e.state === 'log' && e.message) liveLog = [...(liveLog ?? []), e.message];
			else if (e.state === 'done' || e.state === 'error') {
				liveLog = null;
				void load();
			}
		});
	});

	async function reset() {
		if (!confirm('Reset every variable of this flow? Counters start from 0 again.')) return;
		if (await attempt(() => api.del(`/instances/${instanceId}/flows/${flowId}/variables`), 'Variables reset')) void load();
	}

	const vars = $derived(Object.entries(activity?.variables ?? {}));
	const botVars = $derived(Object.entries(activity?.botVariables ?? {}));
	const sharedVars = $derived(Object.entries(activity?.sharedVariables ?? {}));
	const varCount = $derived(vars.length + botVars.length + sharedVars.length);
	const sections = $derived([
		{ title: 'This flow', list: vars },
		{ title: 'All flows of this bot', list: botVars },
		{ title: 'All your bots', list: sharedVars }
	]);
</script>

<section class="mt-4 rounded-xl border border-zinc-800">
	<div class="flex items-center gap-1 border-b border-zinc-800 px-2 py-1.5" role="tablist">
		{#each [['runs', 'Runs'], ['variables', 'Variables']] as [id, label]}
			<button
				type="button"
				role="tab"
				aria-selected={tab === id}
				class="rounded-md px-2.5 py-1 text-xs font-medium {tab === id ? 'bg-zinc-800 text-zinc-100' : 'text-zinc-500 hover:text-zinc-300'}"
				onclick={() => (tab = id as typeof tab)}
				>{label}{id === 'runs' && activity?.runs.length ? ` (${activity.runs.length})` : id === 'variables' && varCount ? ` (${varCount})` : ''}</button
			>
		{/each}
		{#if tab === 'variables' && vars.length}
			<button type="button" class="btn-ghost ml-auto py-1 text-xs" onclick={reset}><RotateCcw size={12} /> Reset</button>
		{/if}
	</div>

	{#if !activity}
		<p class="px-4 py-3 text-xs text-zinc-500">Loading…</p>
	{:else if tab === 'runs'}
		<ul class="max-h-80 divide-y divide-zinc-800/70 overflow-y-auto text-sm">
			{#if liveLog}
				<li class="px-4 py-2">
					<div class="flex items-center gap-2 text-emerald-300"><Loader size={14} class="animate-spin" /> Running now</div>
					{#each liveLog as line}<div class="mt-0.5 pl-6 font-mono text-xs text-zinc-400">{line}</div>{/each}
				</li>
			{/if}
			{#each activity.runs as r (r.startedAt)}
				<li class="px-4 py-2">
					<div class="flex items-start gap-2">
						{#if r.ok}<CircleCheck size={14} class="mt-0.5 shrink-0 text-emerald-400" />{:else}<CircleX size={14} class="mt-0.5 shrink-0 text-red-400" />{/if}
						<div class="min-w-0 flex-1">
							<div class="flex flex-wrap items-baseline gap-x-2">
								<span class="truncate text-zinc-200">{r.cause}</span>
								<span class="text-xs text-zinc-500">{relative(r.startedAt)} · took {duration(Math.max(0, (r.at - r.startedAt) / 1000))}</span>
							</div>
							{#if !r.ok}
								<div class="text-xs text-red-300">
									{r.message}
									{#if r.step}<button type="button" class="ml-1 text-zinc-400 underline-offset-2 hover:underline" onclick={() => onshow(r.step ?? '', r.message ?? '')}
											>Show block</button
										>{/if}
								</div>
							{/if}
							{#each r.log as line}<div class="mt-0.5 font-mono text-xs text-zinc-400">{line}</div>{/each}
						</div>
					</div>
				</li>
			{:else}
				{#if !liveLog}<li class="px-4 py-3 text-xs text-zinc-500">It hasn’t run yet. Its last 20 runs show up here.</li>{/if}
			{/each}
		</ul>
	{:else if varCount}
		<div class="space-y-3 px-4 py-3">
			{#each sections as { title, list }}
				{#if list.length}
					<div>
						<h4 class="mb-1 text-xs font-medium text-zinc-500">{title}</h4>
						<dl class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
							{#each list as [name, value]}
								<dt class="font-mono text-xs leading-5 text-lime-300">{name}</dt>
								<dd class="min-w-0 truncate text-zinc-200">{value}</dd>
							{/each}
						</dl>
					</div>
				{/if}
			{/each}
		</div>
	{:else}
		<p class="px-4 py-3 text-xs text-zinc-500">No variables yet. “Set” and “change” blocks keep values here between runs, like a death counter.</p>
	{/if}
</section>
