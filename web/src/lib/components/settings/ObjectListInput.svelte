<script lang="ts">
	import type { Column } from '#lib/settings-catalog.js';
	import { humanize, humanizeEnum } from '#lib/settings-catalog.js';
	import type { SettingType } from '#lib/types.js';
	import { Plus, Trash2, X } from '@lucide/svelte';

	type Row = Record<string, unknown>;

	let {
		items,
		fields,
		columns = {},
		disabled = false,
		addLabel = 'Add',
		onchange
	}: {
		items: Row[];
		fields: Record<string, SettingType>;
		columns?: Record<string, Column>;
		disabled?: boolean;
		addLabel?: string;
		onchange: (items: Row[]) => void;
	} = $props();

	const names = $derived(Object.keys(fields));
	// {hour, minute} records are times of day
	const isTime = $derived(names.length === 2 && names.includes('hour') && names.includes('minute'));

	function label(name: string) {
		return columns[name]?.label ?? humanize(name);
	}

	function blank(): Row {
		const row: Row = {};
		for (const name of names) {
			const t = fields[name];
			const def = columns[name]?.default;
			row[name] =
				def !== undefined ? def : t.type === 'boolean' ? false : t.type === 'integer' || t.type === 'number' ? 0 : t.type === 'enum' ? t.values[0] : '';
		}
		return row;
	}

	let draft = $state<Row>({});
	let adding = $state(false);
	let error = $state('');

	function startAdd() {
		draft = blank();
		error = '';
		adding = true;
	}

	/** turns raw input text into the field's type; null when it doesn't parse */
	function coerce(name: string, raw: unknown): unknown {
		const t = fields[name];
		const col = columns[name];
		if (t.type === 'integer' || t.type === 'number') {
			let n = Number(raw);
			if (String(raw).trim() === '' || !Number.isFinite(n)) return null;
			if (t.type === 'integer') n = Math.round(n);
			if (col?.min !== undefined) n = Math.max(col.min, n);
			if (col?.max !== undefined) n = Math.min(col.max, n);
			return n;
		}
		if (t.type === 'boolean') return raw === true;
		return String(raw ?? '').trim();
	}

	function validRow(row: Row) {
		for (const name of names) {
			const v = row[name];
			if (v === null) return `${label(name)} must be a number`;
			if (fields[name].type === 'string' && v === '' && !columns[name]?.optional) return `${label(name)} is required`;
		}
		return '';
	}

	function commitAdd(e: Event) {
		e.preventDefault();
		const row: Row = {};
		for (const name of names) row[name] = coerce(name, draft[name]);
		error = validRow(row);
		if (error) return;
		const keyField = names.find((n) => columns[n]?.unique);
		if (keyField && items.some((i) => String(i[keyField]).toLowerCase() === String(row[keyField]).toLowerCase())) {
			error = `There's already one called ${row[keyField]}`;
			return;
		}
		onchange([...items, row]);
		adding = false;
	}

	function edit(index: number, name: string, raw: unknown) {
		const value = coerce(name, raw);
		if (value === null || value === items[index][name]) return;
		if (fields[name].type === 'string' && value === '' && !columns[name]?.optional) return;
		onchange(items.map((row, i) => (i === index ? { ...row, [name]: value } : row)));
	}

	function remove(index: number) {
		onchange(items.filter((_, i) => i !== index));
	}

	// --- times of day ---

	const pad = (n: unknown) => String(n).padStart(2, '0');
	const sortedTimes = $derived(
		isTime ? [...items].sort((a, b) => (a.hour as number) * 60 + (a.minute as number) - ((b.hour as number) * 60 + (b.minute as number))) : []
	);
	let timeDraft = $state('');

	function addTime(e: Event) {
		e.preventDefault();
		const [h, m] = timeDraft.split(':').map(Number);
		if (!Number.isInteger(h) || !Number.isInteger(m)) return;
		if (items.some((t) => t.hour === h && t.minute === m)) {
			error = `${pad(h)}:${pad(m)} is already in the list`;
			return;
		}
		error = '';
		timeDraft = '';
		onchange([...items, { hour: h, minute: m }]);
	}

	function removeTime(row: Row) {
		onchange(items.filter((t) => t !== row));
	}
</script>

