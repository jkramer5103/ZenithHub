<script lang="ts">
	import { runAction } from '#lib/actions.js';
	import type { NearbyPlayer } from '#lib/types.js';
	import { ArrowUp, Footprints, Navigation, Radar } from '@lucide/svelte';

	let {
		instanceId,
		nearby,
		online,
		position,
		canPlay,
		onchanged
	}: {
		instanceId: string;
		nearby: NearbyPlayer[] | undefined;
		online: boolean;
		/** the bot's position, for directions */
		position: { x: number; y: number; z: number } | null | undefined;
		canPlay: boolean;
		onchanged: () => void;
	} = $props();

	let busy = $state(false);

	const COMPASS = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];

	/** degrees clockwise from north; north is -z and east is +x in Minecraft */
	function bearing(p: NearbyPlayer) {
		if (!position) return null;
		const angle = (Math.atan2(p.x - position.x, position.z - p.z) * 180) / Math.PI;
		return (angle + 360) % 360;
	}

	function distance(d: number) {
		return d < 10 ? `${d.toFixed(1)} m` : `${Math.round(d)} m`;
	}

	async function run(action: Record<string, unknown> & { action: string }) {
		busy = true;
		await runAction(instanceId, action);
		busy = false;
		onchanged();
	}
</script>

<section class="card p-4">
	<h2 class="mb-3 flex items-center gap-2 text-sm font-semibold text-zinc-300">
		<Radar size={15} /> Nearby players
		{#if nearby?.length}
			<span class="rounded-full bg-zinc-800 px-1.5 text-xs font-medium text-zinc-400">{nearby.length}</span>
		{/if}
	</h2>

	{#if !online}
		<p class="text-sm text-zinc-500">Shows up once the bot is in the game.</p>
	{:else if !nearby?.length}
		<p class="text-sm text-zinc-500">No other players in sight.</p>
	{:else}
		<ul class="divide-y divide-zinc-800/70">
			{#each nearby as p, i (`${i}:${p.uuid}`)}
				{@const angle = bearing(p)}
				<li class="flex flex-wrap items-center gap-x-3 gap-y-2 py-2.5">
					<div class="flex min-w-0 flex-1 basis-40 items-center gap-3">
						<img src="https://mc-heads.net/avatar/{p.uuid}/32" alt="" class="size-8 shrink-0 rounded [image-rendering:pixelated]" />
						<div class="min-w-0">
							<div class="flex items-center gap-1.5">
								<span class="truncate text-sm font-medium text-zinc-100">{p.name ?? 'Unknown player'}</span>
								{#if p.friend}
									<span class="shrink-0 rounded bg-sky-950 px-1.5 text-[11px] font-medium text-sky-300">Friend</span>
								{/if}
							</div>
							<div class="flex items-center gap-1.5 text-xs text-zinc-400">
								{#if angle != null}
									<ArrowUp size={12} class="shrink-0 text-zinc-300" style="transform: rotate({angle}deg)" />
									<span class="tabular-nums">{distance(p.distance)} {COMPASS[Math.round(angle / 45) % 8]}</span>
								{:else}
									<span class="tabular-nums">{distance(p.distance)}</span>
								{/if}
								<span class="truncate text-zinc-500 tabular-nums">· {Math.floor(p.x)}, {Math.floor(p.y)}, {Math.floor(p.z)}</span>
							</div>
						</div>
					</div>
					{#if canPlay}
						<div class="ml-auto flex shrink-0 items-center gap-1.5">
							{#if p.name}
								<button
									class="btn-secondary px-2.5 py-1.5 text-xs"
									disabled={busy}
									title="Keep walking after {p.name}"
									onclick={() => run({ action: 'follow', player: p.name })}
								>
									<Footprints size={14} /> Follow
								</button>
							{/if}
							<button
								class="btn-secondary px-2.5 py-1.5 text-xs"
								disabled={busy}
								title="Walk to where they are now"
								onclick={() => run({ action: 'goto', x: Math.floor(p.x), y: Math.floor(p.y), z: Math.floor(p.z) })}
							>
								<Navigation size={14} /> Go to
							</button>
						</div>
					{/if}
				</li>
			{/each}
		</ul>
	{/if}
</section>
