<script lang="ts">
	import { api, errorMessage } from '#lib/api.js';
	import { app } from '#lib/app.svelte.js';
	import { toasts } from '#lib/toast.svelte.js';
	import { goto } from '$app/navigation';
	import AddAccountModal from './AddAccountModal.svelte';
	import Modal from './Modal.svelte';
	import ServerFields from './ServerFields.svelte';

	let { onclose }: { onclose: () => void } = $props();

	// your own accounts first; only the admin sees other people's
	const accounts = $derived([...(app.overview?.accounts ?? [])].sort((a, b) => Number(b.mine) - Number(a.mine)));
	const servers = $derived((app.overview?.servers ?? []).filter((s) => s.usable));
	const NEW_SERVER = '__new__';

	let accountId = $state(accounts[0]?.id ?? '');
	let serverId = $state(servers[0]?.id ?? NEW_SERVER);
	let addingAccount = $state(false);
	let newServer = $state({ name: '', address: '', port: 25565 });
	let name = $state('');
	let nameTouched = $state(false);
	let start = $state(true);
	let saving = $state(false);
	let error = $state('');

	const taken = $derived(
		app.overview?.instances.some((i) => i.accountId === accountId && i.serverId === serverId) ?? false
	);

	// suggest "Account @ server" until the user types a name
	$effect(() => {
		if (nameTouched) return;
		const account = accounts.find((a) => a.id === accountId)?.username ?? '';
		const server = serverId === NEW_SERVER ? newServer.name : servers.find((s) => s.id === serverId)?.name;
		name = account && server ? `${account} @ ${server}` : account;
	});

	async function create(e: SubmitEvent) {
		e.preventDefault();
		saving = true;
		error = '';
		try {
			let sid = serverId;
			if (sid === NEW_SERVER) {
				sid = (await api.post<{ id: string }>('/servers', newServer)).id;
				serverId = sid;
			}
			const { id } = await api.post<{ id: string }>('/instances', { name: name.trim(), accountId, serverId: sid, start });
			await app.refresh();
			toasts.success(start ? 'Instance created and connecting' : 'Instance created');
			onclose();
			goto(`/instances/${id}`);
		} catch (e) {
			error = errorMessage(e);
		} finally {
			saving = false;
		}
	}
</script>

<Modal title="New instance" {onclose}>
	<form onsubmit={create} class="space-y-4">
		<p class="text-sm text-zinc-400">An instance is one account playing on one server. One account can be on several servers at once.</p>
		<div>
			<label class="label" for="instance-account">Account</label>
			{#if accounts.length}
				<div class="flex gap-2">
					<select id="instance-account" class="input" bind:value={accountId}>
						{#each accounts as account}
							<option value={account.id}
								>{account.username}{account.type === 'OFFLINE' ? ' (offline)' : ''}{account.mine ? '' : ` · ${account.ownerName ?? 'someone'}’s`}</option
							>
						{/each}
					</select>
					<button type="button" class="btn-secondary shrink-0" onclick={() => (addingAccount = true)}>Add</button>
				</div>
			{:else}
				<button type="button" class="btn-secondary w-full" onclick={() => (addingAccount = true)}>Add a Minecraft account</button>
			{/if}
		</div>
		<div>
			<label class="label" for="instance-server">Server</label>
			<select id="instance-server" class="input" bind:value={serverId}>
				{#each servers as server}
					<option value={server.id}
						>{server.name} ({server.address}{server.port !== 25565 ? `:${server.port}` : ''}){server.mine ? '' : server.shared ? ' · shared' : ` · ${server.ownerName}’s`}</option
					>
				{/each}
				<option value={NEW_SERVER}>+ New server…</option>
			</select>
		</div>
		{#if serverId === NEW_SERVER}
			<div class="space-y-4 rounded-lg border border-zinc-800 bg-zinc-950/50 p-4">
				<ServerFields bind:fields={newServer} />
			</div>
		{/if}
		{#if taken}
			<p class="text-sm text-amber-400">This account already has an instance on that server.</p>
		{/if}
		<div>
			<label class="label" for="instance-name">Instance name</label>
			<input id="instance-name" class="input" bind:value={name} oninput={() => (nameTouched = true)} />
		</div>
		<label class="flex items-center gap-2 text-sm text-zinc-300">
			<input type="checkbox" class="size-4 accent-emerald-600" bind:checked={start} /> Connect right away
		</label>
		{#if error}<p class="text-sm text-red-400">{error}</p>{/if}
		<button
			class="btn-primary w-full"
			disabled={saving || taken || !accountId || !name.trim() || (serverId === NEW_SERVER && !newServer.address.trim())}
		>
			Create instance
		</button>
	</form>
</Modal>

{#if addingAccount}
	<AddAccountModal
		onclose={() => {
			addingAccount = false;
			// pick the account that was just added
			const newest = [...(app.overview?.accounts ?? [])].filter((a) => a.mine).sort((a, b) => b.addedAt - a.addedAt)[0];
			if (newest) accountId = newest.id;
		}}
	/>
{/if}
