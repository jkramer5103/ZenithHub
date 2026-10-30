<script lang="ts">
	import { runAction } from '#lib/actions.js';
	import type { InstanceDetails, InstanceStatus } from '#lib/types.js';
	import { Square } from '@lucide/svelte';

	let {
		instanceId,
		details,
		status,
		canPlay,
		onchanged
	}: {
		instanceId: string;
		details: InstanceDetails | null;
		status: InstanceStatus | null;
		canPlay: boolean;
		onchanged: () => void;
	} = $props();

	let busy = $state(false);

	const pathing = $derived(details?.pathfinder?.active ?? false);
	const clicking = $derived(details?.clicking ?? null);
	// Baritone's process names, e.g. "Custom Goal GoalXZ[x=10, z=10]", in plain words
	const moving = $derived.by(() => {
		const process = details?.pathfinder?.process ?? '';
		const coords = process.match(/x=(-?\d+)(?:, y=(-?\d+))?, z=(-?\d+)/);
		if (coords) return `Going to ${[coords[1], coords[2], coords[3]].filter((c) => c != null).join(', ')}`;
		if (/follow/i.test(process)) return 'Following';
		if (/mine/i.test(process)) return 'Mining';
		if (/elytra/i.test(process)) return 'Flying';
		if (/pickup|collect/i.test(process)) return 'Picking up';
		return 'Moving';
	});
	const doing = $derived(
		[pathing ? moving : null, clicking ? `Holding ${clicking} click` : null, details?.recording ? 'Recording' : null].filter(
			(s): s is string => s != null
		)
	);
	const position = $derived(status?.position);

	async function stop() {
		busy = true;
		if (pathing) await runAction(instanceId, { action: 'stop' }, true);
		if (clicking) await runAction(instanceId, { action: 'clickStop' }, true);
		busy = false;
		onchanged();
	}
</script>

<div class="card flex min-h-14 items-center gap-3 px-4 py-2.5">
	<span class="size-2 shrink-0 rounded-full {doing.length ? 'animate-pulse bg-emerald-400' : 'bg-zinc-600'}"></span>
	<div class="min-w-0 flex-1">
		<div class="truncate text-sm font-medium text-zinc-100" title={details?.pathfinder?.process ?? ''}>
			{doing.length ? doing.join(' · ') : details?.online ? 'Idle' : 'Not in the game'}
		</div>
		{#if position}
			<div class="truncate text-xs text-zinc-500 tabular-nums">
				{Math.floor(position.x)}, {Math.floor(position.y)}, {Math.floor(position.z)}{#if position.dimension}
					· {position.dimension.replace('minecraft:', '').replace('the_', '')}{/if}
			</div>
		{/if}
	</div>
	{#if canPlay && (pathing || clicking)}
		<button class="btn-danger shrink-0 px-3 py-1.5 text-sm" disabled={busy} onclick={stop}>
			<Square size={13} /> Stop
		</button>
	{/if}
</div>
