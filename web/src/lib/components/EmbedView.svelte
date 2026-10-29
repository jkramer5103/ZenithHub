<script lang="ts">
	import { discordMarkdown } from '#lib/format.js';
	import type { CommandResult } from '#lib/types.js';

	let { result }: { result: CommandResult } = $props();

	const color = $derived(
		result.embed?.color != null ? `#${(result.embed.color & 0xffffff).toString(16).padStart(6, '0')}` : '#52525b'
	);

	function reveal(e: MouseEvent) {
		const target = e.target as HTMLElement;
		if (target.dataset.spoiler !== undefined) target.classList.toggle('revealed');
	}
</script>

<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<div class="space-y-1 text-sm" onclick={reveal}>
	{#if result.embed}
		{@const embed = result.embed}
		<div class="rounded-md border-l-4 bg-zinc-900 px-3 py-2" style="border-color: {color}">
			{#if embed.title}<div class="font-semibold">{@html discordMarkdown(embed.title)}</div>{/if}
			{#if embed.description}<div class="mt-1 text-zinc-300">{@html discordMarkdown(embed.description)}</div>{/if}
			{#if embed.fields.length}
				<div class="mt-2 grid grid-cols-2 gap-x-4 gap-y-2 sm:grid-cols-3">
					{#each embed.fields as field}
						<div class={field.inline ? '' : 'col-span-full'}>
							<div class="text-xs font-semibold text-zinc-400">{@html discordMarkdown(field.name)}</div>
							<div class="text-zinc-200">{@html discordMarkdown(field.value)}</div>
						</div>
					{/each}
				</div>
			{/if}
		</div>
	{/if}
	{#each result.lines as line}
		<div class="font-mono text-xs text-zinc-300">{line}</div>
	{/each}
</div>
