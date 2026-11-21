<script lang="ts">
	// a condition, inline: for "if", "repeat until" and "wait until"
	import { conditions, dimensions, makeCondition } from '#lib/flow-blocks.js';
	import type { FlowCondition } from '#lib/types.js';
	import { SELECT, SLOT, editor } from './context.js';

	let { cond = $bindable() }: { cond: FlowCondition } = $props();
	const ctx = editor();
	const groups = [...new Set(conditions.map((c) => c.group))];
</script>

<select class={SELECT} aria-label="Yes or not" bind:value={cond.not}>
	<option value={false}>yes:</option>
	<option value={true}>not:</option>
</select>
<select class={SELECT} aria-label="Condition" value={cond.kind} onchange={(e) => (cond = makeCondition((e.currentTarget as HTMLSelectElement).value, cond.not))}>
	{#each groups as g}
		<optgroup label={g}>
			{#each conditions.filter((c) => c.group === g) as c}<option value={c.kind}>{c.label}</option>{/each}
		</optgroup>
	{/each}
</select>

{#if cond.kind === 'health'}
	<select class={SELECT} aria-label="Hearts" value={Number(cond.below) / 2} onchange={(e) => (cond.below = Number((e.currentTarget as HTMLSelectElement).value) * 2)}>
		{#each [1, 2, 3, 4, 5, 6, 7, 8, 9, 10] as h}<option value={h}>{h} ♥</option>{/each}
	</select>
{:else if cond.kind === 'food'}
	<select class={SELECT} aria-label="Food" bind:value={cond.below}>
		{#each [2, 4, 6, 8, 10, 12, 14, 16, 18, 20] as f}<option value={f}>{f / 2} 🍗</option>{/each}
	</select>
{:else if cond.kind === 'text'}
	<input class="{SLOT} field-sizing-content min-w-24" placeholder="text" maxlength="100" aria-label="Text" bind:value={cond.contains} onfocus={() => ctx.focusText(null)} />
{:else if cond.kind === 'hasItem'}
	<input class="{SLOT} w-14 text-center" inputmode="numeric" aria-label="How many" bind:value={cond.count} />
	<input
		class="{SLOT} field-sizing-content min-w-28 font-mono text-xs"
		placeholder="item id"
		maxlength="74"
		autocapitalize="off"
		spellcheck="false"
		aria-label="Item"
		list="flow-items"
		bind:value={cond.item}
	/>
{:else if cond.kind === 'near'}
	<input class="{SLOT} w-16 text-center" inputmode="numeric" placeholder="x" aria-label="X" bind:value={cond.x} />
	<input class="{SLOT} w-16 text-center" inputmode="numeric" placeholder="z" aria-label="Z" bind:value={cond.z} />
	<span>within</span>
	<input class="{SLOT} w-14 text-center" inputmode="numeric" aria-label="Radius" bind:value={cond.radius} />
	<span>blocks</span>
{:else if cond.kind === 'dimension'}
	<select class={SELECT} aria-label="Dimension" bind:value={cond.is}>
		{#each dimensions as d}<option value={d.value}>{d.label}</option>{/each}
	</select>
{:else if cond.kind === 'var'}
	<input
		class="{SLOT} field-sizing-content min-w-16 font-mono text-xs"
		placeholder="name"
		maxlength="24"
		autocapitalize="off"
		spellcheck="false"
		aria-label="Variable"
		list="flow-vars"
		bind:value={cond.name}
	/>
	<select class={SELECT} aria-label="Compare" bind:value={cond.op}>
		<option value="=">is</option>
		<option value="!=">isn’t</option>
		<option value=">">is more than</option>
		<option value="<">is less than</option>
		<option value="contains">contains</option>
	</select>
	<input
		class="{SLOT} field-sizing-content min-w-12"
		placeholder="value"
		maxlength="100"
		aria-label="Value"
		bind:value={cond.value}
		onfocus={() => ctx.focusText({ obj: cond as Record<string, unknown>, key: 'value' })}
	/>
{:else if cond.kind === 'time'}
	<input type="time" class="{SLOT} [color-scheme:dark]" aria-label="From" required bind:value={cond.from} />
	<span>and</span>
	<input type="time" class="{SLOT} [color-scheme:dark]" aria-label="To" required bind:value={cond.to} />
{:else if cond.kind === 'chance'}
	<select class={SELECT} aria-label="Chance" bind:value={cond.percent}>
		{#each [5, 10, 20, 25, 33, 50, 66, 75, 90] as p}<option value={p}>{p}%</option>{/each}
	</select>
{/if}
