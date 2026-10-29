<script lang="ts">
	import { copyText } from '#lib/clipboard.js';
	import { toasts } from '#lib/toast.svelte.js';
	import { Check, Copy } from '@lucide/svelte';

	let {
		value,
		display,
		size = 'md',
		label = 'Copy'
	}: { value: string; display?: string; size?: 'sm' | 'md' | 'lg'; label?: string } = $props();

	let copied = $state(false);
	let text: HTMLElement | undefined = $state();

	async function copy() {
		if (await copyText(value)) {
			copied = true;
			setTimeout(() => (copied = false), 1500);
		} else {
			// at least leave it selected so Ctrl+C / long-press works
			if (text) window.getSelection()?.selectAllChildren(text);
			toasts.show('error', 'Couldn’t copy automatically. It’s selected, copy it yourself.');
		}
	}

	const textClass = $derived(
		size === 'lg'
			? 'px-3 py-4 text-3xl font-semibold tracking-[0.2em] justify-center'
			: size === 'md'
				? 'px-3 py-2.5 text-lg tracking-wider'
				: 'px-2.5 py-1.5 text-sm'
	);
</script>

<div class="flex w-full items-stretch overflow-hidden rounded-lg border border-zinc-700 bg-zinc-950 focus-within:border-zinc-500">
	<!-- select-all: one click selects the whole value, so manual copying is easy too -->
	<span bind:this={text} class="flex min-w-0 flex-1 items-center truncate font-mono select-all {textClass}">{display ?? value}</span>
	<button
		type="button"
		class="flex shrink-0 items-center gap-1.5 border-l {size === 'sm' ? 'px-2.5' : 'px-3'} border-zinc-700 text-sm text-zinc-400 hover:bg-zinc-800 hover:text-zinc-100"
		onclick={copy}
		title={label}
		aria-label={label}
	>
		{#if copied}<Check size={16} class="text-emerald-400" /> <span class="hidden text-emerald-400 {size === 'sm' ? '' : 'sm:inline'}">Copied</span>
		{:else}<Copy size={16} /> <span class="hidden {size === 'sm' ? '' : 'sm:inline'}">Copy</span>{/if}
	</button>
</div>
