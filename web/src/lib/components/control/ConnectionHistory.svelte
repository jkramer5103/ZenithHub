<script lang="ts">
	import { api } from '#lib/api.js';
	import type { HistoryEntry } from '#lib/types.js';
	import { ChevronDown, History } from '@lucide/svelte';

	/** {@link signature} changes whenever someone joins, leaves or switches, so the list reloads then */
	let { instanceId, signature }: { instanceId: string; signature: string } = $props();

	const SHORT = 6;
	let entries = $state<HistoryEntry[] | null>(null);
	let open = $state(false);
	let showAll = $state(false);

	async function load() {
		try {
			entries = await api.get<HistoryEntry[]>(`/instances/${instanceId}/history`);
		} catch {
			entries ??= [];
		}
	}

	$effect(() => {
		void signature;
		// Zenith reports the change a moment after the player list shows it
		const timer = setTimeout(load, 1500);
		return () => clearTimeout(timer);
	});
	$effect(() => {
		const timer = setInterval(load, 30_000);
		return () => clearInterval(timer);
	});

	const shown = $derived(entries ? (showAll ? entries : entries.slice(0, SHORT)) : []);

	function role(spectator: boolean) {
		return spectator ? 'spectator' : 'player';
	}

	function describe(e: HistoryEntry) {
		switch (e.event) {
			case 'joined':
				return `joined as ${role(e.spectator)}`;
			case 'left':
				return 'left';
			case 'switched':
				return e.spectator ? 'switched to spectating' : 'took over playing';
			case 'kicked':
				return 'was kicked';
		}
	}

	function when(ts: number) {
		const date = new Date(ts);
		const time = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
		if (date.toDateString() === new Date().toDateString()) return time;
		return `${date.toLocaleDateString([], { month: 'short', day: 'numeric' })}, ${time}`;
	}
</script>

<div class="mt-2 border-t border-zinc-800/70 pt-2">
	<button
		type="button"
		class="flex w-full items-center gap-2 rounded-md px-1 py-1.5 text-left text-xs font-medium text-zinc-400 hover:text-zinc-200"
		aria-expanded={open}
		onclick={() => (open = !open)}
	>
		<History size={14} />
		<span class="flex-1">History</span>
		<ChevronDown size={14} class={open ? 'rotate-180' : ''} />
	</button>

	{#if open}
		{#if !entries}
			<p class="px-1 py-2 text-sm text-zinc-500">Loading…</p>
		{:else if !entries.length}
			<p class="px-1 py-2 text-sm text-zinc-500">Nobody has connected yet.</p>
		{:else}
			<ul class="mt-1 space-y-0.5">
				{#each shown as e, i (`${i}:${e.ts}:${e.name}:${e.event}`)}
					<li class="flex items-start gap-2.5 rounded-md px-1 py-1.5 text-sm">
						<img
							src="https://mc-heads.net/avatar/{e.uuid ?? e.name}/20"
							alt=""
							class="mt-0.5 size-5 shrink-0 rounded [image-rendering:pixelated]"
						/>
						<div class="min-w-0 flex-1">
							<div class="text-zinc-300">
								<span class="font-medium text-zinc-100">{e.name}</span>
								<span class={e.event === 'kicked' ? 'text-red-300' : ''}>{describe(e)}</span>
								{#if e.actor}<span class="text-zinc-500">by {e.actor}</span>{/if}
							</div>
							{#if e.reason}
								<div class="truncate text-xs text-zinc-500" title={e.reason}>{e.reason}</div>
							{/if}
						</div>
						<time class="shrink-0 pt-0.5 text-xs text-zinc-500 tabular-nums" datetime={new Date(e.ts).toISOString()} title={new Date(e.ts).toLocaleString()}>
							{when(e.ts)}
						</time>
					</li>
				{/each}
			</ul>
			{#if entries.length > SHORT}
				<button class="btn-ghost mt-1 w-full py-1.5 text-xs" onclick={() => (showAll = !showAll)}>
					{showAll ? 'Show less' : `Show all ${entries.length}`}
				</button>
			{/if}
		{/if}
	{/if}
</div>
