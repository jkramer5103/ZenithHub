<script lang="ts">
	let { size = 28 }: { size?: number } = $props();
</script>

<svg width={size} height={size} viewBox="0 0 32 32" fill="none" aria-hidden="true">
	<rect width="32" height="32" rx="8" class="fill-emerald-600" />
	<path d="M9 10h14l-14 12h14" stroke="white" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />
</svg>
