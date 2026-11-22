<script lang="ts">
	// who or what to look for nearby, plus how far: for "comes within", "within … there is" and "look at"
	import { entityKinds } from '#lib/flow-blocks.js';
	import { SELECT, SLOT } from './context.js';

	let { filter = $bindable(), radius = true }: { filter: Record<string, unknown>; radius?: boolean } = $props();
</script>

<select
	class={SELECT}
	aria-label="Who or what"
	value={filter.what ?? 'anyone'}
	onchange={(e) => {
		const v = (e.currentTarget as HTMLSelectElement).value;
		filter.what = v;
		if (v === 'player' && !filter.name) filter.name = '';
		if (v === 'type' && !filter.mob) filter.mob = 'zombie';
	}}
>
	{#each entityKinds as k}<option value={k.value}>{k.label}</option>{/each}
</select>
{#if filter.what === 'player'}
	<input
		class="{SLOT} field-sizing-content min-w-20"
		placeholder="name"
		maxlength="16"
		autocapitalize="off"
		spellcheck="false"
		aria-label="Player name"
		bind:value={filter.name}
	/>
{:else if filter.what === 'type'}
	<input
		class="{SLOT} field-sizing-content min-w-20 font-mono text-xs"
		placeholder="mob id"
		maxlength="64"
		autocapitalize="off"
		spellcheck="false"
		aria-label="Mob"
		list="flow-mobs"
		bind:value={filter.mob}
	/>
{/if}
{#if radius}
	<span>within</span>
	<input class="{SLOT} w-12 text-center" inputmode="numeric" aria-label="Blocks away" bind:value={filter.radius} />
	<span>blocks</span>
{/if}
