package com.mrbysco.jeicompat.compat.fabric;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.SkipPacketDecoderException;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeSerializer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public record FabricRecipeSyncPayload(List<Entry> entries) implements CustomPacketPayload {
	public static final StreamCodec<RegistryFriendlyByteBuf, FabricRecipeSyncPayload> CODEC = Entry.CODEC.apply(ByteBufCodecs.list())
			.map(FabricRecipeSyncPayload::new, FabricRecipeSyncPayload::entries);

	public static final Type<FabricRecipeSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("fabric", "recipe_sync"));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/**
	 * Reflection-resolved handles for accessing the {@code streamCodec()} of a {@link RecipeSerializer}
	 * and invoking {@code StreamEncoder#encode} / {@code StreamCodec#decode}.
	 *
	 * <p>{@link RecipeSerializer} on Paper 1.21.11 is an abstract class exposing
	 * {@code streamCodec()} directly. On Paper 26.1.x and on Folia / Lophine forks the same type
	 * is a plain interface — the {@code streamCodec()} call then raises
	 * {@code IncompatibleClassChangeError} (see Mrbysco/JEIRecipeBridge issue #6) because the
	 * method has been moved to the implementing class.</p>
	 *
	 * <p>Resolution strategy: try {@code RecipeSerializer.class.getMethod("streamCodec")} at class
	 * load. If it exists, the call site is using the 1.21.11 shape and we keep using the direct
	 * reflective invocation (still works without the {@code deprecation} warning if Mojang renames
	 * the API later). If it does not exist, we fall back to walking the instance's class hierarchy
	 * to find the codec. The lookup is cached because the result is the same for every recipe of
	 * a given serializer type and the path is hot (one per recipe, once per join).</p>
	 */
	private static final class SerializerCodec {
		private static final Method ENCODE_METHOD;
		/**
		 * {@code null} when {@link RecipeSerializer#streamCodec()} is not declared on the type (the
		 * Folia / 26.1.x shape), so the per-instance resolution below must be used instead.
		 */
		private static final Method DECLARED_STREAM_CODEC;
		private static final ConcurrentHashMap<Class<?>, Method> PER_INSTANCE_CACHE = new ConcurrentHashMap<>();
		private static final ConcurrentHashMap<Class<?>, Method> DECODE_CACHE = new ConcurrentHashMap<>();

		static {
			Method encode = null;
			Method declared = null;
			try {
				Class<?> encoder = Class.forName("net.minecraft.network.codec.StreamEncoder");
				encode = encoder.getMethod("encode", Object.class, Object.class);
			} catch (ClassNotFoundException | NoSuchMethodException e) {
				throw new ExceptionInInitializerError(e);
			}
			try {
				declared = RecipeSerializer.class.getMethod("streamCodec");
			} catch (NoSuchMethodException ignored) {
				// 26.1.x / Folia / Lophine: RecipeSerializer is an interface; resolve per instance.
			}
			ENCODE_METHOD = encode;
			DECLARED_STREAM_CODEC = declared;
		}

		static Object streamCodecOf(RecipeSerializer<?> serializer) {
			if (DECLARED_STREAM_CODEC != null) {
				try {
					return DECLARED_STREAM_CODEC.invoke(serializer);
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException("Failed to invoke RecipeSerializer#streamCodec()", e);
				}
			}
			return resolvePerInstance(serializer);
		}

		private static Object resolvePerInstance(RecipeSerializer<?> serializer) {
			Class<?> key = serializer.getClass();
			Method cached = PER_INSTANCE_CACHE.get(key);
			if (cached != null) {
				try {
					return cached.invoke(serializer);
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException("Failed to invoke streamCodec() on " + key, e);
				}
			}
			for (Class<?> c = key; c != null; c = c.getSuperclass()) {
				try {
					Method m = c.getDeclaredMethod("streamCodec");
					m.setAccessible(true);
					PER_INSTANCE_CACHE.put(key, m);
					return m.invoke(serializer);
				} catch (NoSuchMethodException ignored) {
					// keep walking up the hierarchy
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException("Failed to invoke streamCodec() on " + c, e);
				}
			}
			throw new IllegalStateException("No streamCodec() method found on " + key);
		}

		static void encode(Object codec, Object buf, Object recipe) {
			try {
				ENCODE_METHOD.invoke(codec, buf, recipe);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("Failed to invoke StreamEncoder#encode(...)", e);
			}
		}

		static Object decode(Object codec, Object buf) {
			// StreamCodec extends StreamEncoder and adds StreamCodec#decode(BufT) -> T. The bytecode
			// signature pins the parameter type at compile time (e.g. RegistryFriendlyByteBuf on
			// Paper 1.21.11 / 26.1.x), so we have to look up the method on the actual codec class
			// with its real parameter type rather than guessing Object.
			Class<?> codecClass = codec.getClass();
			Method cached = DECODE_CACHE.get(codecClass);
			if (cached != null) {
				try {
					return cached.invoke(codec, buf);
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException("Failed to invoke StreamCodec#decode(...)", e);
				}
			}
			for (Method m : codecClass.getMethods()) {
				if (!"decode".equals(m.getName())) continue;
				if (m.getParameterCount() != 1) continue;
				Class<?> pt = m.getParameterTypes()[0];
				if (pt.isInstance(buf)) {
					m.setAccessible(true);
					DECODE_CACHE.put(codecClass, m);
					try {
						return m.invoke(codec, buf);
					} catch (ReflectiveOperationException e) {
						throw new IllegalStateException("Failed to invoke StreamCodec#decode(...)", e);
					}
				}
			}
			throw new IllegalStateException("No compatible decode(...) method found on " + codecClass);
		}
	}

	public record Entry(RecipeSerializer<?> serializer, List<RecipeHolder<?>> recipes) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.ofMember(
				Entry::write,
				Entry::read
		);

		private static Entry read(RegistryFriendlyByteBuf buf) {
			Identifier recipeSerializerId = buf.readIdentifier();
			RecipeSerializer<?> recipeSerializer = BuiltInRegistries.RECIPE_SERIALIZER.getValue(recipeSerializerId);

			if (recipeSerializer == null) {
				throw new SkipPacketDecoderException("Tried syncing unsupported packet serializer '" + recipeSerializerId + "'!");
			}

			int count = buf.readVarInt();
			var list = new ArrayList<RecipeHolder<?>>();

			Object codec = SerializerCodec.streamCodecOf(recipeSerializer);
			for (int i = 0; i < count; i++) {
				ResourceKey<Recipe<?>> id = buf.readResourceKey(Registries.RECIPE);
				Recipe<?> recipe = (Recipe<?>) SerializerCodec.decode(codec, buf);
				list.add(new RecipeHolder<>(id, recipe));
			}

			return new Entry(recipeSerializer, list);
		}

		private void write(RegistryFriendlyByteBuf buf) {
			buf.writeIdentifier(BuiltInRegistries.RECIPE_SERIALIZER.getKey(this.serializer));

			buf.writeVarInt(this.recipes.size());

			Object codec = SerializerCodec.streamCodecOf(this.serializer);

			for (RecipeHolder<?> recipe : this.recipes) {
				buf.writeResourceKey(recipe.id());
				SerializerCodec.encode(codec, buf, recipe.value());
			}
		}

		/**
		 * Encodes a single entry into a fresh heap buffer and returns the buffer (the caller must
		 * {@code .release()} it). Used by the chunker to measure each entry's encoded size without
		 * having to replicate {@link #write}.
		 */
		public io.netty.buffer.ByteBuf encodeToBuffer(net.minecraft.server.MinecraftServer server) {
			io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
			RegistryFriendlyByteBuf wrapped = new RegistryFriendlyByteBuf(buf, server.registryAccess());
			try {
				CODEC.encode(wrapped, this);
			} catch (Throwable t) {
				buf.release();
				throw t;
			}
			return buf;
		}
	}

	/**
	 * Encode the whole payload to a fresh buffer using the given registry access. The caller must
	 * {@code .release()} the returned buffer.
	 */
	public io.netty.buffer.ByteBuf encodeToBuffer(net.minecraft.server.MinecraftServer server) {
		io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
		RegistryFriendlyByteBuf wrapped = new RegistryFriendlyByteBuf(buf, server.registryAccess());
		try {
			CODEC.encode(wrapped, this);
		} catch (Throwable t) {
			buf.release();
			throw t;
		}
		return buf;
	}
}