{#snippet input(name: string, value: unknown, onvalue: (v: unknown) => void, live: boolean)}
	{@const t = fields[name]}
	{@const col = columns[name]}
	{#if t.type === 'boolean'}
		<input type="checkbox" class="size-4 accent-emerald-500" checked={value === true} {disabled} onchange={(e) => onvalue((e.target as HTMLInputElement).checked)} aria-label={label(name)} />
	{:else if t.type === 'enum' || col?.options}
		{@const options = col?.options ?? {}}
		{@const values = t.type === 'enum' ? t.values : Object.keys(options)}
		<select class="input py-1.5" {disabled} value={String(value)} onchange={(e) => onvalue((e.target as HTMLSelectElement).value)} aria-label={label(name)}>
			{#each values as option}
				<option value={option}>{options[option] ?? humanizeEnum(option)}</option>
			{/each}
		</select>
	{:else}
		<input
			class="input py-1.5 {t.type === 'integer' || t.type === 'number' ? 'tabular-nums' : ''}"
			type={t.type === 'integer' || t.type === 'number' ? 'number' : 'text'}
			inputmode={t.type === 'integer' ? 'numeric' : undefined}
			min={col?.min}
			max={col?.max}
			placeholder={col?.placeholder}
			{disabled}
			value={value ?? ''}
			autocomplete="off"
			spellcheck="false"
			aria-label={label(name)}
			onchange={live ? (e) => onvalue((e.target as HTMLInputElement).value) : undefined}
			oninput={live ? undefined : (e) => onvalue((e.target as HTMLInputElement).value)}
			onkeydown={live ? (e) => e.key === 'Enter' && (e.target as HTMLElement).blur() : undefined}
		/>
	{/if}
{/snippet}

{#if isTime}
	<div>
		{#if sortedTimes.length}
			<ul class="mb-2 flex flex-wrap gap-1.5">
				{#each sortedTimes as row (`${row.hour}:${row.minute}`)}
					<li class="flex items-center gap-1.5 rounded-md border border-zinc-700 bg-zinc-800/70 py-0.5 pr-0.5 pl-2 text-sm tabular-nums">
						{pad(row.hour)}:{pad(row.minute)}
						<button
							type="button"
							class="rounded p-0.5 text-zinc-400 hover:bg-zinc-700 hover:text-zinc-100 disabled:opacity-50"
							aria-label="Remove {pad(row.hour)}:{pad(row.minute)}"
							{disabled}
							onclick={() => removeTime(row)}><X size={14} /></button
						>
					</li>
				{/each}
			</ul>
		{/if}
		<form class="flex gap-2" onsubmit={addTime}>
			<input class="input w-36" type="time" bind:value={timeDraft} {disabled} aria-label="Time" oninput={() => (error = '')} />
			<button class="btn-secondary shrink-0 px-3" disabled={disabled || !timeDraft} aria-label="Add time"><Plus size={16} /></button>
		</form>
		{#if error}<p class="mt-1.5 text-xs text-red-400">{error}</p>{/if}
	</div>
{:else}
	<div class="space-y-2">
		{#if items.length}
			<div class="overflow-hidden rounded-lg border border-zinc-800">
				<!-- header only on wide screens; narrow screens label each input -->
				<div
					class="hidden gap-2 border-b border-zinc-800 bg-zinc-900/60 px-3 py-1.5 text-xs font-medium text-zinc-500 sm:grid"
					style="grid-template-columns: {names.map((n) => columns[n]?.width ?? '1fr').join(' ')} 2rem"
				>
					{#each names as name}<div>{label(name)}</div>{/each}
					<div></div>
				</div>
				{#each items as row, i (i)}
					<div
						class="grid grid-cols-2 items-end gap-2 border-b border-zinc-800/80 px-3 py-2 last:border-b-0 sm:items-center sm:[grid-template-columns:var(--cols)]"
						style="--cols: {names.map((n) => columns[n]?.width ?? '1fr').join(' ')} 2rem"
					>
						{#each names as name}
							<label class="min-w-0 {fields[name].type === 'string' ? 'col-span-2 sm:col-span-1' : ''}">
								<span class="mb-1 block text-xs text-zinc-500 sm:hidden">{label(name)}</span>
								{@render input(name, row[name], (v) => edit(i, name, v), true)}
							</label>
						{/each}
						<button
							type="button"
							class="col-start-2 justify-self-end rounded p-1.5 text-zinc-500 hover:bg-zinc-800 hover:text-red-300 disabled:opacity-50 sm:col-start-auto"
							aria-label="Remove row {i + 1}"
							{disabled}
							onclick={() => remove(i)}><Trash2 size={15} /></button
						>
					</div>
				{/each}
			</div>
		{/if}

		{#if adding}
			<form class="rounded-lg border border-zinc-700 bg-zinc-900/60 p-3" onsubmit={commitAdd}>
				<div class="grid grid-cols-2 gap-2 sm:[grid-template-columns:var(--cols)]" style="--cols: {names.map((n) => columns[n]?.width ?? '1fr').join(' ')}">
					{#each names as name}
						<label class="min-w-0 {fields[name].type === 'string' ? 'col-span-2 sm:col-span-1' : ''}">
							<span class="mb-1 block text-xs text-zinc-500">{label(name)}</span>
							{@render input(name, draft[name], (v) => (draft[name] = v), false)}
						</label>
					{/each}
				</div>
				{#if error}<p class="mt-2 text-xs text-red-400">{error}</p>{/if}
				<div class="mt-3 flex justify-end gap-2">
					<button type="button" class="btn-ghost px-3 py-1.5 text-sm" onclick={() => (adding = false)}>Cancel</button>
					<button class="btn-primary px-3 py-1.5 text-sm" {disabled}>{addLabel}</button>
				</div>
			</form>
		{:else}
			<button type="button" class="btn-secondary px-3 py-1.5 text-sm" {disabled} onclick={startAdd}><Plus size={15} /> {addLabel}</button>
		{/if}
	</div>
{/if}
