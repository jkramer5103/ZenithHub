<script lang="ts">
	import { api, errorMessage } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { toasts } from '#lib/toast.svelte.js';
	import type { SettingsView } from '#lib/types.js';
	import { LoaderCircle } from '@lucide/svelte';
	import Switch from './settings/Switch.svelte';

	const P = 'client.connectionProxy.';
	const TYPES: Record<string, string> = { SOCKS5: 'SOCKS5', SOCKS4: 'SOCKS4', HTTP: 'HTTP' };

	let loaded = $state(false);
	let enabled = $state(false);
	let type = $state('SOCKS5');
	let host = $state('');
	let port = $state(1080);
	let user = $state('');
	let password = $state('');
	let userProxiesAllowed = $state(app.overview?.settings?.userProxiesAllowed ?? false);
	let saving = $state(false);
	let error = $state('');

	// the global proxy is the global settings layer's connection proxy
	async function load() {
		try {
			const view = await api.get<SettingsView>('/settings/global');
			const v = view.values;
			enabled = v[P + 'enabled'] === true;
			type = String(v[P + 'type'] ?? 'SOCKS5');
			host = String(v[P + 'host'] ?? '');
			port = Number(v[P + 'port'] ?? 1080);
			user = String(v[P + 'user'] ?? '');
			password = String(v[P + 'password'] ?? '');
			loaded = true;
		} catch (e) {
			error = errorMessage(e);
		}
	}
	load();

	const invalid = $derived(enabled && (!host.trim() || !Number.isInteger(port) || port < 1 || port > 65535));

	async function save(e: SubmitEvent) {
		e.preventDefault();
		saving = true;
		error = '';
		try {
			await api.patch('/settings/global', {
				set: {
					[P + 'enabled']: enabled,
					[P + 'type']: type,
					[P + 'host']: host.trim(),
					[P + 'port']: port,
					[P + 'user']: user,
					[P + 'password']: password
				}
			});
			await api.put('/settings/manager', { userProxiesAllowed });
			await app.refresh();
			toasts.success('Proxy settings saved. Connected instances switch over when they next connect.');
		} catch (e) {
			error = errorMessage(e);
		} finally {
			saving = false;
		}
	}
</script>

<form onsubmit={save} class="card max-w-xl p-5">
	<div class="flex items-start justify-between gap-4">
		<div>
			<h2 class="font-medium">Global proxy</h2>
			<p class="mt-0.5 text-sm text-zinc-400">
				Sends all of every instance’s traffic through a SOCKS or HTTP proxy instead of this machine’s IP: the server
				connection, Microsoft and Xbox sign-in, token refreshes, the session server, skins and player lookups.
			</p>
		</div>
		{#if loaded}<Switch checked={enabled} label="Global proxy" onchange={(on) => (enabled = on)} />{/if}
	</div>

	{#if !loaded && !error}
		<div class="mt-4 flex items-center gap-2 text-sm text-zinc-500"><LoaderCircle size={16} class="animate-spin" /> Loading…</div>
	{:else if loaded}
		{#if enabled}
			<div class="mt-4 grid gap-3 sm:grid-cols-[8rem_1fr_6rem]">
				<div>
					<label class="label" for="proxy-type">Type</label>
					<select id="proxy-type" class="input" bind:value={type}>
						{#each Object.entries(TYPES) as [value, label] (value)}<option {value}>{label}</option>{/each}
					</select>
				</div>
				<div>
					<label class="label" for="proxy-host">Host</label>
					<input id="proxy-host" class="input font-mono" bind:value={host} placeholder="proxy.example.com" spellcheck="false" />
				</div>
				<div>
					<label class="label" for="proxy-port">Port</label>
					<input id="proxy-port" class="input font-mono" type="number" min="1" max="65535" bind:value={port} />
				</div>
			</div>
			<div class="mt-3 grid gap-3 sm:grid-cols-2">
				<div>
					<label class="label" for="proxy-user">Username</label>
					<input id="proxy-user" class="input font-mono" bind:value={user} placeholder="optional" autocomplete="off" spellcheck="false" />
				</div>
				<div>
					<label class="label" for="proxy-password">Password</label>
					<input id="proxy-password" class="input font-mono" type="password" bind:value={password} placeholder="optional" autocomplete="new-password" />
				</div>
			</div>
		{/if}

		<label class="mt-5 flex cursor-pointer items-start gap-3 border-t border-zinc-800 pt-4">
			<input type="checkbox" class="mt-1 size-4 accent-emerald-600" bind:checked={userProxiesAllowed} />
			<span>
				<span class="block text-sm font-medium">Let users set their own proxy</span>
				<span class="block text-xs text-zinc-400">
					{#if enabled}
						Users can point their instances at a different proxy, but can’t turn the proxy off.
					{:else}
						Users can send their instances through a proxy of their own.
					{/if}
					An account signs in through the proxy of its first instance. Off, every instance uses the global proxy{enabled ? '' : ' (none while it’s off)'}.
				</span>
			</span>
		</label>
	{/if}

	{#if error}<p class="mt-3 text-sm text-red-400">{error}</p>{/if}
	{#if loaded}<button class="btn-primary mt-4" disabled={saving || invalid}>Save</button>{/if}
</form>
