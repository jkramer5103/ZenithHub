<script lang="ts">
	// the settings of a trigger, inline: for the hat block and "wait for"
	import { afkOptions, makeTrigger, triggers, weekdays } from '#lib/flow-blocks.js';
	import type { FlowTrigger } from '#lib/types.js';
	import { SELECT, SLOT, editor } from './context.js';

	let { trigger = $bindable(), waiting = false }: { trigger: FlowTrigger; waiting?: boolean } = $props();
	const ctx = editor();

	const list = $derived(triggers.filter((t) => !waiting || t.waitable));
	const groups = $derived([...new Set(list.map((t) => t.group))]);
	// "a player" picks who; the label then reads "a stranger comes into view"
	const who = $derived(String(trigger.who ?? 'anyone'));
	const whoKind = $derived(who === 'anyone' || who === 'stranger' ? who : 'named');
	const days = $derived(String(trigger.days ?? ''));

	function toggleDay(d: string) {
		const next = days.includes(d) ? days.replace(d, '') : [...days, d].sort().join('');
		// no day at all would never run
		if (next) trigger.days = next;
	}
</script>

<select
	class="{SELECT} font-medium"
	aria-label={waiting ? 'What to wait for' : 'Trigger'}
	value={trigger.kind}
	onchange={(e) => (trigger = makeTrigger((e.currentTarget as HTMLSelectElement).value))}
>
	{#each groups as g}
		<optgroup label={g}>
			{#each list.filter((t) => t.group === g) as t}<option value={t.kind}>{t.label}</option>{/each}
		</optgroup>
	{/each}
</select>

{#if trigger.kind === 'chat' || trigger.kind === 'whisper'}
	<span>with</span>
	<input
		class="{SLOT} field-sizing-content min-w-24"
		placeholder="any text"
		maxlength="100"
		aria-label="Text it contains"
		bind:value={trigger.contains}
		onfocus={() => ctx.focusText(null)}
	/>
	<span>from</span>
	<input class="{SLOT} field-sizing-content min-w-20" placeholder="anyone" maxlength="16" autocapitalize="off" spellcheck="false" aria-label="From player" bind:value={trigger.from} />
{:else if trigger.kind === 'player' || trigger.kind === 'playerLeft'}
	<span>who is</span>
	<select
		class={SELECT}
		aria-label="Which player"
		value={whoKind}
		onchange={(e) => {
			const v = (e.currentTarget as HTMLSelectElement).value;
			trigger.who = v === 'named' ? '' : v;
		}}
	>
		<option value="stranger">not a friend</option>
		<option value="anyone">anyone</option>
		<option value="named">this player</option>
	</select>
	{#if whoKind === 'named'}
		<input class="{SLOT} field-sizing-content min-w-24" placeholder="name" maxlength="16" autocapitalize="off" spellcheck="false" aria-label="Player name" bind:value={trigger.who} />
	{/if}
{:else if trigger.kind === 'health'}
	<select class={SELECT} aria-label="Hearts" value={Number(trigger.below) / 2} onchange={(e) => (trigger.below = Number((e.currentTarget as HTMLSelectElement).value) * 2)}>
		{#each [1, 2, 3, 4, 5, 6, 7, 8, 9] as h}<option value={h}>{h} ♥</option>{/each}
	</select>
{:else if trigger.kind === 'food'}
	<select class={SELECT} aria-label="Food" bind:value={trigger.below}>
		{#each [2, 4, 6, 8, 10, 12, 14, 16, 18] as f}<option value={f}>{f / 2} 🍗</option>{/each}
	</select>
{:else if trigger.kind === 'every' || trigger.kind === 'onlineFor' || trigger.kind === 'unattended'}
	<input class="{SLOT} w-16 text-center" inputmode="numeric" aria-label="Minutes" bind:value={trigger.minutes} />
	<span>minutes</span>
{:else if trigger.kind === 'proxyJoin' || trigger.kind === 'proxyLeave'}
	<span>named</span>
	<input
		class="{SLOT} field-sizing-content min-w-20"
		placeholder="anyone"
		maxlength="16"
		autocapitalize="off"
		spellcheck="false"
		aria-label="Player name"
		value={trigger.who === 'anyone' ? '' : trigger.who}
		oninput={(e) => (trigger.who = (e.currentTarget as HTMLInputElement).value.trim() || 'anyone')}
	/>
{:else if trigger.kind === 'arrived'}
	<span>x</span>
	<input class="{SLOT} w-20 text-center" inputmode="numeric" aria-label="X" bind:value={trigger.x} />
	<span>z</span>
	<input class="{SLOT} w-20 text-center" inputmode="numeric" aria-label="Z" bind:value={trigger.z} />
	<span>within</span>
	<input class="{SLOT} w-14 text-center" inputmode="numeric" aria-label="Radius" bind:value={trigger.radius} />
	<span>blocks</span>
{:else if trigger.kind === 'daily'}
	<span>at</span>
	<input type="time" class="{SLOT} [color-scheme:dark]" aria-label="Time of day" required bind:value={trigger.at} />
	<span class="inline-flex flex-wrap gap-0.5" role="group" aria-label="Days">
		{#each weekdays as d}
			<button
				type="button"
				class="rounded px-1.5 py-0.5 text-xs {days.includes(d.value) ? 'bg-black/40 text-white' : 'bg-black/10 text-white/45 hover:text-white/80'}"
				aria-pressed={days.includes(d.value)}
				onclick={() => toggleDay(d.value)}>{d.label}</button
			>
		{/each}
	</span>
{:else if trigger.kind === 'receive'}
	<input
		class="{SLOT} field-sizing-content min-w-16"
		placeholder="name"
		maxlength="32"
		aria-label="Broadcast name"
		bind:value={trigger.name}
		onfocus={() => ctx.focusText(null)}
	/>
{:else if trigger.kind === 'queueBelow'}
	<input class="{SLOT} w-16 text-center" inputmode="numeric" aria-label="Queue position" bind:value={trigger.position} />
{:else if trigger.kind === 'left'}
	<span>with a reason containing</span>
	<input
		class="{SLOT} field-sizing-content min-w-24"
		placeholder="anything"
		maxlength="100"
		aria-label="Reason contains"
		bind:value={trigger.reason}
		onfocus={() => ctx.focusText(null)}
	/>
{:else if trigger.kind === 'controlled'}
	<span>:</span>
	<select class={SELECT} aria-label="Start or stop" bind:value={trigger.on}>
		<option value="start">someone starts</option>
		<option value="stop">everyone stopped</option>
	</select>
{:else if trigger.kind === 'afk'}
	<span>:</span>
	<select class={SELECT} aria-label="AFK zone change" bind:value={trigger.on}>
		{#each afkOptions as o}<option value={o.value}>{o.label}</option>{/each}
	</select>
{/if}
