<script lang="ts">
	import { api, errorMessage } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { toasts } from '#lib/toast.svelte.js';
	import type { PendingLogin } from '#lib/types.js';
	import { ExternalLink, FileUp, LoaderCircle } from '@lucide/svelte';
	import CopyField from './CopyField.svelte';
	import { onDestroy } from 'svelte';
	import Modal from './Modal.svelte';

	let {
		onclose,
		relogin = null
	}: {
		onclose: () => void;
		/** log in again to an existing account instead of adding a new one */
		relogin?: { id: string; username: string } | null;
	} = $props();

	type Tab = 'microsoft' | 'import' | 'offline';
	let tab = $state<Tab>('microsoft');

	// --- microsoft device code ---
	let login = $state<PendingLogin | null>(null);
	let starting = $state(false);
	let now = $state(Date.now());
	const ticker = setInterval(() => (now = Date.now()), 1000);
	let poller: ReturnType<typeof setInterval> | undefined;

	const stopListening = app.onLogin((l) => {
		if (l.id === login?.id) update(l);
	});

	function update(l: PendingLogin) {
		login = l;
		if (l.state === 'DONE') {
			clearInterval(poller);
			toasts.success(relogin ? `Logged in to ${relogin.username} again` : 'Account added');
			app.refresh();
			onclose();
		} else if (l.state === 'FAILED' || l.state === 'CANCELLED') {
			clearInterval(poller);
		}
	}

	async function startLogin() {
		starting = true;
		try {
			login = await api.post<PendingLogin>('/accounts/login', relogin ? { accountId: relogin.id } : {});
			// the WebSocket delivers updates; polling covers a dropped connection
			clearInterval(poller);
			poller = setInterval(async () => {
				if (!login) return;
				try {
					update(await api.get<PendingLogin>(`/accounts/login/${login.id}`));
				} catch {
					// keep trying
				}
			}, 3000);
		} catch (e) {
			toasts.error(e);
		} finally {
			starting = false;
		}
	}

	function close() {
		if (login && (login.state === 'WAITING_FOR_CODE' || login.state === 'WAITING_FOR_USER')) {
			api.del(`/accounts/login/${login.id}`).catch(() => {});
		}
		onclose();
	}

	onDestroy(() => {
		clearInterval(ticker);
		clearInterval(poller);
		stopListening();
	});

	const secondsLeft = $derived(login?.expiresAt ? Math.max(0, Math.round((login.expiresAt - now) / 1000)) : 0);

	// --- import ---
	let authCache = $state('');
	let importing = $state(false);
	let importError = $state('');

	async function readFile(e: Event) {
		const file = (e.target as HTMLInputElement).files?.[0];
		if (file) authCache = await file.text();
	}

	async function importCache() {
		importing = true;
		importError = '';
		try {
			await api.post('/accounts/import', { authCache });
			toasts.success('Account imported');
			await app.refresh();
			onclose();
		} catch (e) {
			importError = errorMessage(e);
		} finally {
			importing = false;
		}
	}

	// --- offline ---
	let offlineName = $state('');
	let addingOffline = $state(false);
	let offlineError = $state('');

	async function addOffline(e: SubmitEvent) {
		e.preventDefault();
		addingOffline = true;
		offlineError = '';
		try {
			await api.post('/accounts/offline', { username: offlineName.trim() });
			toasts.success('Offline account added');
			await app.refresh();
			onclose();
		} catch (e) {
			offlineError = errorMessage(e);
		} finally {
			addingOffline = false;
		}
	}

	const tabs: { id: Tab; label: string }[] = [
		{ id: 'microsoft', label: 'Microsoft' },
		{ id: 'import', label: 'Import' },
		{ id: 'offline', label: 'Offline' }
	];
</script>

