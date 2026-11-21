<script lang="ts">
	import type { FlowStep } from '#lib/types.js';
	import { Plus } from '@lucide/svelte';
	import { editor } from './context.js';
	import Step from './Step.svelte';

	let { list }: { list: FlowStep[] } = $props();
	const ctx = editor();
	/** the end of the list takes drops too, so an empty "if" or loop can be filled by dragging */
	let over = $state(false);
</script>

<div class="flex flex-col gap-1">
	{#each list as step, i (step.id)}
		<Step {step} {list} index={i} />
	{/each}
	<button
		type="button"
		class="flex items-center gap-1.5 self-start rounded-lg border border-dashed px-2.5 py-1 text-xs transition-colors {over
			? 'border-white text-white'
			: 'border-zinc-700 text-zinc-500 hover:border-zinc-500 hover:text-zinc-200'}"
		onclick={() => ctx.pick(list, list.length)}
		ondragover={(e) => {
			if (!ctx.drag || !ctx.canDrop(list)) return;
			e.preventDefault();
			e.stopPropagation();
			over = true;
		}}
		ondragleave={() => (over = false)}
		ondrop={(e) => {
			if (!over) return;
			e.preventDefault();
			e.stopPropagation();
			over = false;
			ctx.drop(list, list.length);
		}}><Plus size={13} /> {over ? 'Drop here' : 'Add block'}</button
	>
</div>
