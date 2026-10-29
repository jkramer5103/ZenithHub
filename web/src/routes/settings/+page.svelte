<script lang="ts">
	import { api, errorMessage } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import DiscordBotCard from '#lib/components/DiscordBotCard.svelte';
	import SettingsEditor from '#lib/components/settings/SettingsEditor.svelte';
	import { toasts } from '#lib/toast.svelte.js';
	import { page } from '$app/state';

	const tab = $derived(page.url.searchParams.get('tab') === 'defaults' ? 'defaults' : 'general');

	const current = app.overview?.settings;
	let publicAddress = $state(current?.publicAddress ?? '');
	let outboundProxiesEnabled = $state(current?.outboundProxiesEnabled ?? false);
	let saving = $state(false);
	let error = $state('');

	async function save(e: SubmitEvent) {
		e.preventDefault();
		saving = true;
		error = '';
		try {
			await api.put('/settings/manager', { publicAddress: publicAddress.trim(), outboundProxiesEnabled });
			await app.refresh();
			toasts.success('Settings saved. Running instances pick them up on restart.');
		} catch (e) {
			error = errorMessage(e);
		} finally {
			saving = false;
		}
	}
</script>

<svelte:head><title>Settings · ZenithHub</title></svelte:head>

<h1 class="mb-4 text-2xl font-semibold">Settings</h1>

<div class="mb-6 flex gap-1 border-b border-zinc-800">
	{#each [{ id: 'general', label: 'General' }, { id: 'defaults', label: 'Defaults for all instances' }] as t (t.id)}
		<a
			href="?tab={t.id}"
			data-sveltekit-replacestate
			data-sveltekit-noscroll
			class="-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors {tab === t.id
				? 'border-emerald-500 text-zinc-100'
				: 'border-transparent text-zinc-400 hover:text-zinc-200'}">{t.label}</a
		>
	{/each}
</div>

{#if tab === 'defaults'}
	<p class="mb-6 max-w-2xl text-sm text-zinc-400">
		Every instance starts with these. Servers and instances can change any of them for themselves.
	</p>
	<SettingsEditor endpoint="/settings/global" scope="global" />
{:else}
<form onsubmit={save} class="card max-w-xl space-y-6 p-5">
	<div>
		<label class="label" for="public-address">Public address</label>
		<input id="public-address" class="input font-mono" bind:value={publicAddress} placeholder="mc.example.com" spellcheck="false" />
		<p class="mt-1 text-xs text-zinc-400">
			The address players type in Minecraft to reach the lobby. Add <span class="font-mono">:port</span> if it isn’t 25565.
		</p>
	</div>
	<label class="flex cursor-pointer items-start gap-3">
		<input type="checkbox" class="mt-1 size-4 accent-emerald-600" bind:checked={outboundProxiesEnabled} />
		<span>
			<span class="block text-sm font-medium">Allow outbound proxies</span>
			<span class="block text-xs text-zinc-400">
				Lets instances connect to their server through a SOCKS/HTTP proxy. When off, every instance connects directly.
			</span>
		</span>
	</label>
	{#if error}<p class="text-sm text-red-400">{error}</p>{/if}
	<button class="btn-primary" disabled={saving || !publicAddress.trim()}>Save</button>
</form>
<div class="mt-6"><DiscordBotCard /></div>
{/if}
