<script lang="ts">
	import { app } from '#lib/app.svelte.js';
	import { settingsSections } from '#lib/settings-sections.js';
	import { ChevronRight } from '@lucide/svelte';

	const sections = $derived(settingsSections.filter((s) => !s.admin || app.isAdmin));
</script>

<svelte:head><title>Settings · ZenithHub</title></svelte:head>

<h1 class="mb-4 text-2xl font-semibold">Settings</h1>
<ul class="card divide-y divide-zinc-800">
	{#each sections as s (s.href)}
		<li>
			<a href={s.href} class="flex items-center gap-3 px-4 py-3.5 hover:bg-zinc-900">
				<span class="flex size-9 shrink-0 items-center justify-center rounded-lg bg-zinc-800 text-zinc-300"><s.icon size={18} /></span>
				<span class="min-w-0 flex-1">
					<span class="block font-medium">{s.label}</span>
					<span class="block text-xs text-zinc-500">{s.description}</span>
				</span>
				<ChevronRight size={16} class="text-zinc-600" />
			</a>
		</li>
	{/each}
</ul>
