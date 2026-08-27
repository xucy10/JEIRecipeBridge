package com.mrbysco.jeicompat;

import com.mrbysco.jeicompat.compat.fabric.FabricRecipeSyncPayload;
import com.mrbysco.jeicompat.compat.neoforge.NeoforgeRecipeSyncPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RecipeHandler implements Listener {

	/**
	 * Fallback delay for the recipe sync when the brand is still unknown at join time (Folia and
	 * Paper 26.1.x can register plugin channels asynchronously, several ticks after the join
	 * event). On Paper pre-26.x the brand is known on join and the 1-tick sync wins; this is the
	 * safety net for the asynchronous case.
	 */
	private static final long FALLBACK_DELAY_TICKS = 40L;

	/**
	 * Players whose recipe payload has already been sent. Prevents double-sends when the join
	 * event, the channel-register event and the fallback timer all end up scheduling the same
	 * sync. {@link PlayerQuitEvent} clears the entry so a future re-join can sync again.
	 */
	private static final ConcurrentHashMap<UUID, Boolean> SYNCED = new ConcurrentHashMap<>();

	/**
	 * Conservative upper bound for a single {@code fabric:recipe_sync} custom-payload packet.
	 * Paper/Mojang's hard limit is 1 MiB; we sit well below it so the chunker has plenty of
	 * headroom for protocol overhead (packet id, length prefix, etc.). On very large modpacks
	 * (thousands of recipes) a single Fabric payload can easily exceed this and would otherwise
	 * be silently dropped by the client — chunking turns one giant packet into several smaller
	 * ones that the client concatenates by channel id.
	 */
	private static final int MAX_FABRIC_PAYLOAD_BYTES = 100 * 1024;

	@EventHandler
	public void onJoin(PlayerJoinEvent event) {
		final Player originalPlayer = event.getPlayer();
		// Schedule on the player's scheduler so the sync runs on the thread that owns the player's
		// region. On Paper this is effectively the main thread; on Folia it routes the work to the
		// correct region thread, which is the only place that can safely send packets to that
		// player and read player-owned state.
		originalPlayer.getScheduler().runDelayed(
				JEIRecipeBridgePlugin.Plugin,
				task -> syncTo(originalPlayer),
				null,
				1L
		);
		// Fallback for clients whose brand is not yet known at join time. The dedupe in
		// syncTo() makes this idempotent with the earlier scheduling.
		originalPlayer.getScheduler().runDelayed(
				JEIRecipeBridgePlugin.Plugin,
				task -> syncTo(originalPlayer),
				null,
				FALLBACK_DELAY_TICKS
		);
	}

	@EventHandler
	public void onChannelRegister(PlayerRegisterChannelEvent event) {
		// Fabric clients advertise "fabric:recipe_sync"; NeoForge clients advertise
		// "neoforge:recipe_content". Listening here lets us trigger the sync the moment the
		// client signals it can receive the payload, which on Folia and Paper 26.1.x can be
		// several ticks after PlayerJoinEvent fires.
		String channel = event.getChannel();
		if (!"fabric:recipe_sync".equals(channel) && !"neoforge:recipe_content".equals(channel)) {
			return;
		}
		Player player = event.getPlayer();
		if (!player.isOnline()) {
			return;
		}
		// 1-tick slack so the rest of the channel burst (e.g. JEI's own channels) is registered too.
		player.getScheduler().runDelayed(
				JEIRecipeBridgePlugin.Plugin,
				task -> syncTo(player),
				null,
				1L
		);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		SYNCED.remove(event.getPlayer().getUniqueId());
	}

	@EventHandler
	public void onRespawn(PlayerRespawnEvent event) {
		// Vanilla sends a replace=true recipe-book update on respawn, which drops every recipe
		// this plugin added and empties JEI's display until the player rejoins. Re-sync a few
		// ticks after respawn so the client recovers. We only do this for players we have
		// already synced once (i.e. they are in our SYNCED map), so brand-new players still
		// rely on the join-event path.
		final Player player = event.getPlayer();
		if (!SYNCED.containsKey(player.getUniqueId())) {
			return;
		}
		// Clear the dedupe slot so syncTo actually runs again.
		SYNCED.remove(player.getUniqueId());
		// 5-tick delay: let the vanilla respawn packets land first, then push our overlay back on top.
		player.getScheduler().runDelayed(
				JEIRecipeBridgePlugin.Plugin,
				task -> syncTo(player),
				null,
				5L
		);
	}

	private static void syncTo(Player originalPlayer) {
		if (originalPlayer == null || !originalPlayer.isOnline()) {
			return;
		}
		// Dedupe across the three trigger paths (join event, channel register, fallback timer).
		// putIfAbsent inserts only on first call; later calls return the previous Boolean.TRUE and
		// we bail out without sending a second payload.
		if (SYNCED.putIfAbsent(originalPlayer.getUniqueId(), Boolean.TRUE) != null) {
			return;
		}

		String brand = originalPlayer.getClientBrandName();
		if (brand == null) {
			// Brand not yet known. The other triggers will re-attempt; release the dedupe slot so
			// they can succeed when the brand finally arrives.
			SYNCED.remove(originalPlayer.getUniqueId());
			return;
		}
		final ServerPlayer player = ((CraftPlayer) originalPlayer).getHandle();
		final MinecraftServer server = player.level().getServer();
		final RecipeManager recipeManager = server.getRecipeManager();

		RecipeMap recipeMap = recipeManager.recipes;

		if (brand.equalsIgnoreCase("fabric")) {
			sendFabricPayload(player, server, recipeMap);
		} else if (brand.equalsIgnoreCase("neoforge")) {
			RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
			sendNeoForgePayload(player, server, recipeMap, buffer);
		}
		originalPlayer.sendMessage("§6PaperMC JEI Compat: Syncing Recipes...§r");
	}

	private static void sendNeoForgePayload(ServerPlayer player, MinecraftServer server, RecipeMap recipeMap, RegistryFriendlyByteBuf buffer) {
		List<RecipeType<?>> allRecipeTypes = BuiltInRegistries.RECIPE_TYPE.stream().toList();
		var payload = NeoforgeRecipeSyncPayload.create(allRecipeTypes, recipeMap);
		NeoforgeRecipeSyncPayload.STREAM_CODEC.encode(buffer, payload);

		byte[] bytes = new byte[buffer.writerIndex()];
		buffer.getBytes(0, bytes);

		sendPayload(player, Identifier.fromNamespaceAndPath("neoforge", "recipe_content"), bytes);

		player.connection.send(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(server.registries())));
	}

	private static void sendFabricPayload(ServerPlayer player, MinecraftServer server, RecipeMap recipeMap) {
		var allEntries = new ArrayList<FabricRecipeSyncPayload.Entry>();
		var seen = new HashSet<RecipeSerializer<?>>();

		// Fabric's recipe-sync decoder rejects the WHOLE payload if it meets a serializer the
		// client did not opt into. JEI / REI / EMI only opt into the `minecraft:` namespace, so any
		// foreign serializer would cost us every recipe in the payload. We pre-filter here and
		// emit a one-time warning per foreign serializer so the operator knows recipes are being
		// dropped on purpose.
		int skippedRecipes = 0;
		var skippedGroups = new HashSet<String>();

		for (RecipeSerializer<?> serializer : BuiltInRegistries.RECIPE_SERIALIZER) {
			if (!seen.add(serializer)) continue; // skip duplicates

			Identifier serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(serializer);
			if (serializerId == null || !"minecraft".equals(serializerId.getNamespace())) {
				// Not a vanilla serializer. Count recipes that would have belonged to it for the
				// skip report but do not include them in the payload.
				if (skippedGroups.add(String.valueOf(serializerId))) {
					for (RecipeHolder<?> holder : recipeMap.values()) {
						if (holder.value().getSerializer() == serializer) {
							skippedRecipes++;
						}
					}
				}
				continue;
			}

			List<RecipeHolder<?>> recipes = new ArrayList<>();
			for (RecipeHolder<?> holder : recipeMap.values()) {
				if (holder.value().getSerializer() == serializer) {
					recipes.add(holder);
				}
			}

			if (!recipes.isEmpty()) {
				RecipeSerializer<?> entrySerializer = recipes.get(0).value().getSerializer();
				allEntries.add(new FabricRecipeSyncPayload.Entry(entrySerializer, recipes));
			}
		}

		if (!skippedGroups.isEmpty()) {
			JEIRecipeBridgePlugin.LOGGER.warn(
				"Dropped {} Fabric recipe(s) from non-vanilla serializers: {} (Fabric clients "
					+ "discard the entire payload if they encounter an unknown serializer; keeping these "
					+ "would drop every other recipe too).",
				skippedRecipes,
				String.join(", ", skippedGroups));
		}

		// Chunk entries into packets no larger than MAX_FABRIC_PAYLOAD_BYTES. The Fabric decoder
		// reassembles by channel id, so back-to-back packets appear as one logical update on the
		// client. We measure each entry's encoded size with a one-shot encode so the chunker can
		// greedy-pack without re-encoding during emission.
		var chunker = new FabricChunker(allEntries);
		chunker.packInto(player, server, MAX_FABRIC_PAYLOAD_BYTES);
	}

	/**
	 * Greedy first-fit-decreasing chunker for Fabric recipe payloads. Encodes each entry once to
	 * learn its size, sorts entries by size descending, then packs into bins of size
	 * {@code maxBytes}. Single oversized entries (rare; happens when one serializer alone has
	 * thousands of recipes) are emitted in their own packet so we still send them rather than
	 * silently dropping them — the client will refuse the chunk, but at least we tried.
	 */
	private static final class FabricChunker {
		private final List<FabricRecipeSyncPayload.Entry> entries;

		FabricChunker(List<FabricRecipeSyncPayload.Entry> entries) {
			this.entries = entries;
		}

		void packInto(ServerPlayer player, MinecraftServer server, int maxBytes) {
			if (entries.isEmpty()) {
				return;
			}

			// One-shot encode each entry to learn its size. Sorted descending so big entries go
			// first and small entries fill the gaps.
			var measured = new ArrayList<int[]>(entries.size()); // [idx, size]
			for (int i = 0; i < entries.size(); i++) {
				io.netty.buffer.ByteBuf buf = null;
				try {
					buf = entries.get(i).encodeToBuffer(server);
					measured.add(new int[]{i, buf.readableBytes()});
				} finally {
					if (buf != null) buf.release();
				}
			}
			measured.sort((a, b) -> Integer.compare(b[1], a[1]));

			var bins = new ArrayList<List<Integer>>();
			var binBytes = new ArrayList<Integer>();
			for (int[] e : measured) {
				int size = e[1];
				boolean placed = false;
				if (size <= maxBytes) {
					for (int b = 0; b < bins.size(); b++) {
						if (binBytes.get(b) + size <= maxBytes) {
							bins.get(b).add(e[0]);
							binBytes.set(b, binBytes.get(b) + size);
							placed = true;
							break;
						}
					}
				}
				if (!placed) {
					// Either the entry itself exceeds maxBytes, or no bin has room. Start a new
					// bin with just this entry. If the entry is oversized, the client will reject
					// the resulting packet; we still send it so the user can see in their logs
					// that something is wrong.
					var bin = new ArrayList<Integer>();
					bin.add(e[0]);
					bins.add(bin);
					binBytes.add(size);
				}
			}

			// Emit one FabricRecipeSyncPayload per bin.
			for (List<Integer> bin : bins) {
				var payloadEntries = new ArrayList<FabricRecipeSyncPayload.Entry>(bin.size());
				for (int idx : bin) {
					payloadEntries.add(entries.get(idx));
				}
				var payload = new FabricRecipeSyncPayload(payloadEntries);
				io.netty.buffer.ByteBuf buf = null;
				try {
					buf = payload.encodeToBuffer(server);
					byte[] bytes = new byte[buf.readableBytes()];
					buf.getBytes(0, bytes);
					sendPayload(player, Identifier.fromNamespaceAndPath("fabric", "recipe_sync"), bytes);
				} finally {
					if (buf != null) buf.release();
				}
			}

			if (bins.size() > 1) {
				JEIRecipeBridgePlugin.LOGGER.info(
					"Split Fabric recipe payload into {} packets ({} entries total) for {}.",
					bins.size(), entries.size(), player.getName().getString());
			}
		}
	}

	private static void sendPayload(ServerPlayer player, Identifier id, byte[] bytes) {
		player.connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(id, bytes)));
	}
}