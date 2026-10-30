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

	type Tab = 'microsoft' | 'import' | 'offline' | 'more';
	type CredentialMethod = 'cookie' | 'token' | 'refresh-token';
	let method = $state<CredentialMethod>('cookie');
	let credential = $state('');
	let clientId = $state('');
	let switchedTo = $state<CredentialMethod | null>(null);

	/** What a pasted credential looks like: tokens are easy to paste under the wrong method. */
	function guessMethod(text: string): CredentialMethod | null {
		const value = text.trim().replace(/^Bearer\s+/i, '');
		if (/^eyJ[\w-]+\.[\w-]+\.[\w-]*$/.test(value)) return 'token';
		if (/^M\.[\w.!*$-]+$/.test(value)) return 'refresh-token';
		if (/\t/.test(value) || /^[^=\s;]+=[^;]*(;\s*[^=\s;]+=[^;]*)*;?$/.test(value)) return 'cookie';
		return null;
	}

	function onCredentialInput() {
		const guess = guessMethod(credential);
		if (guess && guess !== method) {
			method = guess;
			switchedTo = guess;
			login = null;
		}
	}

	const methodNames: Record<CredentialMethod, string> = { cookie: 'Cookie', token: 'Token', 'refresh-token': 'Refresh token' };
	let tab = $state<Tab>('microsoft');

	// --- microsoft device code ---
	let login = $state<PendingLogin | null>(null);
	let starting = $state(false);
	const busy = $derived(starting || login?.state === 'WAITING_FOR_CODE' || login?.state === 'WAITING_FOR_USER');

	let now = $state(Date.now());
	const ticker = setInterval(() => (now = Date.now()), 1000);
	let poller: ReturnType<typeof setInterval> | undefined;

	const stopListening = app.onLogin((l) => {
		if (l.id === login?.id) update(l);
	});

	let finished = false;

	function update(l: PendingLogin) {
		login = l;
		if (l.state === 'DONE') {
			// the WebSocket and the fallback poll can both report it
			if (finished) return;
			finished = true;
			clearInterval(poller);
			toasts.success(relogin ? `Logged in to ${relogin.username} again` : 'Account added');
			// wait for the new account, so the New instance dialog can pick it
			app.refresh().catch(() => {}).finally(onclose);
		} else if (l.state === 'FAILED' || l.state === 'CANCELLED') {
			clearInterval(poller);
		}
	}

	async function startLogin(useCredential = false) {
		starting = true;
		try {
			login = await api.post<PendingLogin>('/accounts/login', {
				...(relogin ? { accountId: relogin.id } : {}),
				...(useCredential ? { method, credential, ...(method === 'refresh-token' && clientId.trim() ? { clientId: clientId.trim() } : {}) } : {})
			});
			credential = '';
			update(login);
			if (login.state === 'DONE' || login.state === 'FAILED' || login.state === 'CANCELLED') return;
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
		{ id: 'offline', label: 'Offline' },
		{ id: 'more', label: 'More' }
	];
</script>

<Modal title={relogin ? `Log in again: ${relogin.username}` : 'Add account'} onclose={close}>
	<div class="mb-5 grid {relogin ? 'grid-cols-2' : 'grid-cols-4'} gap-1 rounded-lg bg-zinc-950 p-1">
		{#each tabs.filter((t) => !relogin || t.id === 'microsoft' || t.id === 'more') as t}
			<button
				class="rounded-md py-1.5 text-sm font-medium transition-colors {tab === t.id
					? 'bg-zinc-800 text-zinc-100'
					: 'text-zinc-400 hover:text-zinc-200'}"
				onclick={() => { tab = t.id; credential = ''; if (login?.state === 'FAILED') login = null; }}
				disabled={busy && tab !== t.id}>{t.label}</button
			>
		{/each}
	</div>

	{#if tab === 'microsoft'}
		{#if !login || login.state === 'CANCELLED'}
			<p class="mb-4 text-sm text-zinc-400">
				Sign in with the Microsoft account that owns Minecraft. You'll get a code to enter on Microsoft's website; this works from
				any device, your phone included.
			</p>
			<button class="btn-primary w-full" onclick={() => startLogin()} disabled={starting}>
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
			<button class="btn-primary w-full" onclick={() => startLogin()} disabled={starting}>Try again</button>
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
	{:else if tab === 'more'}
		<form onsubmit={(e) => { e.preventDefault(); startLogin(true); }}>
			<label class="label" for="auth-method">Sign-in method</label>
			<select id="auth-method" class="input mb-4" bind:value={method} disabled={busy}
				onchange={() => { credential = ''; login = null; switchedTo = null; }}>
				<option value="cookie">Cookie</option>
				<option value="token">Token</option>
				<option value="refresh-token">Refresh token</option>
			</select>
			<p class="mb-4 text-sm text-zinc-400" id="credential-help">
				{#if method === 'cookie'}
					Paste your login.live.com Cookie header or upload a Netscape cookies.txt export. Continuing approves Minecraft Launcher's Xbox profile and automatic renewal permissions. Microsoft may still require a regular sign-in.
				{:else if method === 'token'}
					Use a Minecraft: Java Edition access token. This login lasts until the token expires; you'll need to sign in again to replace it.
				{:else}
					Use a Microsoft refresh token to keep your account signed in automatically. The application client ID must match the app that issued it.
				{/if}
			</p>
			{#if method === 'cookie'}
				<label class="btn-secondary mb-3 w-full cursor-pointer">
					<FileUp size={16} /> Choose cookie file
					<input type="file" accept=".txt,text/plain" class="hidden" disabled={busy}
						onchange={async (e) => {
							const file = e.currentTarget.files?.[0];
							if (file) credential = await file.text();
						}} />
				</label>
			{/if}
			<label class="label" for="auth-credential">{method === 'cookie' ? 'Microsoft cookies' : method === 'token' ? 'Minecraft access token' : 'Microsoft refresh token'}</label>
			<textarea id="auth-credential" class="input h-28 font-mono text-xs" bind:value={credential}
				aria-describedby="credential-help" placeholder={method === 'cookie' ? 'Paste cookies here' : 'Paste token here'}
				oninput={onCredentialInput}
				autocomplete="off" autocapitalize="off" spellcheck="false" disabled={busy}></textarea>
			{#if switchedTo === method}
				<p class="mt-2 text-xs text-emerald-400">That looks like a {method === 'token' ? 'Minecraft access token' : method === 'cookie' ? 'set of cookies' : 'Microsoft refresh token'}, so the method is now {methodNames[method]}.</p>
			{/if}
			{#if method === 'refresh-token'}
				<label class="label mt-3" for="auth-client-id">Application client ID (optional)</label>
				<input id="auth-client-id" class="input font-mono text-xs" bind:value={clientId} disabled={busy}
					placeholder="Default: Minecraft for Windows" autocomplete="off" spellcheck="false" />
			{/if}
			{#if login?.state === 'FAILED'}<p class="mt-3 text-sm text-red-400" role="alert">{login.error}</p>{/if}
			<button class="btn-primary mt-4 w-full" disabled={busy || !credential.trim()}>
				{#if busy}<LoaderCircle size={16} class="animate-spin" /> Signing in…{:else}{relogin ? 'Log in again' : 'Add account'}{/if}
			</button>
		</form>
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
