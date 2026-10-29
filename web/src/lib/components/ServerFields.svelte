<script lang="ts">
	let { fields = $bindable() }: { fields: { name: string; address: string; port: number } } = $props();

	// fill in a name from the address unless the user typed one
	let nameTouched = $state(!!fields.name);
	function onAddress() {
		if (!nameTouched) fields.name = fields.address.trim();
	}
</script>

<div>
	<label class="label" for="server-address">Address</label>
	<div class="flex gap-2">
		<input
			id="server-address"
			class="input"
			bind:value={fields.address}
			oninput={onAddress}
			placeholder="2b2t.org"
			list="known-servers"
			autocomplete="off"
			spellcheck="false"
		/>
		<input class="input w-24" type="number" min="1" max="65535" bind:value={fields.port} aria-label="Port" />
	</div>
	<datalist id="known-servers">
		<option value="2b2t.org"></option>
		<option value="constantiam.net"></option>
		<option value="9b9t.com"></option>
	</datalist>
</div>
<div>
	<label class="label" for="server-name">Server name</label>
	<input id="server-name" class="input" bind:value={fields.name} oninput={() => (nameTouched = true)} placeholder="2b2t" />
</div>
