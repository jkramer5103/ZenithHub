<script lang="ts">
	import { api } from '#lib/api.js';
	import Modal from '#lib/components/Modal.svelte';
	import { attempt } from '#lib/toast.svelte.js';
	import type { PluginView } from '#lib/types.js';

	let { plugin, onclose, onremoved }: { plugin: PluginView; onclose: () => void; onremoved: () => void } = $props();
	let deleteData = $state(false);
	let busy = $state(false);

	async function remove() {
		busy = true;
		const done = await attempt(() => api.del(`/plugins/${plugin.id}`, { deleteData }), `${plugin.name ?? plugin.id} removed`);
		busy = false;
		if (done) onremoved();
	}
</script>

<Modal title="Remove {plugin.name ?? plugin.id}?" {onclose}>
	<div class="flex flex-col gap-4 text-sm">
		<p class="text-zinc-300">
			The plugin stops loading. What it changed in ZenithHub itself goes away after the next restart, and in a bot when that bot restarts.
		</p>
		<label class="flex items-start gap-2.5">
			<input type="checkbox" class="mt-0.5 size-4 accent-red-500" bind:checked={deleteData} />
			<span>
				Also delete its settings and data
				<span class="block text-xs text-zinc-500">Otherwise they're kept, in case you install it again.</span>
			</span>
		</label>
		<div class="flex justify-end gap-2">
			<button class="btn-secondary" onclick={onclose}>Cancel</button>
			<button class="btn-danger" disabled={busy} onclick={remove}>Remove</button>
		</div>
	</div>
</Modal>
