<script lang="ts">
	import { app } from '#lib/app.svelte.js';
	import { duration } from '#lib/format.js';
	import type { Instance } from '#lib/types.js';
	import { Clock, Gauge, Heart, MapPin, Users } from '@lucide/svelte';
	import InstanceActions from './InstanceActions.svelte';
	import OwnerBadge from './OwnerBadge.svelte';
	import PhaseBadge from './PhaseBadge.svelte';

	let { instance }: { instance: Instance } = $props();

	const server = $derived(app.overview?.servers.find((s) => s.id === instance.serverId));
	const status = $derived(instance.runtime.phase === 'RUNNING' ? instance.runtime.status : null);
</script>

<div class="card flex flex-col transition-colors hover:border-zinc-700">
	<a href="/instances/{instance.id}" class="flex flex-1 flex-col gap-3 p-4">
		<div class="flex items-start gap-3">
			<img
				src="https://mc-heads.net/avatar/{instance.accountName}/40"
				alt=""
				class="size-10 shrink-0 rounded-md bg-zinc-800 [image-rendering:pixelated]"
				loading="lazy"
			/>
			<div class="min-w-0 flex-1">
				<div class="truncate font-semibold">{instance.name}</div>
				<div class="truncate text-sm text-zinc-400">{instance.accountName} · {server?.name ?? 'unknown server'}</div>
				<div class="mt-1.5"><OwnerBadge mine={instance.mine} ownerName={instance.ownerName} /></div>
			</div>
			<PhaseBadge runtime={instance.runtime} />
		</div>

		{#if status?.online}
			<div class="grid grid-cols-2 gap-x-4 gap-y-1.5 text-xs text-zinc-400">
				<span class="flex items-center gap-1.5"><Clock size={13} /> {duration(status.onlineSeconds)} online</span>
				<span class="flex items-center gap-1.5"><Heart size={13} /> {Math.round(status.health ?? 0)} hp · {status.food ?? 0} food</span>
				{#if status.position?.dimension}
					<span class="flex items-center gap-1.5 truncate"><MapPin size={13} /> {status.position.dimension.replace('minecraft:', '')}</span>
				{/if}
				<span class="flex items-center gap-1.5"><Gauge size={13} /> {status.ping}ms · {status.tps?.toFixed(1)} tps</span>
				{#if status.controller || status.spectators.length}
					<span class="col-span-2 flex items-center gap-1.5 truncate">
						<Users size={13} />
						{[status.controller, ...status.spectators].filter(Boolean).join(', ')}
					</span>
				{/if}
			</div>
		{:else if status?.inQueue}
			<div class="text-xs text-zinc-400">
				Position {status.queuePosition}{status.queueEtaSeconds ? ` · about ${duration(status.queueEtaSeconds)} left` : ''}
			</div>
		{:else if instance.runtime.phase === 'BACKOFF' && instance.runtime.lastError}
			<div class="line-clamp-2 text-xs text-red-400">{instance.runtime.lastError}</div>
		{/if}
	</a>
	<div class="border-t border-zinc-800 px-4 py-3">
		<InstanceActions {instance} />
	</div>
</div>
