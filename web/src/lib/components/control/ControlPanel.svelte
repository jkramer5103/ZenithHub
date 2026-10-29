<script lang="ts">
	import { runAction } from '#lib/actions.js';
	import { api } from '#lib/api.js';
	import { DetailsPoller } from '#lib/details.svelte.js';
	import { attempt } from '#lib/toast.svelte.js';
	import type { Capability, InstanceStatus, Replay } from '#lib/types.js';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { Backpack, Circle, Download, Ellipsis, HeartCrack, Navigation, Plug, Trash2, Users, Video } from '@lucide/svelte';
	import ActivityBar from './ActivityBar.svelte';
	import ConnectedPlayers from './ConnectedPlayers.svelte';
	import FingerprintCard from './FingerprintCard.svelte';
	import InteractCard from './InteractCard.svelte';
	import InventoryGrid from './InventoryGrid.svelte';
	import MovementCard from './MovementCard.svelte';
	import NearbyPlayers from './NearbyPlayers.svelte';
	import WaypointsCard from './WaypointsCard.svelte';

	let {
		instanceId,
		capabilities,
		status,
		running
	}: { instanceId: string; capabilities: Capability[]; status: InstanceStatus | null; running: boolean } = $props();

	// the page remounts this per instance ({#key}), so the first id is the only one
	// svelte-ignore state_referenced_locally
	const poller = new DetailsPoller(instanceId);
	$effect(() => poller.start());
	const details = $derived(poller.details);

	const canPlay = $derived(capabilities.includes('PLAY'));
	const canControl = $derived(capabilities.includes('CONTROL'));
	const online = $derived(!!details?.online);
	let busy = $state(false);

	async function run(action: Record<string, unknown> & { action: string }, quiet = false) {
		busy = true;
		await runAction(instanceId, action, quiet);
		busy = false;
		void poller.refresh();
	}

	// one view at a time, so nothing is more than a tap away, even on a phone
	const views = $derived(
		[
			{ id: 'move', label: 'Move', icon: Navigation, show: canPlay },
			{ id: 'players', label: 'Players', icon: Users, show: true },
			{ id: 'inventory', label: 'Inventory', icon: Backpack, show: canPlay },
			{ id: 'more', label: 'More', icon: Ellipsis, show: canPlay || canControl }
		].filter((v) => v.show)
	);
	// the URL keeps the view across reloads; shallow, so switching doesn't reload the page
	let chosen = $state(page.url.searchParams.get('view'));
	const view = $derived(views.find((v) => v.id === chosen)?.id ?? views[0].id);
	function show(id: string) {
		chosen = id;
		const url = new URL(page.url.href);
		url.searchParams.set('view', id);
		void goto(url, { shallow: true, replace: true, state: {} });
	}
	const playerCount = $derived((canControl ? details?.players?.length : details?.nearby?.length) ?? 0);

	// --- players and transfer ---
	let transferAddress = $state('');
	let transferPort = $state('');
	let transferPlayer = $state('');

	// --- recordings ---
	let replays = $state<Replay[] | null>(null);
	async function loadReplays() {
		if (!canControl) return;
		try {
			replays = await api.get<Replay[]>(`/instances/${instanceId}/replays`);
		} catch {
			replays = [];
		}
	}
	$effect(() => {
		void loadReplays();
	});
	let wasRecording = false;
	$effect(() => {
		// a recording just finished: its file is there now
		const recording = !!details?.recording;
		if (wasRecording && !recording) setTimeout(loadReplays, 1500);
		wasRecording = recording;
	});

	function size(bytes: number) {
		return bytes > 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
	}
</script>

