<script lang="ts">
	import { runAction } from '#lib/actions.js';
	import type { InstanceStatus, Waypoint } from '#lib/types.js';
	import { MapPin, Trash2 } from '@lucide/svelte';

	let {
		instanceId,
		waypoints,
		status,
		canSave,
		onchanged
	}: {
		instanceId: string;
		waypoints: Waypoint[];
		status: InstanceStatus | null;
		canSave: boolean;
		onchanged: () => void;
	} = $props();

	let busy = $state(false);
	let name = $state('');

	async function run(action: Record<string, unknown> & { action: string }) {
		busy = true;
		const ok = await runAction(instanceId, action);
		busy = false;
		onchanged();
		return ok;
	}

	async function save() {
		if (!status?.position?.dimension) return;
		const ok = await run({
			action: 'addWaypoint',
			id: name.trim(),
			dimension: status.position.dimension,
			x: Math.floor(status.position.x),
			y: Math.floor(status.position.y),
			z: Math.floor(status.position.z)
		});
		if (ok) name = '';
	}
</script>

<section class="card p-4">
	<h2 class="mb-3 flex items-center gap-2 text-sm font-semibold text-zinc-300"><MapPin size={15} /> Waypoints</h2>
	{#if waypoints.length}
		<ul class="mb-2 divide-y divide-zinc-800/70">
			{#each waypoints as w, i (`${i}:${w.id}`)}
				<li class="flex items-center gap-2 py-1.5 text-sm">
					<span class="min-w-0 flex-1 truncate">
						<span class="font-medium text-zinc-200">{w.id}</span>
						<span class="ml-1 text-xs text-zinc-500">{w.x}, {w.y}, {w.z} · {w.dimension.replace('minecraft:', '').replace('the_', '')}</span>
					</span>
					<button class="btn-secondary px-2.5 py-1 text-xs" disabled={busy} onclick={() => run({ action: 'gotoWaypoint', waypoint: w.id })}>Go</button>
					{#if canSave}
						<button class="btn-ghost p-1.5" aria-label="Delete waypoint {w.id}" disabled={busy} onclick={() => run({ action: 'deleteWaypoint', id: w.id })}>
							<Trash2 size={14} />
						</button>
					{/if}
				</li>
			{/each}
		</ul>
	{:else}
		<p class="mb-2 text-xs text-zinc-500">No waypoints yet. Save a spot to send the bot back there with one tap.</p>
	{/if}
	{#if canSave && status?.position?.dimension}
		<form
			class="flex gap-2"
			onsubmit={(e) => {
				e.preventDefault();
				if (name.trim()) save();
			}}
		>
			<input class="input py-1.5" placeholder="Name this spot, e.g. base" maxlength="32" bind:value={name} />
			<button class="btn-secondary shrink-0 py-1.5" disabled={!name.trim() || busy}>Save here</button>
		</form>
	{/if}
</section>
