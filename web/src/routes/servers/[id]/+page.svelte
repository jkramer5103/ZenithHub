<script lang="ts">
	import { app } from '#lib/app.svelte.js';
	import SettingsEditor from '#lib/components/settings/SettingsEditor.svelte';
	import { page } from '$app/state';
	import { ArrowLeft } from '@lucide/svelte';

	const id = $derived(page.params.id ?? '');
	const server = $derived(app.overview?.servers.find((s) => s.id === id));
</script>

<svelte:head><title>{server?.name ?? 'Server'} settings · ZenithHub</title></svelte:head>

<a href="/servers" class="mb-4 inline-flex items-center gap-1 text-sm text-zinc-400 hover:text-zinc-200"><ArrowLeft size={15} /> Servers</a>

{#if !server}
	<div class="py-16 text-center text-sm text-zinc-400">This server doesn't exist.</div>
{:else if !server.editable}
	<div class="py-16 text-center text-sm text-zinc-400">Only the admin can change settings for {server.name}.</div>
{:else}
	<h1 class="text-2xl font-semibold">{server.name}</h1>
	<p class="mt-1 mb-6 max-w-2xl text-sm text-zinc-400">
		Settings for every instance on {server.address}{server.port !== 25565 ? `:${server.port}` : ''}. They replace the
		{#if app.isAdmin}<a href="/settings?tab=defaults" class="text-emerald-400 hover:underline">defaults for all instances</a>{:else}defaults for all
			instances{/if}, and each instance can still change
		them for itself.
	</p>
	{#key id}
		<SettingsEditor endpoint="/servers/{id}/settings" scope="server" />
	{/key}
{/if}