{#if !running}
	<div class="card p-6 text-center text-sm text-zinc-400">Connect the instance to control the bot.</div>
{:else}
	{#if details && !online}
		<div class="mb-4 rounded-lg border border-zinc-800 bg-zinc-900 px-4 py-3 text-sm text-zinc-300">
			The bot isn't in the game yet{status?.inQueue ? ', it is in the queue' : ''}. Most controls work once it's online.
		</div>
	{:else if details?.controlled}
		<div class="mb-4 rounded-lg border border-amber-900/60 bg-amber-950/30 px-4 py-3 text-sm text-amber-200">
			{status?.controller ?? 'Someone'} is playing on the account, so the bot won't move on its own until they leave.
		</div>
	{/if}

	{#if details && online && details.alive === false && canPlay}
		<div class="mb-4 flex items-center gap-3 rounded-lg border border-red-900 bg-red-950/40 px-4 py-3 text-sm text-red-200">
			<HeartCrack size={18} />
			<span class="flex-1">The bot is dead.</span>
			<button class="btn-primary py-1.5" disabled={busy} onclick={() => run({ action: 'respawn' })}>Respawn</button>
		</div>
	{/if}

	<div class="space-y-4">
		<ActivityBar {instanceId} {details} {status} {canPlay} onchanged={() => poller.refresh()} />

		{#if views.length > 1}
		<div class="grid gap-1 rounded-xl border border-zinc-800 bg-zinc-900/60 p-1 sm:w-fit" style="grid-template-columns: repeat({views.length}, minmax(0, 1fr))" role="tablist">
			{#each views as v (v.id)}
				<button
					role="tab"
					aria-selected={view === v.id}
					class="flex items-center justify-center gap-1.5 rounded-lg px-2 py-2 text-sm font-medium sm:px-4 {view === v.id
						? 'bg-zinc-800 text-zinc-100'
						: 'text-zinc-400 hover:text-zinc-200'}"
					onclick={() => show(v.id)}
				>
					<v.icon size={15} class="hidden shrink-0 sm:block" />
					{v.label}
					{#if v.id === 'players' && playerCount}
						<span class="rounded-full bg-zinc-700/70 px-1.5 text-xs text-zinc-300">{playerCount}</span>
					{/if}
				</button>
			{/each}
		</div>
		{/if}

		{#if view === 'move'}
			<div class="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,24rem)]">
				<MovementCard {instanceId} {status} onchanged={() => poller.refresh()} />
				<WaypointsCard
					{instanceId}
					waypoints={details?.waypoints ?? []}
					{status}
					canSave={capabilities.includes('SETTINGS')}
					onchanged={() => poller.refresh()}
				/>
			</div>
		{:else if view === 'players'}
			<div class="grid items-start gap-4 {canControl ? 'lg:grid-cols-2' : 'max-w-xl'}">
				{#if canControl}
					<ConnectedPlayers {instanceId} players={details ? (details.players ?? []) : null} onchanged={() => poller.refresh()} />
				{/if}
				<NearbyPlayers {instanceId} nearby={details?.nearby} {online} position={status?.position} {canPlay} onchanged={() => poller.refresh()} />
			</div>
		{:else if view === 'inventory'}
			<section class="card max-w-xl p-4">
				{#if details?.inventory}
					<InventoryGrid {instanceId} inventory={details.inventory} canAct={!busy} onchanged={() => poller.refresh()} />
				{:else}
					<p class="text-sm text-zinc-500">{details ? 'Shows up once the bot is in the game.' : 'Loading…'}</p>
				{/if}
			</section>
		{:else}
			<div class="grid items-start gap-4 lg:grid-cols-2">
				<div class="space-y-4">
					{#if canPlay}
						<InteractCard {instanceId} {details} onchanged={() => poller.refresh()} />
					{/if}
					{#if canControl && details?.fingerprint}
						<FingerprintCard fingerprint={details.fingerprint} />
					{/if}
				</div>
				{#if canControl}
					<div class="space-y-4">
						<section class="card p-4">
							<h2 class="mb-3 flex items-center gap-2 text-sm font-semibold text-zinc-300"><Plug size={15} /> Connection</h2>
							<div class="space-y-4">
								<form
									class="space-y-2"
									onsubmit={(e) => {
										e.preventDefault();
										run({
											action: 'transfer',
											address: transferAddress.trim(),
											port: transferPort.trim() ? Number(transferPort) : null,
											player: transferPlayer.trim() || null
										});
									}}
								>
									<div class="text-xs text-zinc-500">Send connected players to another server (Minecraft 1.20.5+)</div>
									<div class="grid grid-cols-[1fr_5.5rem] gap-2">
										<input class="input" placeholder="Server address" autocapitalize="off" spellcheck="false" bind:value={transferAddress} />
										<input class="input" placeholder="Port" inputmode="numeric" bind:value={transferPort} />
									</div>
									<div class="flex gap-2">
										<input class="input" placeholder="Only this player (optional)" autocapitalize="off" spellcheck="false" bind:value={transferPlayer} />
										<button class="btn-secondary shrink-0" disabled={busy || !transferAddress.trim() || !details?.players?.length}>Send</button>
									</div>
								</form>

								<div class="flex flex-wrap items-center gap-2 border-t border-zinc-800 pt-3">
									<span class="flex-1 text-xs text-zinc-500">2b2t: go back to the start of the queue without a full reconnect.</span>
									<button class="btn-secondary py-1.5" disabled={busy || !status?.connected} onclick={() => run({ action: 'requeue' })}>Requeue</button>
								</div>
							</div>
						</section>

						<section class="card p-4">
							<div class="mb-3 flex items-center gap-2">
								<h2 class="flex flex-1 items-center gap-2 text-sm font-semibold text-zinc-300"><Video size={15} /> Recording</h2>
								{#if details?.recording}
									<span class="inline-flex items-center gap-1.5 rounded-full bg-red-950/70 px-2 py-0.5 text-xs text-red-300">
										<Circle size={8} class="animate-pulse fill-red-400 text-red-400" /> Recording
									</span>
									<button class="btn-secondary px-2.5 py-1.5 text-xs" disabled={busy} onclick={() => run({ action: 'replayStop' })}>Stop</button>
								{:else}
									<button class="btn-secondary px-2.5 py-1.5 text-xs" disabled={busy || !online} onclick={() => run({ action: 'replayStart' })}>
										Start recording
									</button>
								{/if}
							</div>
							<p class="mb-2 text-xs text-zinc-500">Records what the bot sees as a replay you can open with ReplayMod.</p>
							{#if replays?.length}
								<ul class="divide-y divide-zinc-800/70">
									{#each replays as r (r.name)}
										<li class="flex items-center gap-2 py-1.5 text-sm">
											<span class="min-w-0 flex-1">
												<span class="block truncate text-zinc-200">{new Date(r.modified).toLocaleString()}</span>
												<span class="text-xs text-zinc-500">{size(r.size)}</span>
											</span>
											<a class="btn-ghost p-1.5" href="/api/instances/{instanceId}/replays/{encodeURIComponent(r.name)}" download title="Download">
												<Download size={15} />
											</a>
											<button
												class="btn-ghost p-1.5"
												title="Delete"
												onclick={() => attempt(async () => {
													await api.del(`/instances/${instanceId}/replays/${encodeURIComponent(r.name)}`);
													await loadReplays();
												}, 'Recording deleted')}
											>
												<Trash2 size={15} />
											</button>
										</li>
									{/each}
								</ul>
							{:else if replays}
								<p class="text-sm text-zinc-500">No recordings yet.</p>
							{/if}
						</section>
					</div>
				{/if}
			</div>
		{/if}
	</div>
{/if}
