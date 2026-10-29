<script lang="ts">
	import { runAction } from '#lib/actions.js';
	import type { Inventory, ItemStack } from '#lib/types.js';
	import { ArrowDownToLine, ArrowRightLeft, ArrowUpFromLine, Hand, PackageMinus, Trash2, X } from '@lucide/svelte';
	import ItemIcon from './ItemIcon.svelte';

	let {
		instanceId,
		inventory,
		canAct,
		onchanged
	}: { instanceId: string; inventory: Inventory; canAct: boolean; onchanged: () => void } = $props();

	let selected = $state<number | null>(null);
	let moving = $state(false);
	let busy = $state(false);

	const isPlayer = $derived(inventory.containerId === 0);
	// slot numbers by section, the way Minecraft lays them out
	const containerSlots = $derived(isPlayer ? [] : range(0, inventory.size - 36));
	const mainSlots = $derived(range(inventory.hotbarStart - 27, inventory.hotbarStart));
	const hotbarSlots = $derived(range(inventory.hotbarStart, inventory.hotbarStart + 9));
	const armorSlots = [5, 6, 7, 8];
	const armorLabels = ['Helmet', 'Chestplate', 'Leggings', 'Boots'];

	const selectedItem = $derived(selected == null ? null : (inventory.slots[selected] ?? null));
	const selectedIsHotbar = $derived(selected != null && selected >= inventory.hotbarStart && selected < inventory.hotbarStart + 9);

	// a different container opened (or closed): the old selection means nothing now.
	// Every poll brings a new inventory object, so compare the id rather than the object.
	let containerId = inventory.containerId;
	$effect.pre(() => {
		if (inventory.containerId === containerId) return;
		containerId = inventory.containerId;
		selected = null;
		moving = false;
	});

	function range(from: number, to: number) {
		return Array.from({ length: Math.max(0, to - from) }, (_, i) => from + i);
	}

	function itemName(item: ItemStack) {
		const name = item.id.replace(/_/g, ' ');
		return item.name ? `${item.name} (${name})` : name.charAt(0).toUpperCase() + name.slice(1);
	}

	async function act(action: Record<string, unknown> & { action: string }) {
		busy = true;
		const ok = await runAction(instanceId, action, true);
		busy = false;
		if (ok) onchanged();
		return ok;
	}

	async function pick(slot: number) {
		if (!canAct) return;
		if (moving && selected != null) {
			const from = selected;
			moving = false;
			selected = null;
			if (from !== slot) await act({ action: 'swap', from, to: slot });
			return;
		}
		selected = selected === slot ? null : slot;
	}

	async function drop(stack: boolean) {
		if (selected == null) return;
		if (await act({ action: 'drop', slot: selected, stack })) selected = null;
	}
</script>

