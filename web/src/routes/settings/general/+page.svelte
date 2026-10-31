<script lang="ts">
	import { api, errorMessage } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import DiscordBotCard from '#lib/components/DiscordBotCard.svelte';
	import ProxyCard from '#lib/components/ProxyCard.svelte';
	import ProxyPoolCard from '#lib/components/ProxyPoolCard.svelte';
	import { toasts } from '#lib/toast.svelte.js';

	const current = app.overview?.settings;
	let publicAddress = $state(current?.publicAddress ?? '');
	let saving = $state(false);
	let error = $state('');

	async function save(e: SubmitEvent) {
		e.preventDefault();
		saving = true;
		error = '';
		try {
			await api.put('/settings/manager', { publicAddress: publicAddress.trim() });
			await app.refresh();
			toasts.success('Settings saved. Running instances pick them up on restart.');
		} catch (e) {
			error = errorMessage(e);
		} finally {
			saving = false;
		}
	}
</script>

<svelte:head><title>General · ZenithHub</title></svelte:head>

<h1 class="mb-6 text-2xl font-semibold">General</h1>

<form onsubmit={save} class="card max-w-xl space-y-6 p-5">
	<div>
		<label class="label" for="public-address">Public address</label>
		<input id="public-address" class="input font-mono" bind:value={publicAddress} placeholder="mc.example.com" spellcheck="false" />
		<p class="mt-1 text-xs text-zinc-400">
			The address players type in Minecraft to reach the lobby. Add <span class="font-mono">:port</span> if it isn’t 25565.
		</p>
	</div>
	{#if error}<p class="text-sm text-red-400">{error}</p>{/if}
	<button class="btn-primary" disabled={saving || !publicAddress.trim()}>Save</button>
</form>
<div class="mt-6"><ProxyCard /></div>
<div class="mt-6"><ProxyPoolCard /></div>
<div class="mt-6"><DiscordBotCard /></div>
