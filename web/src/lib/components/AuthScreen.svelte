<script lang="ts">
	import { errorMessage } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { LoaderCircle } from '@lucide/svelte';
	import Logo from './Logo.svelte';

	const setup = $derived(app.session?.setupRequired ?? false);
	let username = $state('');
	let password = $state('');
	let confirm = $state('');
	let busy = $state(false);
	let error = $state('');

	async function submit(e: SubmitEvent) {
		e.preventDefault();
		error = '';
		if (setup && password !== confirm) {
			error = "Passwords don't match";
			return;
		}
		busy = true;
		try {
			await app.login(username.trim(), password, setup);
		} catch (e) {
			error = errorMessage(e);
		} finally {
			busy = false;
		}
	}
</script>

<div class="flex min-h-dvh items-center justify-center p-4">
	<div class="w-full max-w-sm">
		<div class="mb-8 flex flex-col items-center gap-3 text-center">
			<Logo size={48} />
			<h1 class="text-xl font-semibold">{setup ? 'Welcome to ZenithProxy' : 'Sign in'}</h1>
			{#if setup}
				<p class="text-sm text-zinc-400">Create the admin login. The admin can add accounts and sees everything.</p>
			{/if}
		</div>
		<form onsubmit={submit} class="card space-y-4 p-5">
			<div>
				<label class="label" for="username">Username</label>
				<input id="username" class="input" bind:value={username} autocomplete="username" autocapitalize="off" spellcheck="false" />
			</div>
			<div>
				<label class="label" for="password">Password</label>
				<input
					id="password"
					class="input"
					type="password"
					bind:value={password}
					autocomplete={setup ? 'new-password' : 'current-password'}
				/>
				{#if setup}<p class="mt-1 text-xs text-zinc-500">At least 8 characters.</p>{/if}
			</div>
			{#if setup}
				<div>
					<label class="label" for="confirm">Confirm password</label>
					<input id="confirm" class="input" type="password" bind:value={confirm} autocomplete="new-password" />
				</div>
			{/if}
			{#if error}<p class="text-sm text-red-400">{error}</p>{/if}
			<button class="btn-primary w-full" disabled={busy || !username.trim() || !password}>
				{#if busy}<LoaderCircle size={16} class="animate-spin" />{/if}
				{setup ? 'Create admin' : 'Sign in'}
			</button>
		</form>
	</div>
</div>
