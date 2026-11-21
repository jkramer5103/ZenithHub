<script lang="ts">
	import { api } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { capabilityLabels } from '#lib/access.js';
	import { relative } from '#lib/format.js';
	import IntegrationModal from '#lib/components/IntegrationModal.svelte';
	import { attempt } from '#lib/toast.svelte.js';
	import type { Integration } from '#lib/types.js';
	import { KeyRound, Plus, Trash2 } from '@lucide/svelte';
	import { onMount } from 'svelte';

	let tokens = $state<Integration[] | null>(null);
	let creating = $state(false);

	async function load() {
		tokens = await api.get<Integration[]>('/me/integrations');
	}
	onMount(() => void attempt(load));

	function bots(t: Integration) {
		if (t.allInstances) return 'All your bots';
		const names = t.instances.map((id) => app.overview?.instances.find((i) => i.id === id)?.name).filter(Boolean);
		return names.length ? names.join(', ') : 'No bots left';
	}

	function abilities(t: Integration) {
		const extra = t.capabilities.filter((c) => c !== 'VIEW');
		return extra.length ? extra.map((c) => capabilityLabels[c]).join(' · ') : 'Only see status';
	}

	async function remove(t: Integration) {
		if (!confirm(`Delete “${t.name}”? Programs using it stop working right away.`)) return;
		await attempt(async () => {
			await api.del(`/me/integrations/${t.id}`);
			await load();
		}, 'Token deleted');
	}
</script>

<svelte:head><title>Integrations · ZenithHub</title></svelte:head>

<div class="mb-6 flex flex-wrap items-center justify-between gap-3">
	<div>
		<h1 class="text-2xl font-semibold">Integrations</h1>
		<p class="mt-1 max-w-2xl text-sm text-zinc-400">
			Tokens let another program, like the Blockbot Minecraft add-on, use your bots as you: load pearls, send chat, move them, and react when something
			happens to them. Each token only reaches the bots and actions you pick.
		</p>
	</div>
	<button class="btn-primary" onclick={() => (creating = true)}><Plus size={16} /> New token</button>
</div>

{#if tokens === null}
	<div class="card py-12 text-center text-sm text-zinc-500">Loading…</div>
{:else if tokens.length === 0}
	<div class="card py-12 text-center text-sm text-zinc-400">No tokens yet.</div>
{:else}
	<div class="card divide-y divide-zinc-800">
		{#each tokens as t (t.id)}
			<div class="flex items-center gap-3 p-4">
				<span class="flex size-9 shrink-0 items-center justify-center rounded-lg bg-zinc-800 text-zinc-300"><KeyRound size={17} /></span>
				<div class="min-w-0 flex-1">
					<div class="truncate font-medium">{t.name}</div>
					<div class="truncate text-xs text-zinc-400">{bots(t)}</div>
					<div class="text-xs text-zinc-500">{abilities(t)}</div>
					<div class="text-xs text-zinc-600">
						Created {relative(t.created)} · {t.lastUsed ? `last used ${relative(t.lastUsed)}` : 'never used'}
					</div>
				</div>
				<button class="btn-ghost p-2 hover:text-red-400" title="Delete token" onclick={() => remove(t)}><Trash2 size={16} /></button>
			</div>
		{/each}
	</div>
{/if}

{#if creating}<IntegrationModal onclose={() => (creating = false)} oncreated={load} />{/if}
