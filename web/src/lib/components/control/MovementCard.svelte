<script lang="ts">
	import { runAction } from '#lib/actions.js';
	import type { InstanceStatus } from '#lib/types.js';
	import { ChevronDown, Crosshair, LocateFixed } from '@lucide/svelte';

	let {
		instanceId,
		status,
		onchanged
	}: {
		instanceId: string;
		status: InstanceStatus | null;
		onchanged: () => void;
	} = $props();

	type Field =
		| { key: string; label: string; kind: 'int'; optional?: boolean; placeholder?: string }
		| { key: string; label: string; kind: 'text'; optional?: boolean; placeholder?: string; hint?: string }
		| { key: string; label: string; kind: 'side' };

	interface Mode {
		id: string;
		label: string;
		help: string;
		fields: Field[];
		/** a "use my position" button fills these x/y/z fields */
		position?: boolean;
		submit: string;
		build: (v: Record<string, string>) => Record<string, unknown> & { action: string };
	}

	const xyz = (optionalY = false): Field[] => [
		{ key: 'x', label: 'X', kind: 'int' },
		{ key: 'y', label: 'Y', kind: 'int', optional: optionalY, placeholder: optionalY ? 'any' : '' },
		{ key: 'z', label: 'Z', kind: 'int' }
	];
	const num = (s: string) => (s.trim() === '' ? null : Number(s));
	const opt = (s: string) => (s.trim() === '' ? null : s.trim());
	const at = (v: Record<string, string>) => ({ x: num(v.x), y: num(v.y), z: num(v.z) });

	const modes: Mode[] = [
		{
			id: 'goto',
			label: 'Go to',
			help: 'Walk to coordinates. Leave Y empty to stop at any height.',
			fields: xyz(true),
			position: true,
			submit: 'Go',
			build: (v) => ({ action: 'goto', ...at(v) })
		},
		{
			id: 'elytra',
			label: 'Fly',
			help: 'Fly there with an elytra. Equip one and bring fireworks.',
			fields: xyz(true),
			submit: 'Fly',
			build: (v) => ({ action: 'elytra', ...at(v) })
		},
		{
			id: 'follow',
			label: 'Follow',
			help: 'Follow a player around. Leave the name empty to follow the closest player.',
			fields: [{ key: 'player', label: 'Player', kind: 'text', optional: true, placeholder: 'closest player' }],
			submit: 'Follow',
			build: (v) => ({ action: 'follow', player: opt(v.player) })
		},
		{
			id: 'thisway',
			label: 'Walk',
			help: 'Walk this many blocks in the direction the bot is facing.',
			fields: [{ key: 'blocks', label: 'Blocks', kind: 'int', placeholder: '100' }],
			submit: 'Walk',
			build: (v) => ({ action: 'thisway', blocks: num(v.blocks) })
		},
		{
			id: 'mine',
			label: 'Mine',
			help: 'Find and mine a kind of block until told to stop.',
			fields: [{ key: 'block', label: 'Block', kind: 'text', placeholder: 'diamond_ore' }],
			submit: 'Mine',
			build: (v) => ({ action: 'mine', block: v.block })
		},
		{
			id: 'getTo',
			label: 'Find block',
			help: 'Walk to the nearest block of a kind, e.g. a crafting table.',
			fields: [{ key: 'block', label: 'Block', kind: 'text', placeholder: 'crafting_table' }],
			submit: 'Go',
			build: (v) => ({ action: 'getTo', block: v.block })
		},
		{
			id: 'pickup',
			label: 'Pick up',
			help: 'Collect items lying around. Leave it empty to pick up everything.',
			fields: [{ key: 'item', label: 'Item', kind: 'text', optional: true, placeholder: 'any item' }],
			submit: 'Pick up',
			build: (v) => ({ action: 'pickup', item: opt(v.item) })
		},
		{
			id: 'clickBlock',
			label: 'Use block',
			help: 'Walk to a block and click it: right click opens chests and presses buttons, left click hits it.',
			fields: [{ key: 'button', label: 'Click', kind: 'side' }, ...xyz()],
			position: true,
			submit: 'Go and click',
			build: (v) => ({ action: 'clickBlock', button: v.button, ...at(v) })
		},
		{
			id: 'clickEntity',
			label: 'Use entity',
			help: 'Walk to the nearest entity of a kind and click it, e.g. right click a villager to trade.',
			fields: [{ key: 'button', label: 'Click', kind: 'side' }, { key: 'entity', label: 'Entity', kind: 'text', placeholder: 'villager' }],
			submit: 'Go and click',
			build: (v) => ({ action: 'clickEntity', button: v.button, entity: v.entity })
		},
		{
			id: 'breakBlock',
			label: 'Break',
			help: 'Walk to a block and break it.',
			fields: xyz(),
			position: true,
			submit: 'Break',
			build: (v) => ({ action: 'breakBlock', ...at(v) })
		},
		{
			id: 'placeBlock',
			label: 'Place',
			help: 'Walk over and place a block from the inventory.',
			fields: [...xyz(), { key: 'item', label: 'Block', kind: 'text', placeholder: 'cobblestone' }],
			position: true,
			submit: 'Place',
			build: (v) => ({ action: 'placeBlock', ...at(v), item: v.item })
		},
		{
			id: 'near',
			label: 'Stay near',
			help: 'Get within some blocks of a spot.',
			fields: [...xyz(), { key: 'range', label: 'Within', kind: 'int', placeholder: '5' }],
			position: true,
			submit: 'Go',
			build: (v) => ({ action: 'near', ...at(v), range: num(v.range) })
		},
		{
			id: 'clearArea',
			label: 'Clear area',
			help: 'Break every block between two corners.',
			fields: [
				{ key: 'x1', label: 'From X', kind: 'int' },
				{ key: 'y1', label: 'Y', kind: 'int' },
				{ key: 'z1', label: 'Z', kind: 'int' },
				{ key: 'x2', label: 'To X', kind: 'int' },
				{ key: 'y2', label: 'Y', kind: 'int' },
				{ key: 'z2', label: 'Z', kind: 'int' }
			],
			submit: 'Clear',
			build: (v) => ({
				action: 'clearArea',
				from: { x: num(v.x1), y: num(v.y1), z: num(v.z1) },
				to: { x: num(v.x2), y: num(v.y2), z: num(v.z2) }
			})
		}
	];

	/** the rest sit behind "More", so the everyday ones are one tap away */
	const MAIN = ['goto', 'follow', 'mine', 'elytra'];
	const mainModes = modes.filter((m) => MAIN.includes(m.id));
	const moreModes = modes.filter((m) => !MAIN.includes(m.id));

	let modeId = $state('goto');
	const mode = $derived(modes.find((m) => m.id === modeId)!);
	let showMore = $state(false);
	// values are shared between modes, so coordinates typed for "Go to" are still there for "Break"
	let values = $state<Record<string, string>>({ button: 'right' });
	let busy = $state(false);

	const ready = $derived(
		mode.fields.every((f) => f.kind === 'side' || ('optional' in f && f.optional) || (values[f.key] ?? '').trim() !== '')
	);

	function usePosition() {
		if (!status?.position) return;
		values.x = String(Math.floor(status.position.x));
		values.y = String(Math.floor(status.position.y));
		values.z = String(Math.floor(status.position.z));
	}

	async function run(action: Record<string, unknown> & { action: string }) {
		busy = true;
		await runAction(instanceId, action);
		busy = false;
		onchanged();
	}
