<script lang="ts">
	import { app } from '#lib/app.svelte.js';
	import { plugins } from '#lib/plugins.svelte.js';
	import PluginMount from '#lib/components/plugins/PluginMount.svelte';
	import { page } from '$app/state';

	const found = $derived(plugins.find(plugins.settingsPages, page.params.plugin ?? '', page.params.path ?? ''));
	const allowed = $derived(!!found && (!found.item.admin || app.isAdmin));
	const ctx = $derived(found ? { hub: found.item.hub, rest: found.rest, url: new URL(page.url.href) } : null);
</script>

<svelte:head><title>{found?.item.title ?? 'Plugin'} · ZenithHub</title></svelte:head>

{#if found && allowed && ctx}
	{#key `${page.url.pathname}${page.url.search}`}
		<PluginMount mount={found.item.mount} {ctx} plugin={found.item.plugin} />
	{/key}
{:else}
	<div class="card p-6 text-center text-sm text-zinc-400">
		{plugins.errors[page.params.plugin ?? ''] ? `The plugin failed to load: ${plugins.errors[page.params.plugin ?? '']}` : 'Nothing here. The plugin may be off or still loading.'}
	</div>
{/if}
