<script lang="ts">
	import { api, errorMessage } from '#lib/api.js';
	import { humanizeEnum } from '#lib/settings-catalog.js';
	import type { PlayerEntry, SettingType } from '#lib/types.js';
	import { LoaderCircle, Plus, X } from '@lucide/svelte';

	let {
		items,
		itemType,
		disabled = false,
		placeholder,
		onchange
	}: {
		items: unknown[];
		itemType: SettingType;
		disabled?: boolean;
		placeholder?: string;
		onchange: (items: unknown[]) => void;
	} = $props();

	let draft = $state('');
	let busy = $state(false);
	let error = $state('');

	const isPlayer = $derived(itemType.type === 'player');
	const enumValues = $derived(itemType.type === 'enum' ? itemType.values.filter((v) => !items.includes(v)) : []);

	function label(item: unknown) {
		if (isPlayer) return (item as PlayerEntry).username;
		if (itemType.type === 'enum') return humanizeEnum(String(item));
		return String(item);
	}

	function key(item: unknown) {
		return isPlayer ? (item as PlayerEntry).uuid : String(item);
	}

	function remove(index: number) {
		onchange(items.filter((_, i) => i !== index));
	}

	async function add(e?: Event) {
		e?.preventDefault();
		const text = draft.trim();
		if (!text || busy) return;
		error = '';
		let item: unknown = text;
		if (itemType.type === 'integer') {
			item = Number(text);
			if (!Number.isInteger(item)) {
				error = 'Enter a whole number';
				return;
			}
		} else if (isPlayer) {
			busy = true;
			try {
				item = await api.get<PlayerEntry>(`/players/${encodeURIComponent(text)}`);
			} catch (err) {
				error = errorMessage(err);
				return;
			} finally {
				busy = false;
			}
		}
		if (items.some((i) => key(i).toLowerCase() === key(item).toLowerCase())) {
			error = `${label(item)} is already in the list`;
			return;
		}
		draft = '';
		onchange([...items, item]);
	}

	function addEnum(e: Event) {
		const select = e.target as HTMLSelectElement;
		if (select.value) onchange([...items, select.value]);
		select.value = '';
	}
</script>

<div>
	{#if items.length}
		<ul class="mb-2 flex flex-wrap gap-1.5">
			{#each items as item, i (`${i}:${key(item)}`)}
				<li class="flex max-w-full items-center gap-1.5 rounded-md border border-zinc-700 bg-zinc-800/70 py-0.5 pr-0.5 pl-2 text-sm">
					{#if isPlayer}
						<img
							src="https://mc-heads.net/avatar/{(item as PlayerEntry).uuid}/20"
							alt=""
							class="-ml-1 size-5 rounded-sm [image-rendering:pixelated]"
						/>
					{/if}
					<span class="min-w-0 break-words">{label(item)}</span>
					<button
						type="button"
						class="rounded p-0.5 text-zinc-400 hover:bg-zinc-700 hover:text-zinc-100 disabled:opacity-50"
						aria-label="Remove {label(item)}"
						{disabled}
						onclick={() => remove(i)}
					>
						<X size={14} />
					</button>
				</li>
			{/each}
		</ul>
	{/if}
	{#if itemType.type === 'enum'}
		<select class="input" {disabled} onchange={addEnum} aria-label="Add">
			<option value="">Add…</option>
			{#each enumValues as value}
				<option {value}>{humanizeEnum(value)}</option>
			{/each}
		</select>
	{:else}
		<form class="flex gap-2" onsubmit={add}>
			<input
				class="input"
				bind:value={draft}
				{disabled}
				type={itemType.type === 'integer' ? 'number' : 'text'}
				placeholder={placeholder ?? (isPlayer ? 'Minecraft name' : 'Add an entry')}
				maxlength={isPlayer ? 16 : 256}
				autocomplete="off"
				autocapitalize="off"
				spellcheck="false"
				oninput={() => (error = '')}
			/>
			<button class="btn-secondary shrink-0 px-3" disabled={disabled || busy || !draft.trim()} aria-label="Add">
				{#if busy}<LoaderCircle size={16} class="animate-spin" />{:else}<Plus size={16} />{/if}
			</button>
		</form>
	{/if}
	{#if error}<p class="mt-1.5 text-xs text-red-400">{error}</p>{/if}
</div>