</script>

<section class="card p-4">
	{#snippet chip(m: Mode)}
		<button
			role="tab"
			aria-selected={modeId === m.id}
			class="shrink-0 rounded-full border px-2.5 py-1 text-xs font-medium {modeId === m.id
				? 'border-emerald-600 bg-emerald-950/60 text-emerald-200'
				: 'border-zinc-700 text-zinc-400 hover:border-zinc-500 hover:text-zinc-200'}"
			onclick={() => (modeId = m.id)}>{m.label}</button
		>
	{/snippet}
	<div class="mb-3 flex flex-wrap gap-1" role="tablist">
		{#each mainModes as m (m.id)}{@render chip(m)}{/each}
		{#if showMore || moreModes.some((m) => m.id === modeId)}
			{#each moreModes as m (m.id)}{@render chip(m)}{/each}
		{:else}
			<button
				type="button"
				class="inline-flex shrink-0 items-center gap-0.5 rounded-full px-2.5 py-1 text-xs font-medium text-zinc-400 hover:text-zinc-200"
				onclick={() => (showMore = true)}
			>
				More <ChevronDown size={13} />
			</button>
		{/if}
	</div>

	<form
		class="space-y-3"
		onsubmit={(e) => {
			e.preventDefault();
			if (ready) run(mode.build(values));
		}}
	>
		<p class="text-xs text-zinc-500">{mode.help}</p>
		<div class="grid grid-cols-3 gap-2">
			{#each mode.fields as f (f.key)}
				{#if f.kind === 'side'}
					<div class="col-span-3 flex gap-1">
						{#each ['right', 'left'] as side}
							<button
								type="button"
								class="flex-1 rounded-lg border px-3 py-1.5 text-sm {values.button === side
									? 'border-emerald-600 bg-emerald-950/50 text-emerald-200'
									: 'border-zinc-700 text-zinc-400 hover:text-zinc-200'}"
								onclick={() => (values.button = side)}>{side === 'right' ? 'Right click (use)' : 'Left click (hit)'}</button
							>
						{/each}
					</div>
				{:else}
					<label class={f.kind === 'text' ? 'col-span-3' : ''}>
						<span class="mb-1 block text-xs text-zinc-400">{f.label}</span>
						<input
							class="input"
							inputmode={f.kind === 'int' ? 'numeric' : 'text'}
							autocapitalize="off"
							autocomplete="off"
							spellcheck="false"
							placeholder={f.placeholder ?? ''}
							bind:value={values[f.key]}
						/>
					</label>
				{/if}
			{/each}
		</div>
		<div class="flex flex-wrap gap-2">
			<button class="btn-primary" disabled={!ready || busy}><Crosshair size={15} /> {mode.submit}</button>
			{#if mode.position && status?.position}
				<button type="button" class="btn-ghost" onclick={usePosition}><LocateFixed size={15} /> Use the bot's position</button>
			{/if}
		</div>
	</form>

</section>
