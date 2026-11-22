<script lang="ts" generics="C">
	import type { Cleanup } from '#lib/plugins.svelte.js';
	import { untrack } from 'svelte';

	/**
	 * Hands an element to a plugin's mount function and calls its cleanup when the element goes away. It mounts once:
	 * wrap it in a {#key} to mount again when something changes.
	 */
	let {
		mount,
		ctx,
		plugin,
		class: className = ''
	}: { mount: (el: HTMLElement, ctx: C) => void | Cleanup | Promise<void | Cleanup>; ctx: C; plugin: string; class?: string } = $props();

	let el: HTMLElement;
	let error = $state('');

	$effect(() => {
		const target = el;
		const current = untrack(() => ctx);
		const run = untrack(() => mount);
		let cleanup: void | Cleanup;
		let gone = false;
		error = '';
		Promise.resolve()
			.then(() => run(target, current))
			.then((c) => {
				if (gone) c?.();
				else cleanup = c;
			})
			.catch((e) => {
				console.error(`Plugin ${plugin} failed`, e);
				error = e instanceof Error ? e.message : String(e);
			});
		return () => {
			gone = true;
			try {
				cleanup?.();
			} catch (e) {
				console.error(`Plugin ${plugin} failed cleaning up`, e);
			}
			target.replaceChildren();
		};
	});
</script>

{#if error}
	<div class="rounded-lg border border-red-900/60 bg-red-950/40 px-3 py-2 text-sm text-red-300" role="alert">
		The plugin “{plugin}” failed: {error}
	</div>
{/if}
<div bind:this={el} class={className} data-plugin={plugin}></div>
