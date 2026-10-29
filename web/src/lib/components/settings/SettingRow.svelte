<script lang="ts">
	import { humanizeEnum, type Field } from '#lib/settings-catalog.js';
	import type { Query } from '#lib/settings-search.js';
	import type { SettingScope, SettingSource, SettingType } from '#lib/types.js';
	import { Lock, RotateCcw } from '@lucide/svelte';
	import Highlight from './Highlight.svelte';
	import ListInput from './ListInput.svelte';
	import ObjectListInput from './ObjectListInput.svelte';
	import Switch from './Switch.svelte';

	let {
		field,
		type,
		value,
		source,
		scope,
		mono = false,
		query = null,
		onset,
		onreset
	}: {
		field: Field;
		type: SettingType;
		value: unknown;
		source: SettingSource;
		scope: SettingScope;
		/** raw path labels in the developer view */
		mono?: boolean;
		/** search words to highlight */
		query?: Query | null;
		onset: (value: unknown) => void;
		onreset: () => void;
	} = $props();

	const forced = $derived(source === 'forced');
	const changed = $derived(source === scope);
	const block = $derived(type.type === 'list' || field.multiline);
	const inheritedFrom = $derived(
		source === 'server' && scope === 'instance'
			? 'Set for this server'
			: source === 'global' && scope !== 'global'
				? 'Set for all instances'
				: ''
	);

	// text and number inputs commit on blur or enter, not on every keystroke
	let draft = $state('');
	$effect(() => {
		draft = value === null || value === undefined ? '' : String(value);
	});

	function commitText() {
		// number inputs bind numbers
		const text = String(draft ?? '');
		if (text === String(value ?? '')) return;
		if (type.type === 'integer' || type.type === 'number') {
			let n = Number(text);
			if (text.trim() === '' || !Number.isFinite(n)) {
				draft = String(value ?? '');
				return;
			}
			if (type.type === 'integer') n = Math.round(n);
			if (field.min !== undefined) n = Math.max(field.min, n);
			if (field.max !== undefined) n = Math.min(field.max, n);
			draft = String(n);
			if (n !== value) onset(n);
		} else {
			onset(text);
		}
	}

	function onKey(e: KeyboardEvent) {
		if (e.key === 'Enter' && !(e.target instanceof HTMLTextAreaElement)) (e.target as HTMLElement).blur();
	}
</script>

{#snippet control()}
	{#if type.type === 'boolean'}
		<Switch checked={value === true} disabled={forced} label={field.label} onchange={onset} />
	{:else if type.type === 'enum'}
		<select class="input w-full sm:w-56" disabled={forced} value={value as string} onchange={(e) => onset((e.target as HTMLSelectElement).value)}>
			{#each type.values as option}
				<option value={option}>{field.options?.[option] ?? humanizeEnum(option)}</option>
			{/each}
		</select>
	{:else if type.type === 'integer' || type.type === 'number'}
		<div class="flex items-center gap-2">
			<input
				class="input w-28 text-right tabular-nums"
				type="number"
				inputmode={type.type === 'integer' ? 'numeric' : 'decimal'}
				step={type.type === 'integer' ? 1 : 'any'}
				min={field.min}
				max={field.max}
				disabled={forced}
				bind:value={draft}
				onblur={commitText}
				onkeydown={onKey}
				aria-label={field.label}
			/>
			{#if field.unit}<span class="text-xs whitespace-nowrap text-zinc-500">{field.unit}</span>{/if}
		</div>
	{:else if type.type === 'list' && type.items.type === 'object'}
		<ObjectListInput
			items={(value as Record<string, unknown>[]) ?? []}
			fields={type.items.fields}
			columns={field.columns}
			addLabel={field.addLabel}
			disabled={forced}
			onchange={onset}
		/>
	{:else if type.type === 'list'}
		<ListInput
			items={(value as unknown[]) ?? []}
			itemType={type.items}
			disabled={forced}
			placeholder={field.placeholder}
			onchange={onset}
		/>
	{:else if field.multiline}
		<textarea
			class="input min-h-20"
			disabled={forced}
			bind:value={draft}
			onblur={commitText}
			maxlength={1000}
			aria-label={field.label}
		></textarea>
	{:else}
		<input
			class="input w-full sm:w-64 {mono ? 'font-mono' : ''}"
			type={field.secret ? 'password' : 'text'}
			disabled={forced}
			bind:value={draft}
			onblur={commitText}
			onkeydown={onKey}
			placeholder={field.placeholder}
			autocomplete="off"
			spellcheck="false"
			aria-label={field.label}
		/>
	{/if}
{/snippet}

<div class="py-3 {block ? '' : 'flex flex-wrap items-center justify-between gap-x-6 gap-y-2'}">
	<div class="min-w-0 flex-1 {block ? 'mb-2' : ''}">
		<div class="flex flex-wrap items-center gap-x-2 gap-y-1">
			<span class="text-sm {mono ? 'font-mono text-[13px] break-all' : ''}"><Highlight text={field.label} {query} /></span>
			{#if changed}
				<span class="rounded bg-emerald-950 px-1.5 py-px text-[11px] font-medium text-emerald-300">Changed</span>
				<button
					type="button"
					class="rounded p-0.5 text-zinc-500 hover:bg-zinc-800 hover:text-zinc-200"
					title="Reset to the inherited value"
					aria-label="Reset {field.label}"
					onclick={onreset}
				>
					<RotateCcw size={13} />
				</button>
			{/if}
		</div>
		{#if forced}
			<p class="mt-0.5 flex items-center gap-1 text-xs text-zinc-500"><Lock size={11} /> Managed by ZenithProxy</p>
		{:else if field.help || inheritedFrom}
			<p class="mt-0.5 text-xs text-zinc-500">
				<Highlight text={field.help ?? ''} {query} />{field.help && inheritedFrom ? ' · ' : ''}{inheritedFrom}
			</p>
		{/if}
	</div>
	<div class={block ? '' : type.type === 'enum' || type.type === 'string' ? 'w-full sm:w-auto' : 'shrink-0'}>{@render control()}</div>
</div>
