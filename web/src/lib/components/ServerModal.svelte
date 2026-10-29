<script lang="ts">
	import { api, errorMessage } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { toasts } from '#lib/toast.svelte.js';
	import type { Server } from '#lib/types.js';
	import Modal from './Modal.svelte';
	import ServerFields from './ServerFields.svelte';

	let { onclose, server = null }: { onclose: () => void; server?: Server | null } = $props();

	let fields = $state({ name: server?.name ?? '', address: server?.address ?? '', port: server?.port ?? 25565 });
	let saving = $state(false);
	let error = $state('');

	async function save(e: SubmitEvent) {
		e.preventDefault();
		saving = true;
		error = '';
		try {
			if (server) await api.patch(`/servers/${server.id}`, fields);
			else await api.post('/servers', fields);
			toasts.success(server ? 'Server saved' : 'Server added');
			await app.refresh();
			onclose();
		} catch (e) {
			error = errorMessage(e);
		} finally {
			saving = false;
		}
	}
</script>

<Modal title={server ? 'Edit server' : 'Add server'} {onclose}>
	<form onsubmit={save} class="space-y-4">
		<ServerFields bind:fields />
		{#if error}<p class="text-sm text-red-400">{error}</p>{/if}
		<button class="btn-primary w-full" disabled={saving || !fields.name.trim() || !fields.address.trim()}>
			{server ? 'Save' : 'Add server'}
		</button>
	</form>
</Modal>
