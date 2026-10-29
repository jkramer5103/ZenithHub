<script lang="ts">
	import { api, errorMessage } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { toasts } from '#lib/toast.svelte.js';
	import { LoaderCircle } from '@lucide/svelte';
	import CopyField from './CopyField.svelte';
	import Modal from './Modal.svelte';

	let { onclose }: { onclose: () => void } = $props();

	const linked = $derived(app.user?.discordLinked ?? false);
	let code = $state('');
	let error = $state('');
	let busy = $state(false);

	async function newCode() {
		busy = true;
		error = '';
		try {
			code = (await api.post<{ code: string }>('/me/discord/link')).code;
		} catch (e) {
			error = errorMessage(e);
		} finally {
			busy = false;
		}
	}

	async function unlink() {
		busy = true;
		try {
			await api.del('/me/discord');
			await Promise.all([app.refresh(), app.loadSession()]);
			toasts.success('Discord unlinked');
			onclose();
		} catch (e) {
			error = errorMessage(e);
		} finally {
			busy = false;
		}
	}

	if (!linked) newCode();
</script>

<Modal title="Discord" {onclose}>
	{#if linked}
		<p class="text-sm text-zinc-300">Your Discord account is linked. You see the channels of every instance shared with you.</p>
		{#if error}<p class="mt-3 text-sm text-red-400">{error}</p>{/if}
		<div class="mt-5 flex justify-end gap-2">
			<button class="btn-ghost" onclick={onclose}>Close</button>
			<button class="btn-danger" disabled={busy} onclick={unlink}>Unlink Discord</button>
		</div>
	{:else}
		<p class="text-sm text-zinc-300">
			Link your Discord account to see your instances’ channels and use commands there with your permissions.
		</p>
		<ol class="mt-4 space-y-3 text-sm">
			<li class="flex gap-3">
				<span class="flex size-6 shrink-0 items-center justify-center rounded-full bg-zinc-800 text-xs font-semibold">1</span>
				<span class="pt-0.5 text-zinc-300">Open the Discord server the ZenithHub bot is in.</span>
			</li>
			<li class="flex gap-3">
				<span class="flex size-6 shrink-0 items-center justify-center rounded-full bg-zinc-800 text-xs font-semibold">2</span>
				<div class="min-w-0 flex-1 pt-0.5">
					<span class="text-zinc-300">Send this command in any channel:</span>
					{#if code}
						<div class="mt-2"><CopyField value="/link {code}" label="Copy command" /></div>
						<p class="mt-1.5 text-xs text-zinc-500">Only you see the reply. The code works once, for 10 minutes.</p>
					{:else if busy}
						<div class="mt-2 flex items-center gap-2 text-zinc-500"><LoaderCircle size={16} class="animate-spin" /> Getting a code…</div>
					{/if}
				</div>
			</li>
		</ol>
		{#if error}<p class="mt-3 text-sm text-red-400">{error}</p>{/if}
		<div class="mt-5 flex justify-end gap-2">
			{#if code}<button class="btn-ghost" disabled={busy} onclick={newCode}>New code</button>{/if}
			<button class="btn-primary" onclick={() => Promise.all([app.refresh(), app.loadSession()]).finally(onclose)}>Done</button>
		</div>
	{/if}
</Modal>
