<script lang="ts">
	import { app } from '#lib/app.svelte.js';
	import { settingsSections } from '#lib/settings-sections.js';
	import { page } from '$app/state';
	import { ArrowLeft } from '@lucide/svelte';
	import type { LayoutProps } from './$types';

	let { children }: LayoutProps = $props();

	const sections = $derived(settingsSections.filter((s) => !s.admin || app.isAdmin));
	const index = $derived(page.url.pathname.replace(/\/$/, '') === '/settings');
	// one level up: a server's settings go back to Servers, a section to the list (phones only, wider screens have the sidebar)
	const section = $derived(sections.find((s) => page.url.pathname.startsWith(s.href + '/')));
	const back = $derived(section ? { href: section.href, label: section.label } : { href: '/settings', label: 'Settings' });
</script>

<div class="md:flex md:items-start md:gap-8">
	<aside class="sticky top-20 hidden w-48 shrink-0 md:block">
		<h2 class="mb-2 px-3 text-xs font-semibold tracking-wide text-zinc-500 uppercase">Settings</h2>
		<nav class="flex flex-col gap-0.5">
			{#each sections as s (s.href)}
				<a
					href={s.href}
					class="btn justify-start px-3 py-2 {page.url.pathname.startsWith(s.href)
						? 'bg-zinc-800 text-zinc-100'
						: 'text-zinc-400 hover:bg-zinc-900 hover:text-zinc-100'}"
				>
					<s.icon size={16} />
					{s.label}
				</a>
			{/each}
		</nav>
	</aside>
	<div class="min-w-0 flex-1">
		{#if !index}
			<a href={back.href} class="mb-4 inline-flex items-center gap-1 text-sm text-zinc-400 hover:text-zinc-200 {section ? '' : 'md:hidden'}"
				><ArrowLeft size={15} /> {back.label}</a
			>
		{/if}
		{@render children()}
	</div>
</div>
