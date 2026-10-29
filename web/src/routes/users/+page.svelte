<script lang="ts">
	import { api } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import UserModal from '#lib/components/UserModal.svelte';
	import { attempt } from '#lib/toast.svelte.js';
	import type { ManagedUser } from '#lib/types.js';
	import { LoaderCircle, Pencil, Plus, Trash2 } from '@lucide/svelte';

	let users = $state<ManagedUser[] | null>(null);
	let editing = $state<ManagedUser | null | undefined>(undefined);

	async function load() {
		users = await api.get<ManagedUser[]>('/users');
	}
	load();

	async function remove(user: ManagedUser) {
		if (!confirm(`Delete ${user.username}? They lose access to every instance.`)) return;
		await attempt(async () => {
			await api.del(`/users/${user.id}`);
			await Promise.all([load(), app.refresh()]);
		}, 'User deleted');
	}
</script>

<svelte:head><title>Users · ZenithProxy</title></svelte:head>

<div class="mb-6 flex flex-wrap items-center justify-between gap-3">
	<div>
		<h1 class="text-2xl font-semibold">Users</h1>
		<p class="mt-1 text-sm text-zinc-400">People who can sign in. Share instances with them from each instance’s Sharing tab.</p>
	</div>
	<button class="btn-primary" onclick={() => (editing = null)}><Plus size={16} /> Add user</button>
</div>

{#if !users}
	<div class="flex items-center justify-center gap-2 py-16 text-sm text-zinc-400"><LoaderCircle size={18} class="animate-spin" /> Loading…</div>
{:else}
	<div class="card divide-y divide-zinc-800">
		{#each users as user (user.id)}
			<div class="flex items-center gap-3 p-4">
				<span class="flex size-9 shrink-0 items-center justify-center rounded-full bg-zinc-800 text-sm font-semibold text-zinc-200 uppercase">
					{user.username.charAt(0)}
				</span>
				<div class="min-w-0 flex-1">
					<div class="flex flex-wrap items-center gap-2">
						<span class="truncate font-medium">{user.username}</span>
						{#if user.admin}<span class="rounded bg-emerald-950 px-1.5 py-px text-[11px] font-medium text-emerald-300">Administrator</span>{/if}
						{#if user.discordId}<span class="rounded bg-indigo-950 px-1.5 py-px text-[11px] font-medium text-indigo-300">Discord</span>{/if}
					</div>
					<div class="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-zinc-500">
						{#if user.admin}
							<span>Access to everything</span>
						{:else}
							<span>{user.instanceCount} instance{user.instanceCount === 1 ? '' : 's'}</span>
						{/if}
						{#each user.mcNames as name}
							<span class="flex items-center gap-1">
								<img src="https://mc-heads.net/avatar/{name}/16" alt="" class="size-4 rounded-sm [image-rendering:pixelated]" />
								{name}
							</span>
						{/each}
					</div>
				</div>
				<button class="btn-ghost p-2" title="Edit" aria-label="Edit {user.username}" onclick={() => (editing = user)}><Pencil size={16} /></button>
				{#if !user.admin}
					<button class="btn-ghost p-2 hover:text-red-400" title="Delete" aria-label="Delete {user.username}" onclick={() => remove(user)}>
						<Trash2 size={16} />
					</button>
				{/if}
			</div>
		{/each}
	</div>
	{#if users.length === 1}
		<p class="mt-4 text-center text-sm text-zinc-500">Only you so far. Add friends to share instances with them.</p>
	{/if}
{/if}

{#if editing !== undefined}<UserModal user={editing} onclose={() => (editing = undefined)} onsaved={load} />{/if}