{#snippet slot(n: number, title?: string)}
	{@const item = inventory.slots[n]}
	<button
		type="button"
		class="relative flex aspect-square items-center justify-center rounded-md border p-1
			{selected === n
			? 'border-emerald-400 bg-emerald-950/60 ring-1 ring-emerald-400'
			: moving
				? 'border-zinc-600 bg-zinc-800 hover:border-emerald-500'
				: 'border-zinc-700/80 bg-zinc-800/70 hover:border-zinc-500'}
			{n === inventory.heldSlot ? 'outline-2 outline-offset-1 outline-zinc-300/70' : ''}"
		title={item ? `${itemName(item)}${item.count > 1 ? ` ×${item.count}` : ''}` : (title ?? 'Empty')}
		aria-label={item ? itemName(item) : (title ?? `Empty slot ${n}`)}
		disabled={!canAct}
		onclick={() => pick(n)}
	>
		{#if item}
			<ItemIcon {item} />
			{#if item.count > 1}
				<span class="absolute right-0.5 bottom-0 text-[11px] font-bold text-white [text-shadow:1px_1px_0_#000]">{item.count}</span>
			{/if}
		{/if}
	</button>
{/snippet}

<div class="space-y-3">
	{#if !isPlayer}
		<div class="flex flex-wrap items-center gap-2">
			<div class="min-w-0 flex-1 basis-full truncate text-sm font-medium text-zinc-200 sm:basis-0">{inventory.title || 'Container'}</div>
			{#if canAct}
				<button class="btn-secondary px-2.5 py-1.5 text-xs" disabled={busy} onclick={() => act({ action: 'withdraw' })}>
					<ArrowDownToLine size={14} /> Take all
				</button>
				<button class="btn-secondary px-2.5 py-1.5 text-xs" disabled={busy} onclick={() => act({ action: 'deposit' })}>
					<ArrowUpFromLine size={14} /> Put all in
				</button>
				<button class="btn-ghost px-2.5 py-1.5 text-xs" disabled={busy} onclick={() => act({ action: 'closeContainer' })}>
					<X size={14} /> Close
				</button>
			{/if}
		</div>
		<div class="grid grid-cols-9 gap-1">
			{#each containerSlots as n (n)}{@render slot(n)}{/each}
		</div>
		<div class="text-xs font-medium tracking-wide text-zinc-500 uppercase">Inventory</div>
	{:else}
		<div class="grid grid-cols-9 gap-1">
			{#each armorSlots as n, i (n)}{@render slot(n, armorLabels[i])}{/each}
			<div class="col-span-4"></div>
			{@render slot(45, 'Off hand')}
		</div>
	{/if}

	<div class="grid grid-cols-9 gap-1">
		{#each mainSlots as n (n)}{@render slot(n)}{/each}
	</div>
	<div class="grid grid-cols-9 gap-1">
		{#each hotbarSlots as n (n)}{@render slot(n)}{/each}
	</div>

	{#if canAct}
		<div class="flex min-h-10 flex-wrap items-center gap-2 rounded-lg border border-zinc-800 bg-zinc-950/50 px-3 py-2 text-sm">
			{#if moving}
				<ArrowRightLeft size={15} class="text-emerald-400" />
				<span class="flex-1 text-zinc-300">Pick the slot to move it to. Whatever's there swaps places.</span>
				<button class="btn-ghost px-2 py-1 text-xs" onclick={() => (moving = false)}>Cancel</button>
			{:else if selected == null}
				<span class="text-zinc-500">Tap a slot to hold, move or drop it.</span>
			{:else}
				<span class="flex w-full min-w-0 items-center gap-2">
					<span class="min-w-0 flex-1 truncate text-zinc-200">
						{selectedItem ? itemName(selectedItem) : 'Empty slot'}{selectedItem && selectedItem.count > 1 ? ` ×${selectedItem.count}` : ''}
					</span>
					<button class="btn-ghost -mr-1.5 p-1" aria-label="Deselect" onclick={() => (selected = null)}><X size={14} /></button>
				</span>
				{#if selectedIsHotbar && selected !== inventory.heldSlot}
					<button
						class="btn-secondary px-2.5 py-1.5 text-xs"
						disabled={busy}
						onclick={() => act({ action: 'hold', slot: selected })}
					>
						<Hand size={14} /> Hold
					</button>
				{/if}
				<button class="btn-secondary px-2.5 py-1.5 text-xs" disabled={busy} onclick={() => (moving = true)}>
					<ArrowRightLeft size={14} /> Move
				</button>
				{#if selectedItem}
					{#if selectedItem.count > 1}
						<button class="btn-secondary px-2.5 py-1.5 text-xs" disabled={busy} onclick={() => drop(false)}>
							<PackageMinus size={14} /> Drop one
						</button>
					{/if}
					<button class="btn-danger px-2.5 py-1.5 text-xs" disabled={busy} onclick={() => drop(true)}>
						<Trash2 size={14} /> Drop{selectedItem.count > 1 ? ' all' : ''}
					</button>
				{/if}
			{/if}
		</div>
	{/if}
</div>
