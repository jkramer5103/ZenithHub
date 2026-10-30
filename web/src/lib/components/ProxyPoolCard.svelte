<script lang="ts">
	import { api, errorMessage } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { relative } from '#lib/format.js';
	import { toasts } from '#lib/toast.svelte.js';
	import type { ProxyPoolStatus } from '#lib/types.js';
	import { LoaderCircle, RefreshCw, Shuffle } from '@lucide/svelte';
	import Switch from './settings/Switch.svelte';

	const TYPES: Record<string, string> = { SOCKS5: 'SOCKS5', SOCKS4: 'SOCKS4', HTTP: 'HTTP' };

	let pool = $state<ProxyPoolStatus | null>(null);
	let url = $state('');
	let busy = $state<string | null>(null);
	let error = $state('');

	async function load() {
		try {
			pool = await api.get<ProxyPoolStatus>('/proxy-pool');
		} catch (e) {
			error = errorMessage(e);
		}
	}
	load();

	async function run(what: string, request: () => Promise<ProxyPoolStatus>, done?: string) {
		busy = what;
		error = '';
		try {
			pool = await request();
			await app.refresh();
			if (done) toasts.success(done);
		} catch (e) {
			error = errorMessage(e);
		} finally {
			busy = null;
		}
	}

	function saveUrl(e: SubmitEvent) {
		e.preventDefault();
		void run('url', () => api.put<ProxyPoolStatus>('/proxy-pool', { url: url.trim() }), 'Proxy list loaded').then(() => (url = ''));
	}

	async function reconnect() {
		busy = 'reconnect';
		try {
			const r = await api.post<{ reconnecting: number }>('/proxy-pool/reconnect', {});
			toasts.success(r.reconnecting ? `Reconnecting ${r.reconnecting} account${r.reconnecting === 1 ? '' : 's'}, one every few seconds` : 'Nothing is connected');
		} catch (e) {
			toasts.error(e);
		} finally {
			busy = null;
		}
	}

	const assigned = $derived(pool?.accounts.filter((a) => a.proxy) ?? []);
</script>

