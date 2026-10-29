<script lang="ts">
	import type { ItemStack } from '#lib/types.js';

	let { item }: { item: ItemStack } = $props();
	let failed = $state(false);

	// two letters when the icon can't load (offline, or an item the icon site doesn't have)
	const initials = $derived(
		item.id
			.split('_')
			.slice(0, 2)
			.map((w) => w[0]?.toUpperCase() ?? '')
			.join('')
	);
</script>

{#if !failed}
	<img
		src="https://mc.nerothe.com/img/1.21.4/minecraft_{item.id}.png"
		alt=""
		loading="lazy"
		draggable="false"
		class="size-full object-contain [image-rendering:pixelated]"
		onerror={() => (failed = true)}
	/>
{:else}
	<span class="text-[11px] font-semibold text-zinc-300">{initials}</span>
{/if}
