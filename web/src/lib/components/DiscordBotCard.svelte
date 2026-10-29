<script lang="ts">
	import { api, errorMessage } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { toasts } from '#lib/toast.svelte.js';
	import type { DiscordStatus } from '#lib/types.js';
	import { ExternalLink, LoaderCircle } from '@lucide/svelte';
	import Switch from './settings/Switch.svelte';

	let status = $state<DiscordStatus | null>(null);
	let token = $state('');
	let busy = $state(false);
	let error = $state('');

	async function load() {
		try {
			status = await api.get<DiscordStatus>('/discord');
		} catch (e) {
			error = errorMessage(e);
		}
	}
	load();

	// connecting finishes in the background
	$effect(() => {
		if (status?.phase !== 'CONNECTING') return;
		const timer = setTimeout(async () => {
			await load();
			if (status?.phase === 'ONLINE') app.refresh();
		}, 1500);
		return () => clearTimeout(timer);
	});

	async function update(body: Record<string, unknown>, done?: string) {
		busy = true;
		error = '';
		try {
			status = await api.put<DiscordStatus>('/discord', body);
			token = '';
			if (done) toasts.success(done);
		} catch (e) {
			error = errorMessage(e);
		} finally {
			busy = false;
		}
	}

	async function removeToken() {
		if (!confirm('Remove the bot token and turn the bot off?')) return;
		busy = true;
		try {
			status = await api.del<DiscordStatus>('/discord/token');
			await app.refresh();
		} catch (e) {
			error = errorMessage(e);
		} finally {
			busy = false;
		}
	}
</script>

<section class="card max-w-xl p-5">
	<div class="flex items-start justify-between gap-4">
		<div>
			<h2 class="font-medium">Discord bot</h2>
			<p class="mt-0.5 text-sm text-zinc-400">
				Optional. One bot gives every instance its own channel, visible to whoever it’s shared with.
			</p>
		</div>
		{#if status?.configured}
			<Switch checked={status.enabled} disabled={busy} label="Discord bot" onchange={(on) => update({ enabled: on }, on ? undefined : 'Discord bot turned off')} />
		{/if}
	</div>

	{#if !status}
		<div class="mt-4 flex items-center gap-2 text-sm text-zinc-500"><LoaderCircle size={16} class="animate-spin" /> Loading…</div>
	{:else}
		{#if status.configured}
			<div class="mt-4 rounded-lg border border-zinc-800 bg-zinc-950/50 p-3 text-sm">
				{#if status.phase === 'ONLINE'}
					<div class="flex items-center gap-2">
						<span class="size-2 rounded-full bg-emerald-400"></span>
						Online as <span class="font-medium">{status.botName}</span>
					</div>
					{#if status.serverName}
						<p class="mt-1 text-xs text-zinc-500">Managing channels in {status.serverName}.</p>
					{:else}
						<p class="mt-1 text-xs text-zinc-400">Not in a Discord server yet.</p>
					{/if}
				{#if status.serverName && status.missingPermissions?.length}
					<div class="mt-3 rounded-md border border-amber-900/60 bg-amber-950/30 p-3 text-xs text-amber-200">
						<p class="font-medium">The bot can’t create channels yet. It’s missing:</p>
						<p class="mt-1 text-amber-300/90">{status.missingPermissions.join(', ')}</p>
						<p class="mt-2 text-amber-200/80">Open the invite link again and authorize it. That gives the bot its own role.</p>
						<div class="mt-3 flex flex-wrap gap-2">
							{#if status.inviteUrl}
								<a class="btn-primary px-3 py-1.5 text-sm" href={status.inviteUrl} target="_blank" rel="noreferrer">
									Fix permissions <ExternalLink size={14} />
								</a>
							{/if}
							<button class="btn-secondary px-3 py-1.5 text-sm" disabled={busy} onclick={() => update({ enabled: true })}>Check again</button>
						</div>
					</div>
				{:else if status.inviteUrl}
						<a class="btn-secondary mt-3 px-3 py-1.5 text-sm" href={status.inviteUrl} target="_blank" rel="noreferrer">
							{status.serverName ? 'Invite link' : 'Add the bot to your server'} <ExternalLink size={14} />
						</a>
					{/if}
				{:else if status.phase === 'CONNECTING'}
					<div class="flex items-center gap-2 text-zinc-300"><LoaderCircle size={14} class="animate-spin" /> Connecting…</div>
				{:else if status.phase === 'ERROR'}
					<div class="flex items-center gap-2 text-red-300"><span class="size-2 rounded-full bg-red-400"></span> {status.error}</div>
				{:else}
					<div class="flex items-center gap-2 text-zinc-400"><span class="size-2 rounded-full bg-zinc-600"></span> Off</div>
				{/if}
			</div>
		{/if}

		<form
			class="mt-4 space-y-2"
			onsubmit={(e) => {
				e.preventDefault();
				update({ token: token.trim(), enabled: true }, 'Token saved');
			}}
		>
			<label class="label" for="discord-token">{status.configured ? 'Replace token' : 'Bot token'}</label>
			<div class="flex gap-2">
				<input
					id="discord-token"
					class="input font-mono"
					type="password"
					bind:value={token}
					placeholder={status.configured ? '••••••••••••••••' : 'Paste the token of your bot'}
					autocomplete="off"
					spellcheck="false"
				/>
				<button class="btn-primary shrink-0" disabled={busy || token.trim().length < 50}>Save</button>
			</div>
			<p class="text-xs text-zinc-500">
				Create an application at
				<a class="text-emerald-400 underline" href="https://discord.com/developers/applications" target="_blank" rel="noreferrer">discord.com/developers</a>,
				open Bot and reset the token. No privileged intents are needed.
			</p>
		</form>
		{#if status.configured}
			<button class="mt-3 text-xs text-zinc-500 underline hover:text-red-300" disabled={busy} onclick={removeToken}>Remove token</button>
		{/if}
		{#if error}<p class="mt-3 text-sm text-red-400">{error}</p>{/if}
	{/if}
</section>