<section class="card max-w-xl p-5">
	<div class="flex items-start justify-between gap-4">
		<div>
			<h2 class="font-medium">Proxy pool</h2>
			<p class="mt-0.5 text-sm text-zinc-400">
				Gives every account a proxy of its own from a list, so each one connects from a different IP. An account keeps its proxy for as long
				as the list has it. While the pool is on it replaces the global proxy and users' own proxies.
			</p>
		</div>
		{#if pool}
			<Switch
				checked={pool.enabled}
				label="Proxy pool"
				disabled={busy !== null || (!pool.enabled && pool.size === 0)}
				onchange={(on) =>
					run(
						'enabled',
						() => api.put<ProxyPoolStatus>('/proxy-pool', { enabled: on }),
						on ? 'Proxy pool on. Connected accounts switch when they next connect.' : 'Proxy pool off'
					)}
			/>
		{/if}
	</div>

	{#if !pool && !error}
		<div class="mt-4 flex items-center gap-2 text-sm text-zinc-500"><LoaderCircle size={16} class="animate-spin" /> Loading…</div>
	{:else if pool}
		<form class="mt-4" onsubmit={saveUrl}>
			<label class="label" for="pool-url">{pool.hasUrl ? 'Replace the list URL' : 'List URL'}</label>
			<div class="flex gap-2">
				<input
					id="pool-url"
					class="input min-w-0 flex-1 font-mono"
					type="password"
					autocomplete="off"
					placeholder={pool.hasUrl ? 'Saved. Paste a new one to replace it' : 'https://proxy.webshare.io/api/v2/proxy/list/download/…'}
					bind:value={url}
				/>
				<button class="btn-secondary shrink-0" disabled={!url.trim() || busy !== null}>
					{busy === 'url' ? 'Loading…' : 'Load'}
				</button>
			</div>
			<p class="mt-1 text-xs text-zinc-500">
				A download link that returns one proxy per line, like <span class="font-mono">host:port:user:password</span>. It's kept as a secret file
				and read again every 6 hours.
			</p>
		</form>

		<div class="mt-4 grid grid-cols-3 divide-x divide-zinc-800 rounded-lg border border-zinc-800 text-center">
			<div class="px-2 py-2">
				<div class="font-semibold text-zinc-100 tabular-nums">{pool.size}</div>
				<div class="text-xs text-zinc-500">proxies</div>
			</div>
			<div class="px-2 py-2">
				<div class="font-semibold text-zinc-100 tabular-nums">{assigned.length}</div>
				<div class="text-xs text-zinc-500">in use</div>
			</div>
			<div class="px-2 py-2">
				<div class="font-semibold text-zinc-100 tabular-nums">{pool.free}</div>
				<div class="text-xs text-zinc-500">free</div>
			</div>
		</div>

		<div class="mt-3 flex flex-wrap items-center gap-2">
			<div class="min-w-0 flex-1">
				<label class="sr-only" for="pool-type">Type</label>
				<select
					id="pool-type"
					class="input w-32"
					value={pool.type}
					disabled={busy !== null}
					onchange={(e) => run('type', () => api.put<ProxyPoolStatus>('/proxy-pool', { type: (e.currentTarget as HTMLSelectElement).value }))}
				>
					{#each Object.entries(TYPES) as [value, label] (value)}<option {value}>{label}</option>{/each}
				</select>
			</div>
			{#if pool.hasUrl}
				<button class="btn-ghost" disabled={busy !== null} onclick={() => run('refresh', () => api.post<ProxyPoolStatus>('/proxy-pool/refresh', {}), 'List reloaded')}>
					<RefreshCw size={15} class={busy === 'refresh' ? 'animate-spin' : ''} /> Reload list
				</button>
			{/if}
			{#if pool.enabled}
				<button class="btn-secondary" disabled={busy !== null} onclick={reconnect}>Reconnect everyone now</button>
			{/if}
		</div>
		{#if pool.refreshedAt}<p class="mt-2 text-xs text-zinc-500">List loaded {relative(pool.refreshedAt)}.</p>{/if}
		{#if pool.error}<p class="mt-2 text-xs text-amber-300">Last reload failed: {pool.error}</p>{/if}

		{#if pool.accounts.length}
			<ul class="mt-4 divide-y divide-zinc-800 border-t border-zinc-800">
				{#each pool.accounts as a (a.accountId)}
					<li class="flex items-center gap-3 py-2 text-sm">
						<img
							src="https://mc-heads.net/avatar/{a.username}/24"
							alt=""
							class="size-6 shrink-0 rounded bg-zinc-800 [image-rendering:pixelated]"
							onerror={(e) => ((e.currentTarget as HTMLImageElement).style.visibility = 'hidden')}
						/>
						<div class="min-w-0 flex-1 sm:flex sm:items-center sm:gap-3">
							<div class="truncate text-zinc-200 sm:flex-1">{a.username}</div>
							{#if a.proxy}
								<div
									class="truncate font-mono text-xs {a.inPool ? 'text-zinc-400' : 'text-amber-300'}"
									title={a.inPool ? '' : 'No longer in the list: gets a new one when it next connects'}
								>
									{a.proxy}
								</div>
							{:else}
								<div class="text-xs text-zinc-500">{pool.enabled ? 'gets one when it connects' : 'none'}</div>
							{/if}
						</div>
						{#if a.proxy}
							<button
								class="btn-ghost p-1.5"
								title="Give it a different proxy"
								aria-label="Give {a.username} a different proxy"
								disabled={busy !== null}
								onclick={() => run('reassign', () => api.post<ProxyPoolStatus>(`/proxy-pool/reassign/${a.accountId}`, {}), `${a.username} gets a new proxy when it next connects`)}
							>
								<Shuffle size={14} />
							</button>
						{/if}
					</li>
				{/each}
			</ul>
		{/if}
	{/if}

	{#if error}<p class="mt-3 text-sm text-red-400">{error}</p>{/if}
</section>
