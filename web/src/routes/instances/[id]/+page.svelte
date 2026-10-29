<script lang="ts">
	import { app } from '#lib/app.svelte.js';
	import Console from '#lib/components/Console.svelte';
	import InstanceActions from '#lib/components/InstanceActions.svelte';
	import InstanceSettingsModal from '#lib/components/InstanceSettingsModal.svelte';
	import OwnerBadge from '#lib/components/OwnerBadge.svelte';
	import PhaseBadge from '#lib/components/PhaseBadge.svelte';
	import SettingsEditor from '#lib/components/settings/SettingsEditor.svelte';
	import SharingPanel from '#lib/components/SharingPanel.svelte';
	import CopyField from '#lib/components/CopyField.svelte';
	import { duration } from '#lib/format.js';
	import { page } from '$app/state';
	import { Activity, ArrowLeft, Gamepad2, Settings2, Share2, SlidersHorizontal, Terminal } from '@lucide/svelte';

	const id = $derived(page.params.id ?? '');
	const instance = $derived(app.instance(id));
	const server = $derived(app.overview?.servers.find((s) => s.id === instance?.serverId));
	const runtime = $derived(instance?.runtime);
	const status = $derived(runtime?.phase === 'RUNNING' ? runtime.status : null);
	let showSettings = $state(false);

	type Tab = 'overview' | 'settings' | 'sharing' | 'console';
	const tabs = $derived(
		[
			{ id: 'overview' as Tab, label: 'Overview', icon: Activity, show: true },
			{ id: 'settings' as Tab, label: 'Settings', icon: SlidersHorizontal, show: !!instance?.capabilities.includes('SETTINGS') },
			{ id: 'sharing' as Tab, label: 'Sharing', icon: Share2, show: !!instance?.capabilities.includes('SHARE') },
			{ id: 'console' as Tab, label: 'Console', icon: Terminal, show: app.devMode }
		].filter((t) => t.show)
	);
	const tab = $derived.by(() => {
		const requested = page.url.searchParams.get('tab');
		return tabs.find((t) => t.id === requested)?.id ?? 'overview';
	});

	const stats = $derived.by(() => {
		if (!status) return [];
		const rows: [string, string][] = [];
		if (status.inQueue) {
			rows.push(['Queue position', String(status.queuePosition)]);
			if (status.queueEtaSeconds > 0) rows.push(['Queue ETA', duration(status.queueEtaSeconds)]);
		}
		if (status.online) {
			rows.push(['Online for', duration(status.onlineSeconds)]);
			rows.push(['Health', `${Math.round(status.health)} / 20`]);
			rows.push(['Food', `${status.food} / 20`]);
			if (status.position) {
				rows.push(['Dimension', status.position.dimension.replace('minecraft:', '')]);
				rows.push(['Position', `${Math.round(status.position.x)}, ${Math.round(status.position.y)}, ${Math.round(status.position.z)}`]);
			}
			rows.push(['Ping', `${status.ping} ms`]);
			rows.push(['Server TPS', status.tps?.toFixed(1) ?? '-']);
			rows.push(['Controlled by', status.controller ?? 'nobody']);
			if (status.spectators.length) rows.push(['Spectators', status.spectators.join(', ')]);
		}
		rows.push(['Proxy uptime', duration(status.uptimeSeconds)]);
		return rows;
	});
</script>

<svelte:head><title>{instance?.name ?? 'Instance'} · ZenithProxy</title></svelte:head>

