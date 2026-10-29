<script lang="ts">
	import { api, errorMessage } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { toasts } from '#lib/toast.svelte.js';
	import { LoaderCircle, Unlink } from '@lucide/svelte';
	import CopyField from './CopyField.svelte';
	import Modal from './Modal.svelte';

	let { onclose }: { onclose: () => void } = $props();

	const names = $derived(app.user?.mcNames ?? []);
	const address = $derived(app.overview?.publicAddress ?? '');
	let code = $state('');
	let error = $state('');
	let busy = $state(false);

	async function claim(e?: SubmitEvent) {
		e?.preventDefault();
		if (busy || code.length < 6) return;
		busy = true;
		error = '';
		try {
			const { name } = await api.post<{ name: string }>('/me/minecraft', { code });
			await app.refresh();
			toasts.success(`${name} is linked`);
			code = '';
		} catch (err) {
			error = errorMessage(err);
		} finally {
			busy = false;
		}
	}

	async function unlink(name: string) {
		if (!confirm(`Unlink ${name}? It loses access to everything you own.`)) return;
		try {
			await api.del(`/me/minecraft/${encodeURIComponent(name)}`);
			await app.refresh();
			toasts.success(`${name} unlinked`);
		} catch (err) {
			toasts.error(err);
		}
	}

	function onInput(e: Event) {
		code = (e.target as HTMLInputElement).value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6);
		error = '';
		if (code.length === 6) claim();
	}
</script>

<Modal title="Minecraft accounts" {onclose}>
	<p class="text-sm text-zinc-300">
		Link the Minecraft accounts you play with. In-game they can do everything you can: full control of your own accounts, plus whatever
		friends shared with you. No one has to add them by name.
	</p>

	{#if names.length}
		<ul class="mt-4 divide-y divide-zinc-800 rounded-lg border border-zinc-800">
			{#each names as name (name)}
				<li class="flex items-center gap-3 px-3 py-2.5">
					<img src="https://mc-heads.net/avatar/{name}/32" alt="" class="size-8 rounded bg-zinc-800 [image-rendering:pixelated]" />
					<span class="min-w-0 flex-1 truncate font-medium">{name}</span>
					<span class="rounded-full bg-emerald-500/10 px-2 py-0.5 text-xs text-emerald-400 ring-1 ring-emerald-500/25 ring-inset">Linked</span>
					<button class="btn-ghost p-1.5 hover:text-red-400" title="Unlink {name}" aria-label="Unlink {name}" onclick={() => unlink(name)}>
						<Unlink size={16} />
					</button>
				</li>
			{/each}
		</ul>
	{/if}

	<h3 class="mt-5 text-sm font-semibold text-zinc-200">{names.length ? 'Link another account' : 'Link an account'}</h3>
	<ol class="mt-3 space-y-3 text-sm">
		<li class="flex gap-3">
			<span class="flex size-6 shrink-0 items-center justify-center rounded-full bg-zinc-800 text-xs font-semibold">1</span>
			<div class="min-w-0 flex-1 pt-0.5">
				<span class="text-zinc-300">Join this server in Minecraft with the account:</span>
				{#if address}
					<div class="mt-2"><CopyField value={address} size="sm" label="Copy address" /></div>
				{:else}
					<p class="mt-1 text-xs text-amber-400">The admin hasn’t set a public address yet (Settings).</p>
				{/if}
			</div>
		</li>
		<li class="flex gap-3">
			<span class="flex size-6 shrink-0 items-center justify-center rounded-full bg-zinc-800 text-xs font-semibold">2</span>
			<span class="pt-0.5 text-zinc-300">
				You’ll see a 6-character code: on the disconnect screen, or after clicking <span class="text-zinc-100">Link this account</span> in the
				lobby menu.
			</span>
		</li>
		<li class="flex gap-3">
			<span class="flex size-6 shrink-0 items-center justify-center rounded-full bg-zinc-800 text-xs font-semibold">3</span>
			<form class="min-w-0 flex-1 pt-0.5" onsubmit={claim}>
				<label class="text-zinc-300" for="mc-link-code">Enter it here:</label>
				<div class="mt-2 flex gap-2">
					<input
						id="mc-link-code"
						class="input text-center font-mono text-lg tracking-[0.3em] uppercase"
						placeholder="ABC123"
						autocomplete="off"
						autocapitalize="characters"
						spellcheck="false"
						value={code}
						oninput={onInput}
						disabled={busy}
					/>
					<button class="btn-primary shrink-0" disabled={busy || code.length < 6}>
						{#if busy}<LoaderCircle size={16} class="animate-spin" />{/if} Link
					</button>
				</div>
				<p class="mt-1.5 text-xs text-zinc-500">Codes work once, for 10 minutes.</p>
			</form>
		</li>
	</ol>
	{#if error}<p class="mt-3 text-sm text-red-400">{error}</p>{/if}
	<div class="mt-5 flex justify-end">
		<button class="btn-ghost" onclick={onclose}>Done</button>
	</div>
</Modal>
