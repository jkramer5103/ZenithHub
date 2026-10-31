<script lang="ts">
	import SettingsEditor from '#lib/components/settings/SettingsEditor.svelte';
</script>

<svelte:head><title>Defaults · ZenithHub</title></svelte:head>

<h1 class="text-2xl font-semibold">Defaults for all instances</h1>
<p class="mt-1 mb-6 max-w-2xl text-sm text-zinc-400">Every instance starts with these. Servers and instances can change any of them for themselves.</p>
<SettingsEditor endpoint="/settings/global" scope="global" />
