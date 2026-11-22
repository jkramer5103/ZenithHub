<script lang="ts">
	import { api } from '#lib/api.js';
	import Modal from '#lib/components/Modal.svelte';
	import Switch from '#lib/components/settings/Switch.svelte';
	import { attempt } from '#lib/toast.svelte.js';
	import type { PluginSettingField, PluginView } from '#lib/types.js';
	import { onMount } from 'svelte';

	let { plugin, onclose }: { plugin: PluginView; onclose: () => void } = $props();
	let fields = $state<PluginSettingField[] | null>(null);
	let values = $state<Record<string, unknown>>({});
	let busy = $state(false);

	onMount(() => {
		void attempt(async () => {
			const r = await api.get<{ fields: PluginSettingField[] }>(`/plugins/${plugin.id}/settings`);
			fields = r.fields;
			values = Object.fromEntries(r.fields.map((f) => [f.key, f.type === 'secret' ? '' : f.value]));
		});
	});

	async function save() {
		if (!fields) return;
		busy = true;
		const out: Record<string, unknown> = {};
		for (const f of fields) {
			const v = values[f.key];
			// a blank secret keeps the saved one
			if (f.type === 'secret' && v === '') continue;
			if (f.type === 'number') out[f.key] = v === '' || v === null || v === undefined ? null : Number(v);
			else out[f.key] = v;
		}
		const done = await attempt(() => api.put(`/plugins/${plugin.id}/settings`, { values: out }), 'Settings saved');
		busy = false;
		if (done) onclose();
	}
</script>

<Modal title="{plugin.name ?? plugin.id} settings" {onclose} wide>
	{#if fields === null}
		<div class="py-8 text-center text-sm text-zinc-500">Loading…</div>
	{:else}
		<form
			class="flex flex-col gap-4"
			onsubmit={(e) => {
				e.preventDefault();
				void save();
			}}
		>
			{#each fields as f (f.key)}
				{#if f.type === 'boolean'}
					<div class="flex items-start gap-3">
						<div class="min-w-0 flex-1">
							<div class="text-sm font-medium">{f.label}</div>
							{#if f.description}<div class="text-xs text-zinc-500">{f.description}</div>{/if}
						</div>
						<Switch checked={!!values[f.key]} label={f.label} onchange={(on) => (values[f.key] = on)} />
					</div>
				{:else}
					<label class="flex flex-col gap-1">
						<span class="text-sm font-medium">{f.label}</span>
						{#if f.type === 'select'}
							<select class="input" bind:value={values[f.key]}>
								{#each f.options as o (o.value)}<option value={o.value}>{o.label}</option>{/each}
							</select>
						{:else if f.type === 'text'}
							<textarea class="input min-h-24" bind:value={values[f.key]}></textarea>
						{:else if f.type === 'number'}
							<input class="input" type="number" step="any" min={f.min} max={f.max} bind:value={values[f.key]} />
						{:else if f.type === 'secret'}
							<input
								class="input"
								type="password"
								autocomplete="off"
								placeholder={f.set ? 'Saved. Leave blank to keep it' : 'Not set'}
								bind:value={values[f.key]}
							/>
						{:else}
							<input class="input" type="text" bind:value={values[f.key]} />
						{/if}
						{#if f.description}<span class="text-xs text-zinc-500">{f.description}</span>{/if}
					</label>
				{/if}
			{/each}
			<div class="flex justify-end gap-2">
				<button type="button" class="btn-secondary" onclick={onclose}>Cancel</button>
				<button class="btn-primary" disabled={busy}>Save</button>
			</div>
		</form>
	{/if}
</Modal>
