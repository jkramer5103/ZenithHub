<script lang="ts">
	import { runAction } from '#lib/actions.js';
	import type { InstanceDetails } from '#lib/types.js';
	import { ChevronDown, MousePointerClick } from '@lucide/svelte';

	let {
		instanceId,
		details,
		onchanged
	}: { instanceId: string; details: InstanceDetails | null; onchanged: () => void } = $props();

	let busy = $state(false);
	const online = $derived(!!details?.online);
	const clicking = $derived(details?.clicking ?? null);

	async function run(action: Record<string, unknown> & { action: string }, quiet = false) {
		busy = true;
		await runAction(instanceId, action, quiet);
		busy = false;
		onchanged();
	}

	/** turns holding a button on, or off when it's already held */
	function hold(button: 'left' | 'right') {
		run(clicking === button ? { action: 'clickStop' } : { action: 'click', button, mode: 'hold' });
	}

	let yaw = $state('');
	let pitch = $state('0');
	let exact = $state(false);
	const directions = [
		{ label: 'N', yaw: 180 },
		{ label: 'E', yaw: -90 },
		{ label: 'S', yaw: 0 },
		{ label: 'W', yaw: 90 }
	];
</script>

<section class="card p-4">
	<h2 class="mb-3 flex items-center gap-2 text-sm font-semibold text-zinc-300"><MousePointerClick size={15} /> Click and look</h2>
	<div class="space-y-4">
		<div>
			<div class="mb-1.5 text-xs text-zinc-500">Click once at whatever the bot is looking at</div>
			<div class="grid grid-cols-3 gap-2">
				<button class="btn-secondary px-2" disabled={busy || !online} onclick={() => run({ action: 'click', button: 'right' })}>Right click</button>
				<button class="btn-secondary px-2" disabled={busy || !online} onclick={() => run({ action: 'click', button: 'left' })}>Left click</button>
				<button class="btn-secondary px-2" disabled={busy || !online} onclick={() => run({ action: 'click', button: 'right', mode: 'useItem' })}>
					Use item
				</button>
			</div>
		</div>

		<div>
			<div class="mb-1.5 text-xs text-zinc-500">Keep clicking, e.g. for an AFK fish or mob farm</div>
			<div class="grid grid-cols-2 gap-2">
				{#each ['right', 'left'] as const as button}
					<button
						class="rounded-lg border px-3 py-2 text-sm font-medium disabled:opacity-50 {clicking === button
							? 'border-emerald-600 bg-emerald-950/60 text-emerald-200'
							: 'border-zinc-700 text-zinc-300 hover:border-zinc-500'}"
						aria-pressed={clicking === button}
						disabled={busy || !online}
						onclick={() => hold(button)}
					>
						{clicking === button ? `Holding ${button}` : `Hold ${button}`}
					</button>
				{/each}
			</div>
		</div>

		<div>
			<div class="mb-1.5 text-xs text-zinc-500">Face a direction</div>
			<div class="flex items-center gap-1">
				{#each directions as d}
					<button
						class="btn-secondary w-10 px-0"
						title="Face {d.label}"
						disabled={busy || !online}
						onclick={() => run({ action: 'rotate', yaw: d.yaw, pitch: 0 }, true)}>{d.label}</button
					>
				{/each}
				<button
					type="button"
					class="ml-auto inline-flex items-center gap-0.5 px-1 text-xs text-zinc-400 hover:text-zinc-200"
					aria-expanded={exact}
					onclick={() => (exact = !exact)}
				>
					Exact angle <ChevronDown size={13} class={exact ? 'rotate-180' : ''} />
				</button>
			</div>
			{#if exact}
				<form
					class="mt-2 flex items-end gap-2"
					onsubmit={(e) => {
						e.preventDefault();
						run({ action: 'rotate', yaw: Number(yaw), pitch: Number(pitch) }, true);
					}}
				>
					<label class="w-20">
						<span class="mb-1 block text-xs text-zinc-400">Yaw</span>
						<input class="input py-1.5" inputmode="decimal" placeholder="0" bind:value={yaw} />
					</label>
					<label class="w-20">
						<span class="mb-1 block text-xs text-zinc-400">Pitch</span>
						<input class="input py-1.5" inputmode="decimal" placeholder="0" bind:value={pitch} />
					</label>
					<button class="btn-secondary" disabled={busy || !online || yaw.trim() === '' || pitch.trim() === ''}>Look</button>
				</form>
			{/if}
		</div>
	</div>
</section>
