<script lang="ts">
	import { api, errorMessage } from '#lib/api.js';
	import { toasts } from '#lib/toast.svelte.js';
	import Modal from './Modal.svelte';

	let { onclose }: { onclose: () => void } = $props();

	let currentPassword = $state('');
	let password = $state('');
	let saving = $state(false);
	let error = $state('');

	async function save(e: SubmitEvent) {
		e.preventDefault();
		saving = true;
		error = '';
		try {
			await api.post('/me/password', { currentPassword, password });
			toasts.success('Password changed');
			onclose();
		} catch (e) {
			error = errorMessage(e);
		} finally {
			saving = false;
		}
	}
</script>

<Modal title="Change password" {onclose}>
	<form onsubmit={save} class="space-y-4">
		<label class="block">
			<span class="label">Current password</span>
			<input class="input" type="password" bind:value={currentPassword} autocomplete="current-password" required />
		</label>
		<label class="block">
			<span class="label">New password</span>
			<input class="input" type="password" bind:value={password} autocomplete="new-password" minlength={8} placeholder="At least 8 characters" required />
		</label>
		{#if error}<p class="text-sm text-red-400">{error}</p>{/if}
		<button class="btn-primary w-full" disabled={saving || !currentPassword || password.length < 8}>Change password</button>
	</form>
</Modal>
