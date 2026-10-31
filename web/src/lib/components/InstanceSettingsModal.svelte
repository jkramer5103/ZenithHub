<script lang="ts">
	import { api, errorMessage } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { toasts } from '#lib/toast.svelte.js';
	import type { Autostart, Instance } from '#lib/types.js';
	import { goto } from '$app/navigation';
	import Modal from './Modal.svelte';

	let { instance, onclose }: { instance: Instance; onclose: () => void } = $props();

	let name = $state(instance.name);
	let autostart = $state<Autostart>(instance.autostart);
	let saving = $state(false);
	let error = $state('');
	let confirmDelete = $state('');

	const autostartOptions: { value: Autostart; label: string; hint: string }[] = [
		{ value: 'RESTORE', label: 'Restore', hint: 'Reconnect if it was connected when the manager stopped' },
		{ value: 'ALWAYS', label: 'Always', hint: 'Always connect when the manager starts' },
		{ value: 'NEVER', label: 'Never', hint: 'Only connect by hand' }
	];

	async function save(e: SubmitEvent) {
		e.preventDefault();
		saving = true;
		error = '';
		try {
			await api.patch(`/instances/${instance.id}`, { name: name.trim(), autostart });
			await app.refresh();
			toasts.success('Saved');
			onclose();
		} catch (e) {
			error = errorMessage(e);
		} finally {
			saving = false;
		}
	}

	async function remove() {
		saving = true;
		try {
			await api.del(`/instances/${instance.id}`);
			await app.refresh();
			toasts.success('Instance deleted');
			onclose();
			goto('/instances');
		} catch (e) {
			error = errorMessage(e);
		} finally {
			saving = false;
		}
	}
</script>

<Modal title="Instance settings" {onclose}>
	<form onsubmit={save} class="space-y-4">
		<div>
			<label class="label" for="instance-name">Name</label>
			<input id="instance-name" class="input" bind:value={name} />
		</div>
		<fieldset>
			<legend class="label">When the manager starts</legend>
			<div class="space-y-2">
				{#each autostartOptions as option}
					<label class="flex cursor-pointer items-start gap-3 rounded-lg border border-zinc-800 p-3 has-[:checked]:border-emerald-700 has-[:checked]:bg-emerald-950/30">
						<input type="radio" class="mt-0.5 accent-emerald-600" bind:group={autostart} value={option.value} />
						<span>
							<span class="block text-sm font-medium">{option.label}</span>
							<span class="block text-xs text-zinc-400">{option.hint}</span>
						</span>
					</label>
				{/each}
			</div>
		</fieldset>
		{#if error}<p class="text-sm text-red-400">{error}</p>{/if}
		<button class="btn-primary w-full" disabled={saving || !name.trim()}>Save</button>
	</form>

	<div class="mt-6 border-t border-zinc-800 pt-5">
		<h3 class="text-sm font-semibold text-red-300">Delete instance</h3>
		<p class="mt-1 text-xs text-zinc-400">
			Stops it and deletes its settings and data. The account stays. Type <span class="font-mono text-zinc-200">{instance.name}</span> to confirm.
		</p>
		<div class="mt-3 flex gap-2">
			<input class="input" bind:value={confirmDelete} aria-label="Instance name" />
			<button class="btn-danger shrink-0" disabled={confirmDelete !== instance.name || saving} onclick={remove}>Delete</button>
		</div>
	</div>
</Modal>
