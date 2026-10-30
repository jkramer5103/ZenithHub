<script lang="ts">
	import type { Fingerprint } from '#lib/types.js';
	import { Eye } from '@lucide/svelte';

	let { fingerprint, canEditSettings }: { fingerprint: Fingerprint; canEditSettings: boolean } = $props();

	const translated = $derived(fingerprint.serverVersion !== fingerprint.clientVersion);
	const rows = $derived([
		{
			label: 'Client',
			value: `Vanilla ${fingerprint.clientVersion}`,
			note: translated ? `translated to the server's ${fingerprint.serverVersion}` : ''
		},
		{ label: 'Language', value: fingerprint.locale, note: '' },
		{ label: 'View distance', value: `${fingerprint.viewDistance} chunks`, note: '' },
		{ label: 'Main hand', value: fingerprint.mainHand === 'left' ? 'Left' : 'Right', note: '' },
		{ label: 'Server list', value: fingerprint.allowsListing ? 'Shown' : 'Hidden', note: '' },
		{
			label: 'Chat',
			value: { signed: 'Signed', unavailable: "Can't sign", off: 'Unsigned' }[fingerprint.chatSigning],
			note: fingerprint.chatSigning === 'off' ? "the server doesn't ask for signatures" : ''
		},
		{ label: 'Ping', value: `${fingerprint.ping} ms`, note: fingerprint.pingSource === 'tablist' ? 'from the player list' : 'from extra ping requests' },
		{ label: 'Replies', value: fingerprint.tickAlignedReplies ? 'On the next tick' : 'Instant', note: '' },
		{
			label: 'Resource packs',
			value: fingerprint.resourcePacks === 0 ? 'None' : String(fingerprint.resourcePacks),
			note: fingerprint.resourcePacks === 0 ? '' : fingerprint.downloadsResourcePacks ? 'downloaded' : 'accepted without downloading'
		},
		{ label: 'Cookies', value: fingerprint.cookies === 0 ? 'None' : `${fingerprint.cookies} stored`, note: '' }
	]);
</script>

<section class="card p-4">
	<h2 class="mb-1 flex items-center gap-2 text-sm font-semibold text-zinc-300"><Eye size={15} /> What the server sees</h2>
	<p class="mb-3 text-xs text-zinc-500">How the account's client looks to the server right now.</p>
	<dl class="divide-y divide-zinc-800/70 text-sm">
		{#each rows as row (row.label)}
			<div class="flex items-baseline gap-3 py-1.5">
				<dt class="w-28 shrink-0 text-zinc-500">{row.label}</dt>
				<dd class="min-w-0 text-zinc-200">
					{row.value}
					{#if row.note}<span class="text-xs text-zinc-500"> · {row.note}</span>{/if}
				</dd>
			</div>
		{/each}
	</dl>
	{#if canEditSettings}
		<a class="mt-3 inline-block text-xs text-emerald-400 hover:text-emerald-300" href="?tab=settings#settings-privacy">Privacy settings</a>
	{/if}
</section>
