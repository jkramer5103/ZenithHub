<script lang="ts">
	import { api } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import AddAccountModal from '#lib/components/AddAccountModal.svelte';
	import OwnerBadge from '#lib/components/OwnerBadge.svelte';
	import { relative } from '#lib/format.js';
	import { attempt } from '#lib/toast.svelte.js';
	import type { Account } from '#lib/types.js';
	import { CircleAlert, Clock, KeyRound, Plus, RefreshCw, Trash2 } from '@lucide/svelte';

	let showAdd = $state(false);
	let relogin = $state<{ id: string; username: string } | null>(null);
	let refreshing = $state<string | null>(null);

	let now = $state(Date.now());
	$effect(() => {
		const timer = setInterval(() => (now = Date.now()), 60_000);
		return () => clearInterval(timer);
	});

	function expiresIn(ts: number) {
		const minutes = Math.round((ts - now) / 60_000);
		if (minutes < 60) return `${Math.max(minutes, 1)}m`;
		const hours = Math.floor(minutes / 60);
		return hours < 48 ? `${hours}h ${minutes % 60}m` : `${Math.floor(hours / 24)}d`;
	}

	const accounts = $derived(app.overview?.accounts ?? []);
	const groups = $derived(
		[
			{ id: 'mine', title: 'Your accounts', items: accounts.filter((a) => a.mine) },
			{ id: 'others', title: 'Other users’ accounts', items: accounts.filter((a) => !a.mine) }
		].filter((g) => g.items.length)
	);

	function instanceCount(account: Account) {
		return app.overview?.instances.filter((i) => i.accountId === account.id).length ?? 0;
	}

	async function refresh(account: Account) {
		refreshing = account.id;
		await attempt(() => api.post(`/accounts/${account.id}/refresh`), `Refreshed ${account.username}`);
		refreshing = null;
	}

	async function remove(account: Account) {
		if (!confirm(`Remove ${account.username}? Its saved login is deleted.`)) return;
		await attempt(async () => {
			await api.del(`/accounts/${account.id}`);
			await app.refresh();
		}, 'Account removed');
	}
</script>

<svelte:head><title>Accounts · ZenithHub</title></svelte:head>

<div class="mb-6 flex flex-wrap items-center justify-between gap-3">
	<div>
		<h1 class="text-2xl font-semibold">Accounts</h1>
		<p class="mt-1 text-sm text-zinc-400">
			Your Minecraft accounts. You have full control over them and decide who else may use them. Microsoft logins renew themselves; a pasted access token works until it expires.
		</p>
	</div>
	<button class="btn-primary" onclick={() => (showAdd = true)}><Plus size={16} /> Add account</button>
</div>

{#if accounts.length === 0}
	<div class="card py-12 text-center text-sm text-zinc-400">
		No accounts yet. Add one to run it on a server; accounts friends shared with you show up under Instances.
	</div>
{:else}
	{#each groups as group (group.id)}
	{#if groups.length > 1}
		<h2 class="mt-6 mb-3 text-sm font-semibold tracking-wide text-zinc-400 uppercase first:mt-0">{group.title}</h2>
	{/if}
	<div class="card divide-y divide-zinc-800">
		{#each group.items as account (account.id)}
			{@const count = instanceCount(account)}
			<div class="flex flex-wrap items-center gap-3 p-4">
				<img
					src="https://mc-heads.net/avatar/{account.username}/40"
					alt=""
					class="size-10 rounded-md bg-zinc-800 [image-rendering:pixelated]"
					loading="lazy"
				/>
				<div class="min-w-0 flex-1">
					<div class="flex flex-wrap items-center gap-x-2 gap-y-1">
						<span class="max-w-full truncate font-medium">{account.username}</span>
						<span class="rounded bg-zinc-800 px-1.5 py-0.5 text-[11px] text-zinc-400">
							{account.type === 'OFFLINE' ? 'Offline' : account.tokenExpiresAt ? 'Access token' : 'Microsoft'}
						</span>
						<OwnerBadge mine={account.mine} ownerName={account.ownerName} />
					</div>
					<div class="text-xs text-zinc-500">
						{count} instance{count === 1 ? '' : 's'} · added {relative(account.addedAt)}
					</div>
					{#if account.tokenExpiresAt && !account.authError}
						<div class="mt-1 flex items-center gap-1 text-xs {account.tokenExpiresAt - now < 3 * 3_600_000 ? 'text-amber-400' : 'text-zinc-500'}">
							<Clock size={13} class="shrink-0" />
							{account.tokenExpiresAt > now ? `Stops working in ${expiresIn(account.tokenExpiresAt)}. A token can't be renewed.` : 'Expired. Log in again.'}
						</div>
					{/if}
					{#if account.authError}
						<div class="mt-1 flex items-center gap-1 text-xs text-red-400">
							<CircleAlert size={13} class="shrink-0" />
							{account.authError}
						</div>
					{/if}
				</div>
				<div class="flex items-center gap-1">
					{#if account.type === 'MSA'}
						{#if account.authError || (account.tokenExpiresAt && account.tokenExpiresAt <= now)}
							<button class="btn-primary" onclick={() => (relogin = { id: account.id, username: account.username })}>
								<KeyRound size={16} /> Log in again
							</button>
						{:else if !account.tokenExpiresAt}
							<button class="btn-ghost p-2" title="Refresh login" onclick={() => refresh(account)} disabled={refreshing === account.id}>
								<RefreshCw size={16} class={refreshing === account.id ? 'animate-spin' : ''} />
							</button>
						{/if}
					{/if}
					<button
						class="btn-ghost p-2 hover:text-red-400"
						title={count ? 'Delete its instances first' : 'Remove account'}
						disabled={count > 0}
						onclick={() => remove(account)}
					>
						<Trash2 size={16} />
					</button>
				</div>
			</div>
		{/each}
	</div>
	{/each}
{/if}

{#if showAdd}<AddAccountModal onclose={() => (showAdd = false)} />{/if}
{#if relogin}<AddAccountModal {relogin} onclose={() => (relogin = null)} />{/if}
