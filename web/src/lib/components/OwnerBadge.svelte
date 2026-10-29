<script lang="ts">
	import { Crown, UserRound } from '@lucide/svelte';

	let { mine, ownerName }: { mine: boolean; ownerName: string | null } = $props();
</script>

{#if mine}
	<span class="inline-flex items-center gap-1 rounded-full bg-emerald-500/10 px-2 py-0.5 text-xs font-medium text-emerald-400 ring-1 ring-emerald-500/25 ring-inset">
		<Crown size={12} /> Yours
	</span>
{:else}
	<span
		class="inline-flex max-w-full items-center gap-1 rounded-full bg-sky-500/10 px-2 py-0.5 text-xs font-medium text-sky-300 ring-1 ring-sky-500/25 ring-inset"
		title={ownerName ? `Owned by ${ownerName}` : 'Owner unknown'}
	>
		<UserRound size={12} class="shrink-0" /> <span class="truncate">{ownerName ? `${ownerName}’s` : 'Shared'}</span>
	</span>
{/if}
