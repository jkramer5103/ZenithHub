<script lang="ts">
	import { Puzzle } from '@lucide/svelte';

	/** A plugin's SVG icon, or a puzzle piece without one. */
	let { svg, size = 16, class: className = '' }: { svg?: string; size?: number; class?: string } = $props();
</script>

{#if svg}
	<span class="inline-flex shrink-0 items-center justify-center {className}" style="width:{size}px;height:{size}px" aria-hidden="true">
		<!-- eslint-disable-next-line svelte/no-at-html-tags -- plugins are trusted code -->
		{@html svg}
	</span>
{:else}
	<Puzzle {size} class={className} />
{/if}

<style>
	span :global(svg) {
		width: 100%;
		height: 100%;
	}
</style>