<Modal title={relogin ? `Log in again: ${relogin.username}` : 'Add account'} onclose={close}>
	{#if !relogin}
		<div class="mb-5 grid grid-cols-3 gap-1 rounded-lg bg-zinc-950 p-1">
			{#each tabs as t}
				<button
					class="rounded-md py-1.5 text-sm font-medium transition-colors {tab === t.id
						? 'bg-zinc-800 text-zinc-100'
						: 'text-zinc-400 hover:text-zinc-200'}"
					onclick={() => (tab = t.id)}
					disabled={!!login && tab !== t.id && login.state === 'WAITING_FOR_USER'}>{t.label}</button
				>
			{/each}
		</div>
	{/if}

	{#if tab === 'microsoft'}
		{#if !login || login.state === 'CANCELLED'}
			<p class="mb-4 text-sm text-zinc-400">
				Sign in with the Microsoft account that owns Minecraft. You'll get a code to enter on Microsoft's website; this works from
				any device, your phone included.
			</p>
			<button class="btn-primary w-full" onclick={startLogin} disabled={starting}>
				{#if starting}<LoaderCircle size={16} class="animate-spin" />{/if}
				Get login code
			</button>
		{:else if login.state === 'WAITING_FOR_CODE'}
			<div class="flex items-center justify-center gap-2 py-8 text-sm text-zinc-400">
				<LoaderCircle size={18} class="animate-spin" /> Asking Microsoft for a code…
			</div>
		{:else if login.state === 'WAITING_FOR_USER'}
			<ol class="mb-4 list-decimal space-y-1 pl-5 text-sm text-zinc-300">
				<li>Open the Microsoft login page.</li>
				<li>Enter this code and sign in.</li>
			</ol>
			<div class="mb-3"><CopyField value={login.userCode ?? ''} size="lg" label="Copy code" /></div>
			<a
				class="btn-primary w-full"
				href={login.directVerificationUri ?? login.verificationUri}
				target="_blank"
				rel="noreferrer"
			>
				Open {login.verificationUri?.replace(/^https?:\/\//, '')}
				<ExternalLink size={16} />
			</a>
			<div class="mt-4 flex items-center justify-center gap-2 text-xs text-zinc-500">
				<LoaderCircle size={14} class="animate-spin" />
				Waiting for you to sign in · code expires in {Math.floor(secondsLeft / 60)}:{String(secondsLeft % 60).padStart(2, '0')}
			</div>
		{:else if login.state === 'FAILED'}
			<div class="mb-4 rounded-lg border border-red-900 bg-red-950/50 p-3 text-sm text-red-200">{login.error}</div>
			<button class="btn-primary w-full" onclick={startLogin} disabled={starting}>Try again</button>
		{/if}
	{:else if tab === 'import'}
		<p class="mb-4 text-sm text-zinc-400">
			Moving from a regular ZenithProxy install? Upload its <code class="text-zinc-300">mc_auth_cache.json</code> to keep the login
			without signing in again.
		</p>
		<label class="btn-secondary mb-3 w-full cursor-pointer">
			<FileUp size={16} /> Choose file
			<input type="file" accept=".json,application/json" class="hidden" onchange={readFile} />
		</label>
		<textarea
			class="input h-28 font-mono text-xs"
			placeholder="…or paste the file contents here"
			bind:value={authCache}
			spellcheck="false"
		></textarea>
		{#if importError}<p class="mt-2 text-sm text-red-400">{importError}</p>{/if}
		<button class="btn-primary mt-4 w-full" onclick={importCache} disabled={!authCache.trim() || importing}>
			{#if importing}<LoaderCircle size={16} class="animate-spin" />{/if}
			Import account
		</button>
	{:else}
		<form onsubmit={addOffline}>
			<p class="mb-4 text-sm text-zinc-400">For servers in offline (cracked) mode. No login needed, just a name.</p>
			<label class="label" for="offline-name">Username</label>
			<input
				id="offline-name"
				class="input"
				bind:value={offlineName}
				placeholder="Steve"
				maxlength="16"
				autocomplete="off"
				spellcheck="false"
			/>
			{#if offlineError}<p class="mt-2 text-sm text-red-400">{offlineError}</p>{/if}
			<button class="btn-primary mt-4 w-full" disabled={offlineName.trim().length < 3 || addingOffline}>Add offline account</button>
		</form>
	{/if}
</Modal>
