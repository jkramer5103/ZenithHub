<script lang="ts">
	import { runAction } from '#lib/actions.js';
	import { app } from '#lib/app.svelte.js';
	import type { ConnectedPlayer } from '#lib/types.js';
	import { Eye, Gamepad2, UserX, Users } from '@lucide/svelte';
	import ConnectionHistory from './ConnectionHistory.svelte';

	let {
		instanceId,
		players,
		onchanged
	}: { instanceId: string; players: ConnectedPlayer[] | null; onchanged: () => void } = $props();

	let busy = $state(false);
	/** a swap reconnects both players, so it takes a few seconds to show up */
	let swapping = $state<string | null>(null);

	const signature = $derived((players ?? []).map((p) => `${p.name}:${p.spectator ? 's' : 'p'}`).join(','));
	const mine = $derived(new Set((app.user?.mcNames ?? []).map((n) => n.toLowerCase())));

	async function run(action: Record<string, unknown> & { action: string }, swap?: string) {
		busy = true;
		const ok = await runAction(instanceId, action);
		busy = false;
		onchanged();
		if (ok && swap) {
			swapping = swap;
			setTimeout(() => {
				if (swapping === swap) swapping = null;
				onchanged();
			}, 4000);
		}
	}

	function swapBlocked(p: ConnectedPlayer) {
		if (!p.canSwap) return `${p.name} needs Minecraft 1.20.5 or newer to swap (they're on ${p.version})`;
		if (p.spectator && !p.canPlay) return `${p.name} may only spectate this account`;
		return null;
	}
</script>

<section class="card p-4">
	<h2 class="mb-3 flex items-center gap-2 text-sm font-semibold text-zinc-300">
		<Users size={15} /> Connected players
		{#if players?.length}
			<span class="rounded-full bg-zinc-800 px-1.5 text-xs font-medium text-zinc-400">{players.length}</span>
		{/if}
		{#if players && players.length > 1}
			<button class="btn-ghost -my-1.5 ml-auto px-2.5 py-1.5 text-xs font-normal text-red-300" disabled={busy} onclick={() => run({ action: 'kick' })}>
				<UserX size={14} /> Kick everyone
			</button>
		{/if}
	</h2>

	{#if !players}
		<p class="text-sm text-zinc-500">Loading…</p>
	{:else if !players.length}
		<p class="text-sm text-zinc-500">Nobody is connected. Players join through the lobby.</p>
	{:else}
		<ul class="divide-y divide-zinc-800/70">
			{#each players as p (p.name)}
				{@const blocked = swapBlocked(p)}
				<li class="flex flex-wrap items-center gap-x-3 gap-y-2 py-2.5">
					<div class="flex min-w-0 flex-1 basis-40 items-center gap-3">
						<img src="https://mc-heads.net/avatar/{p.uuid ?? p.name}/32" alt="" class="size-8 shrink-0 rounded [image-rendering:pixelated]" />
						<div class="min-w-0">
							<div class="flex items-center gap-1.5">
								<span class="truncate text-sm font-medium text-zinc-100">{p.name}</span>
								{#if mine.has(p.name.toLowerCase())}
									<span class="shrink-0 rounded bg-emerald-950 px-1.5 text-[11px] font-medium text-emerald-300">You</span>
								{/if}
							</div>
							<div class="text-xs">
								{#if swapping === p.name}
									<span class="text-zinc-400">Swapping…</span>
								{:else if p.spectator}
									<span class="inline-flex items-center gap-1 text-sky-300"><Eye size={12} /> Spectating</span>
								{:else}
									<span class="inline-flex items-center gap-1 text-emerald-300"><Gamepad2 size={12} /> Playing</span>
								{/if}
								{#if !p.canSwap}
									<div class="text-zinc-500">Needs Minecraft 1.20.5+ to swap</div>
								{:else if p.spectator && !p.canPlay}
									<div class="text-zinc-500">May only spectate</div>
								{/if}
							</div>
						</div>
					</div>
					<div class="ml-auto flex shrink-0 items-center gap-1.5">
						{#if p.spectator}
							<button
								class="btn-secondary px-2.5 py-1.5 text-xs"
								disabled={busy || !!blocked || swapping != null}
								title={blocked ?? `Let ${p.name} play. Whoever is playing now starts spectating.`}
								onclick={() => run({ action: 'makePlayer', player: p.name }, p.name)}
							>
								<Gamepad2 size={14} /> Make player
							</button>
						{:else}
							<button
								class="btn-secondary px-2.5 py-1.5 text-xs"
								disabled={busy || !!blocked || swapping != null}
								title={blocked ?? `${p.name} keeps watching, and the bot takes over again`}
								onclick={() => run({ action: 'makeSpectator' }, p.name)}
							>
								<Eye size={14} /> Make spectator
							</button>
						{/if}
						<button
							class="btn-ghost px-2.5 py-1.5 text-xs text-red-300"
							disabled={busy}
							title="Disconnect {p.name} from the account"
							onclick={() => run({ action: 'kick', player: p.name })}
						>
							<UserX size={14} /> Kick
						</button>
					</div>
				</li>
			{/each}
		</ul>
	{/if}
	<ConnectionHistory {instanceId} signature={signature} />
</section>
