<script lang="ts">
	import { api, errorMessage } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { toasts } from '#lib/toast.svelte.js';
	import type { ManagedUser } from '#lib/types.js';
	import ListInput from './settings/ListInput.svelte';
	import Modal from './Modal.svelte';

	let { onclose, onsaved, user = null }: { onclose: () => void; onsaved: () => void; user?: ManagedUser | null } = $props();

	let username = $state(user?.username ?? '');
	let password = $state('');
	let mcNames = $state<string[]>(user ? [...user.mcNames] : []);
	let unlinkDiscord = $state(false);
	let saving = $state(false);
	let error = $state('');

	async function save(e: SubmitEvent) {
		e.preventDefault();
		saving = true;
		error = '';
		try {
			if (user) {
				const body: Record<string, unknown> = { mcNames };
				if (password) body.password = password;
				if (unlinkDiscord) body.discordId = null;
				await api.patch(`/users/${user.id}`, body);
			} else {
				await api.post('/users', { username: username.trim(), password, mcNames });
			}
			toasts.success(user ? 'User saved' : `${username.trim()} can now sign in`);
			await app.refresh();
			onsaved();
			onclose();
		} catch (e) {
			error = errorMessage(e);
		} finally {
			saving = false;
		}
	}
</script>

<Modal title={user ? `Edit ${user.username}` : 'Add user'} {onclose}>
	<form onsubmit={save} class="space-y-4">
		{#if !user}
			<label class="block">
				<span class="label">Username</span>
				<input class="input" bind:value={username} autocomplete="off" autocapitalize="off" spellcheck="false" required />
			</label>
		{/if}
		<label class="block">
			<span class="label">{user ? 'New password' : 'Password'}</span>
			<input
				class="input"
				type="password"
				bind:value={password}
				autocomplete="new-password"
				minlength={8}
				required={!user}
				placeholder={user ? 'Leave empty to keep the current one' : 'At least 8 characters'}
			/>
			{#if user && password}<span class="mt-1 block text-xs text-zinc-500">{user.username} will be signed out everywhere.</span>{/if}
		</label>
		<div>
			<span class="label">Minecraft names</span>
			<p class="mb-2 text-xs text-zinc-500">
				Lets them use the in-game lobby. Only add names they own: the lobby checks with Mojang that they’re really logged in as that name.
			</p>
			<ListInput items={mcNames} itemType={{ type: 'string' }} placeholder="Minecraft name" onchange={(v) => (mcNames = v as string[])} />
		</div>
		{#if user?.discordId}
			<div class="flex items-center justify-between gap-3 rounded-lg border border-zinc-800 px-3 py-2.5 text-sm">
				<div>
					<div>Discord linked</div>
					<div class="font-mono text-xs text-zinc-500">{user.discordId}</div>
				</div>
				<label class="flex items-center gap-2 text-xs text-zinc-400">
					<input type="checkbox" class="accent-red-500" bind:checked={unlinkDiscord} /> Unlink
				</label>
			</div>
		{/if}
		{#if error}<p class="text-sm text-red-400">{error}</p>{/if}
		<button class="btn-primary w-full" disabled={saving || (!user && (!username.trim() || password.length < 8))}>
			{user ? 'Save' : 'Add user'}
		</button>
	</form>
</Modal>
