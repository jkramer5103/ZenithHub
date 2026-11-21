<script lang="ts">
	import { blocks, categories, type BlockDef } from '#lib/flow-blocks.js';
	import type { Capability } from '#lib/types.js';
	import { Search } from '@lucide/svelte';
	import Modal from '../Modal.svelte';

	let { capabilities, onpick, onclose }: { capabilities: Capability[]; onpick: (def: BlockDef) => void; onclose: () => void } = $props();

	let query = $state('');
	const usable = $derived(blocks.filter((b) => capabilities.includes(b.needs)));
	const shown = $derived(query.trim() ? usable.filter((b) => b.label.toLowerCase().includes(query.trim().toLowerCase())) : usable);
</script>

<Modal title="Add a block" {onclose} wide>
	<label class="relative mb-4 block">
		<Search size={15} class="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-zinc-500" />
		<!-- svelte-ignore a11y_autofocus -->
		<input
			class="input pl-9"
			placeholder="Search blocks"
			aria-label="Search blocks"
			autofocus
			bind:value={query}
			onkeydown={(e) => e.key === 'Enter' && shown[0] && onpick(shown[0])}
		/>
	</label>
	<div class="space-y-4">
		{#each categories as c}
			{@const list = shown.filter((b) => b.cat === c.id)}
			{#if list.length}
				<section>
					<h3 class="mb-1.5 text-xs font-semibold tracking-wide text-zinc-500 uppercase">{c.label}</h3>
					<div class="flex flex-wrap gap-1.5">
						{#each list as b}
							<button type="button" class="rounded-lg px-3 py-1.5 text-sm font-medium text-white shadow-sm transition-transform hover:-translate-y-px {c.fill}" onclick={() => onpick(b)}
								>{b.label}</button
							>
						{/each}
					</div>
				</section>
			{/if}
		{/each}
		{#if !shown.length}<p class="text-sm text-zinc-500">No block matches “{query}”.</p>{/if}
	</div>
</Modal>
