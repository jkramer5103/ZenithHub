<script lang="ts">
	import { toasts } from '#lib/toast.svelte.js';
	import { CircleAlert, CircleCheck } from '@lucide/svelte';
</script>

<!-- above the phone's bottom nav, not over it -->
<div class="pointer-events-none fixed inset-x-0 bottom-[calc(4rem+env(safe-area-inset-bottom))] z-50 flex md:bottom-0 flex-col items-center gap-2 p-4 sm:items-end">
	{#each toasts.items as toast (toast.id)}
		<button
			class="pointer-events-auto flex max-w-md items-start gap-2 rounded-lg border px-4 py-3 text-left text-sm shadow-lg {toast.kind ===
			'error'
				? 'border-red-900 bg-red-950 text-red-200'
				: 'border-emerald-900 bg-emerald-950 text-emerald-200'}"
			onclick={() => toasts.dismiss(toast.id)}
		>
			{#if toast.kind === 'error'}<CircleAlert size={18} class="shrink-0" />{:else}<CircleCheck size={18} class="shrink-0" />{/if}
			<span>{toast.message}</span>
		</button>
	{/each}
</div>
