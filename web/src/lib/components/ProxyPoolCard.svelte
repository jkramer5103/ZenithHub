<script lang="ts">
	import { api, errorMessage } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { relative } from '#lib/format.js';
	import { toasts } from '#lib/toast.svelte.js';
	import type { ProxyPoolStatus } from '#lib/types.js';
	import { LoaderCircle, RefreshCw, Shuffle, SlidersHorizontal } from '@lucide/svelte';
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

	const assigned = $derived(pool?.accounts.filter((a) => a.proxy && !a.override && !a.lan) ?? []);

	type Account = ProxyPoolStatus['accounts'][number];

	// the override editor, for one account at a time
	let editing = $state<string | null>(null);
	let form = $state({ mode: 'pool', type: 'SOCKS5', host: '', port: '', user: '', password: '' });

	function edit(a: Account) {
		if (editing === a.accountId) {
			editing = null;
			return;
		}
		const o = a.override;
		form = {
			mode: !o ? 'pool' : o.type === 'DIRECT' ? 'direct' : 'custom',
			type: o && o.type !== 'DIRECT' ? o.type : 'SOCKS5',
			host: o?.host ?? '',
			port: o?.port ? String(o.port) : '',
			user: o?.user ?? '',
			password: ''
		};
		editing = a.accountId;
	}

	async function saveOverride(e: SubmitEvent, a: Account) {
		e.preventDefault();
		const path = `/proxy-overrides/${a.accountId}`;
		const request =
			form.mode === 'pool'
				? () => api.del<ProxyPoolStatus>(path)
				: form.mode === 'direct'
					? () => api.put<ProxyPoolStatus>(path, { type: 'DIRECT' })
					: () =>
							api.put<ProxyPoolStatus>(path, {
								type: form.type,
								host: form.host.trim(),
								port: Number(form.port),
								user: form.user.trim(),
								password: form.password
							});
		await run('override', request, `${a.username} uses the new proxy when it next connects`);
		if (!error) editing = null;
	}

	const customValid = $derived(form.mode !== 'custom' || (form.host.trim() !== '' && Number(form.port) >= 1 && Number(form.port) <= 65535));
</script>

