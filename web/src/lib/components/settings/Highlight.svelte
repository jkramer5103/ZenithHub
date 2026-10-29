<script lang="ts">
	import { highlight, type Query } from '#lib/settings-search.js';

	let { text, query }: { text: string; query: Query | null } = $props();
	const pieces = $derived(highlight(text, query));
</script>

{#each pieces as piece, i (i)}{#if piece.hit}<mark class="rounded-sm bg-amber-400/25 text-inherit">{piece.text}</mark>{:else}{piece.text}{/if}{/each}
