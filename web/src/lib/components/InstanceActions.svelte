<script lang="ts">
	import { api } from '#lib/api.js';
	import { attempt } from '#lib/toast.svelte.js';
	import type { Instance } from '#lib/types.js';
	import { LoaderCircle, Plug, RotateCw, Unplug } from '@lucide/svelte';

	let { instance, compact = false }: { instance: Instance; compact?: boolean } = $props();

	let busy = $state<string | null>(null);
	const phase = $derived(instance.runtime.phase);
	const status = $derived(instance.runtime.status);
	const canControl = $derived(instance.capabilities.includes('CONTROL'));

	// connect returns at once; keep showing progress until it works or fails
	let connectingSince = $state<number | null>(null);
	const waiting = $derived.by(() => {
		if (connectingSince === null) return false;
		const rt = instance.runtime;
		if (rt.phase === 'BACKOFF' || (rt.phase === 'STOPPED' && rt.lastError)) return false;
		if (rt.phase === 'RUNNING' && status?.connected) return false;
		return !(status?.lastDisconnectAt && status.lastDisconnectAt >= connectingSince);
	});
	$effect(() => {
		if (connectingSince === null) return;
		const timer = setTimeout(() => (connectingSince = null), 120_000);
		return () => clearTimeout(timer);
	});

	async function run(action: string) {
		busy = action;
		let since: number | null = null;
		await attempt(async () => {
			// server time, so a phone with a wrong clock still sees the result
			const result = await api.post<{ since?: number }>(`/instances/${instance.id}/${action}`);
			since = result?.since ?? null;
		});
		connectingSince = since;
		busy = null;
	}
</script>

{#snippet button(action: string, label: string, Icon: typeof Plug, cls: string)}
	<button class={cls} onclick={() => run(action)} disabled={busy !== null} title={label}>
		{#if busy === action}<LoaderCircle size={16} class="animate-spin" />{:else}<Icon size={16} />{/if}
		{#if !compact}<span>{label}</span>{/if}
	</button>
{/snippet}

{#if canControl}
	<div class="flex flex-wrap items-center gap-2">
		{#if phase === 'STARTING' || phase === 'STOPPING' || waiting}
			<button class="btn-secondary" disabled>
				<LoaderCircle size={16} class="animate-spin" />
				{#if !compact}<span>{phase === 'STOPPING' ? 'Disconnecting' : 'Connecting'}</span>{/if}
			</button>
		{:else if phase === 'RUNNING' && status?.connected}
			{@render button('disconnect', 'Disconnect', Unplug, 'btn-secondary')}
		{:else}
			{@render button('connect', 'Connect', Plug, 'btn-primary')}
		{/if}
		{#if !compact && phase === 'RUNNING'}{@render button('restart', 'Restart', RotateCw, 'btn-secondary')}{/if}
	</div>
{/if}