{#if !instance || !runtime}
	<div class="py-16 text-center text-sm text-zinc-400">
		This instance doesn't exist or isn't shared with you. <a href="/" class="text-emerald-400 underline">Back to instances</a>
	</div>
{:else}
	<a href="/" class="mb-4 inline-flex items-center gap-1 text-sm text-zinc-400 hover:text-zinc-200"><ArrowLeft size={15} /> Instances</a>

	<div class="mb-5 flex flex-wrap items-center gap-x-4 gap-y-3">
		<img
			src="https://mc-heads.net/avatar/{instance.accountName}/48"
			alt=""
			class="size-12 rounded-lg bg-zinc-800 [image-rendering:pixelated]"
		/>
		<div class="min-w-0 flex-1">
			<div class="flex flex-wrap items-center gap-2">
				<h1 class="truncate text-xl font-semibold">{instance.name}</h1>
				<PhaseBadge {runtime} />
				<OwnerBadge mine={instance.mine} ownerName={instance.ownerName} />
			</div>
			<div class="text-sm text-zinc-400">
				{instance.accountName} on {server ? `${server.name} (${server.address}${server.port !== 25565 ? `:${server.port}` : ''})` : 'unknown server'}
			</div>
		</div>
		<!-- its own row on phones so the name never gets squeezed -->
		<div class="flex w-full items-center gap-2 sm:w-auto">
			<InstanceActions {instance} />
			{#if instance.mine || app.isAdmin}
				<button class="btn-secondary px-2.5" title="Instance settings" onclick={() => (showSettings = true)}>
					<Settings2 size={16} />
				</button>
			{/if}
		</div>
	</div>

	{#if runtime.phase === 'BACKOFF' && runtime.lastError}
		<div class="mb-4 rounded-lg border border-red-900 bg-red-950/40 px-4 py-3 text-sm text-red-200">{runtime.lastError}</div>
	{:else if runtime.phase === 'STOPPED' && runtime.lastError}
		<div class="mb-4 rounded-lg border border-zinc-800 bg-zinc-900 px-4 py-3 text-sm text-zinc-300">{runtime.lastError}</div>
	{:else if status && !status.connected && status.lastDisconnectReason}
		<div class="mb-4 rounded-lg border border-amber-900/60 bg-amber-950/30 px-4 py-3 text-sm text-amber-200">
			<span class="font-medium">Disconnected:</span>
			{status.lastDisconnectReason}
		</div>
	{/if}

	{#if tabs.length > 1}
		<div class="mb-4 flex gap-1 border-b border-zinc-800">
			{#each tabs as t (t.id)}
				<a
					href="?tab={t.id}"
					data-sveltekit-replacestate
					data-sveltekit-noscroll
					class="-mb-px flex items-center gap-1.5 border-b-2 px-3 py-2 text-sm font-medium transition-colors {tab === t.id
						? 'border-emerald-500 text-zinc-100'
						: 'border-transparent text-zinc-400 hover:text-zinc-200'}"
				>
					<t.icon size={15} />
					{t.label}
				</a>
			{/each}
		</div>
	{/if}

	{#if tab === 'settings'}
		{#key id}
			<SettingsEditor endpoint="/instances/{id}/settings" scope="instance" />
		{/key}
	{:else if tab === 'sharing'}
		{#key id}
			<SharingPanel instanceId={id} />
		{/key}
	{:else if tab === 'console'}
		<div class="flex h-[calc(100dvh-24rem-env(safe-area-inset-bottom))] min-h-[320px] flex-col md:h-[calc(100dvh-16rem)]">
			{#key id}
				<Console
					instanceId={id}
					mode="console"
					running={runtime.phase === 'RUNNING'}
					canCommand={instance.capabilities.includes('COMMANDS')}
					canChat={false}
				/>
			{/key}
		</div>
	{:else}
		<div class="grid min-h-0 flex-1 gap-4 lg:grid-cols-[1fr_260px]">
			<aside class="lg:order-2">
				<div class="card p-4">
					<h2 class="mb-3 text-sm font-semibold text-zinc-300">Status</h2>
					{#if stats.length}
						<dl class="grid grid-cols-2 gap-x-3 gap-y-2 text-sm lg:grid-cols-1">
							{#each stats as [label, value]}
								<div class="flex flex-col lg:flex-row lg:justify-between lg:gap-2">
									<dt class="text-xs text-zinc-500 lg:text-sm">{label}</dt>
									<dd class="truncate text-zinc-200">{value}</dd>
								</div>
							{/each}
						</dl>
					{:else}
						<p class="text-sm text-zinc-500">
							{runtime.phase === 'RUNNING' ? 'Waiting for status…' : 'Connect to see live status.'}
						</p>
					{/if}
				</div>
				{#if app.overview?.publicAddress && (instance.capabilities.includes('PLAY') || instance.capabilities.includes('SPECTATE'))}
					<div class="card mt-4 p-4">
						<h2 class="mb-2 flex items-center gap-2 text-sm font-semibold text-zinc-300"><Gamepad2 size={15} /> Join in Minecraft</h2>
						<CopyField value={app.overview.publicAddress} size="sm" label="Copy address" />
						<p class="mt-2 text-xs text-zinc-500">
							Minecraft 1.20.5 or newer. Pick “{instance.name}” in the lobby menu{instance.capabilities.includes('PLAY') ? '' : ' to spectate'}. Type
							<span class="font-mono">/lobby</span> to go back.
						</p>
					</div>
				{/if}
			</aside>
			<div class="flex h-[calc(100dvh-24rem-env(safe-area-inset-bottom))] min-h-[320px] flex-col md:h-[calc(100dvh-16rem)] lg:order-1">
				{#key id}
					<Console
						instanceId={id}
						mode="chat"
						running={runtime.phase === 'RUNNING'}
						canCommand={false}
						canChat={instance.capabilities.includes('CHAT')}
					/>
				{/key}
			</div>
		</div>
	{/if}

	{#if showSettings}<InstanceSettingsModal {instance} onclose={() => (showSettings = false)} />{/if}
{/if}