<section class="card max-w-xl p-5">
	<div class="flex items-start justify-between gap-4">
		<div>
			<h2 class="font-medium">Proxy pool</h2>
			<p class="mt-0.5 text-sm text-zinc-400">
				Gives every account a proxy of its own from a list, so each one connects from a different IP. An account keeps its proxy for as long
				as the list has it. While the pool is on it replaces the global proxy and users' own proxies. Use
				<SlidersHorizontal size={12} class="inline align-[-1px]" /> to give one account its own proxy or none. Accounts whose servers are on your local network
				always connect directly.
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
					<li class="py-2 text-sm">
						<div class="flex items-center gap-3">
							<img
								src="https://mc-heads.net/avatar/{a.username}/24"
								alt=""
								class="size-6 shrink-0 rounded bg-zinc-800 [image-rendering:pixelated]"
								onerror={(e) => ((e.currentTarget as HTMLImageElement).style.visibility = 'hidden')}
							/>
							<div class="min-w-0 flex-1 sm:flex sm:items-center sm:gap-3">
								<div class="truncate text-zinc-200 sm:flex-1">{a.username}</div>
								{#if a.lan}
									<div class="text-xs text-emerald-300" title="Its servers are on your local network, so it never uses a proxy">LAN server: direct</div>
								{:else if a.override}
									<div class="flex min-w-0 items-center gap-1.5 text-xs text-sky-300">
										<span class="shrink-0 rounded bg-sky-500/15 px-1.5 py-0.5 text-[11px] font-medium">Admin</span>
										{#if a.override.type === 'DIRECT'}
											<span>no proxy</span>
										{:else}
											<span class="truncate font-mono">{a.override.type} {a.override.host}:{a.override.port}</span>
										{/if}
									</div>
								{:else if a.proxy}
									<div
										class="truncate font-mono text-xs {a.inPool ? 'text-zinc-400' : 'text-amber-300'}"
										title={a.inPool ? '' : 'No longer in the list: gets a new one when it next connects'}
									>
										{a.proxy}
									</div>
								{:else}
									<div class="text-xs text-zinc-500">{pool.enabled ? 'gets one when it connects' : 'global or own proxy'}</div>
								{/if}
							</div>
							{#if a.proxy && !a.override && !a.lan}
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
							<button
								class="btn-ghost p-1.5 {editing === a.accountId ? 'bg-zinc-800 text-zinc-100' : ''}"
								title="Choose this account's proxy"
								aria-label="Choose the proxy for {a.username}"
								aria-expanded={editing === a.accountId}
								disabled={busy !== null}
								onclick={() => edit(a)}
							>
								<SlidersHorizontal size={14} />
							</button>
						</div>
						{#if editing === a.accountId}
							<form class="mt-3 mb-1 space-y-3 rounded-lg border border-zinc-800 bg-zinc-900/60 p-3" onsubmit={(e) => saveOverride(e, a)}>
								<fieldset>
									<legend class="label">Proxy for {a.username}</legend>
									<div class="grid gap-1.5 sm:grid-cols-3">
										{#each [['pool', 'Default'], ['direct', 'No proxy'], ['custom', 'Custom']] as [value, label] (value)}
											<label
												class="flex cursor-pointer items-center gap-2 rounded-md border px-2.5 py-1.5 {form.mode === value
													? 'border-sky-500/60 bg-sky-500/10 text-zinc-100'
													: 'border-zinc-800 text-zinc-300'}"
											>
												<input type="radio" class="accent-sky-500" name="mode-{a.accountId}" {value} bind:group={form.mode} />
												{label}
											</label>
										{/each}
									</div>
									<p class="mt-1.5 text-xs text-zinc-500">
										{#if form.mode === 'pool'}
											{pool.enabled ? 'A proxy from the pool.' : 'The global proxy, or the one its owner set.'}
										{:else if form.mode === 'direct'}
											Connects from this server's own IP, whatever the pool or its owner set.
										{:else}
											Only for this account, over the pool and its owner's proxy. Its owner can't see or change it.
										{/if}
									</p>
								</fieldset>
								{#if form.mode === 'custom'}
									<div class="grid grid-cols-[7rem_1fr_5.5rem] gap-2 max-sm:grid-cols-[1fr_5.5rem]">
										<div class="max-sm:col-span-2">
											<label class="label" for="o-type">Type</label>
											<select id="o-type" class="input" bind:value={form.type}>
												{#each Object.entries(TYPES) as [value, label] (value)}<option {value}>{label}</option>{/each}
											</select>
										</div>
										<div class="min-w-0">
											<label class="label" for="o-host">Host</label>
											<input id="o-host" class="input font-mono" bind:value={form.host} placeholder="proxy.example.com" spellcheck="false" autocomplete="off" />
										</div>
										<div>
											<label class="label" for="o-port">Port</label>
											<input id="o-port" class="input font-mono" inputmode="numeric" bind:value={form.port} placeholder="1080" autocomplete="off" />
										</div>
									</div>
									<div class="grid gap-2 sm:grid-cols-2">
										<div>
											<label class="label" for="o-user">Username <span class="text-zinc-500">(optional)</span></label>
											<input id="o-user" class="input font-mono" bind:value={form.user} spellcheck="false" autocomplete="off" />
										</div>
										<div>
											<label class="label" for="o-password">Password <span class="text-zinc-500">(optional)</span></label>
											<input
												id="o-password"
												class="input font-mono"
												type="password"
												bind:value={form.password}
												autocomplete="new-password"
												placeholder={a.override?.hasPassword ? 'Saved. Leave blank to keep' : ''}
											/>
										</div>
									</div>
								{/if}
								{#if a.lan}
									<p class="text-xs text-emerald-300">Its servers are on your local network, so it connects directly there anyway.</p>
								{/if}
								<div class="flex justify-end gap-2">
									<button type="button" class="btn-ghost" onclick={() => (editing = null)}>Cancel</button>
									<button class="btn-primary" disabled={busy !== null || !customValid}>{busy === 'override' ? 'Saving…' : 'Save'}</button>
								</div>
							</form>
						{/if}
					</li>
				{/each}
			</ul>
		{/if}
	{/if}

	{#if error}<p class="mt-3 text-sm text-red-400">{error}</p>{/if}
</section>
