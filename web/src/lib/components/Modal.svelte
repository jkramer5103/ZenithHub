<script lang="ts">
	import { X } from '@lucide/svelte';
	import type { Snippet } from 'svelte';

	let {
		title,
		onclose,
		children,
		wide = false
	}: { title: string; onclose: () => void; children: Snippet; wide?: boolean } = $props();
</script>

<svelte:window onkeydown={(e) => e.key === 'Escape' && onclose()} />

<div class="fixed inset-0 z-40 flex items-end justify-center bg-black/60 backdrop-blur-sm sm:items-center sm:p-4">
	<button class="absolute inset-0 cursor-default" aria-label="Close" onclick={onclose}></button>
	<div
		role="dialog"
		aria-modal="true"
		aria-label={title}
		class="relative max-h-[92dvh] w-full overflow-y-auto rounded-t-2xl border border-zinc-800 bg-zinc-900 shadow-2xl sm:rounded-2xl {wide ? 'sm:max-w-2xl' : 'sm:max-w-md'}"
	>
		<div class="sticky top-0 z-10 flex items-center justify-between border-b border-zinc-800 bg-zinc-900 px-5 py-3">
			<h2 class="text-base font-semibold">{title}</h2>
			<button class="btn-ghost -mr-2 p-1.5" aria-label="Close" onclick={onclose}><X size={18} /></button>
		</div>
		<div class="p-5 pb-[calc(1.25rem+env(safe-area-inset-bottom))] sm:pb-5">
			{@render children()}
		</div>
	</div>
</div>
