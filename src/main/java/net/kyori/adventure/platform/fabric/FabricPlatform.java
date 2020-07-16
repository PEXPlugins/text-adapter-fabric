/*
 * This file is part of adventure, licensed under the MIT License.
 *
 * Copyright (c) 2020 KyoriPowered
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.kyori.adventure.platform.fabric;

import ca.stellardrift.colonel.api.ServerArgumentType;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.platform.AudienceProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.renderer.ComponentRenderer;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainComponentSerializer;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.command.arguments.ArgumentTypes;
import net.minecraft.command.arguments.IdentifierArgumentType;
import net.minecraft.command.arguments.TextArgumentType;
import net.minecraft.command.arguments.serialize.ConstantArgumentSerializer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.PolyNull;

import static java.util.Objects.requireNonNull;

/**
 * The entry point for accessing Adventure.
 */
public final class FabricPlatform implements AudienceProvider {
  private static final PlainComponentSerializer PLAIN;
  private static final MinecraftTextSerializer TEXT_NON_WRAPPING = new MinecraftTextSerializer();
  public static final GsonComponentSerializer GSON = GsonComponentSerializer.builder().legacyHoverEventSerializer(NBTLegacyHoverEventSerializer.INSTANCE).build();

  static {
    final Function<KeybindComponent, String> keybindNamer;

    if(FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
      keybindNamer = keybind -> KeyBinding.getLocalizedName(keybind.keybind()).get().asString();
    } else {
      keybindNamer = KeybindComponent::keybind;
    }
    PLAIN = new PlainComponentSerializer(keybindNamer, trans -> FabricPlatform.adapt(trans).asString());
  }

  private final MinecraftServer server;
  private final ComponentRenderer<Locale> renderer;

  public static @NonNull FabricPlatform of(final @NonNull MinecraftServer server) {
    return new FabricPlatform(requireNonNull(server, "server"), null);
  }

  public static @NonNull FabricPlatform of(final @NonNull MinecraftServer server, final @NonNull ComponentRenderer<Locale> renderer) {
    return new FabricPlatform(requireNonNull(server, "server"), requireNonNull(renderer, "renderer"));
  }

  private FabricPlatform(final MinecraftServer server, final ComponentRenderer<Locale> renderer) {
    this.server = server;
    this.renderer = renderer;
  }

  /**
   * Return a {@link PlainComponentSerializer} instance that can resolve key bindings and translations using the game's data
   *
   * @return the plain serializer instance
   */
  public static PlainComponentSerializer plainSerializer() {
    return PLAIN;
  }

  /**
   * Return a TextSerializer instance that will do deep conversions between Adventure {@link Component Components} and Minecraft {@link Text} objects.
   * <p>
   * This serializer will never wrap text, and can provide {@link net.minecraft.text.MutableText} instances suitable for passing around the game.
   *
   * @return a serializer instance
   */
  public static MinecraftTextSerializer nonWrappingSerializer() {
    return TEXT_NON_WRAPPING;
  }

  public static Text adapt(final Component component) {
    return new ComponentText(component);
  }

  public static Component adapt(final Text text) {
    if(text instanceof ComponentText) {
      return ((ComponentText) text).wrapped();
    }
    return nonWrappingSerializer().deserialize(text);
  }

  public static Text update(final Text input, final UnaryOperator<Component> modifier) {
    final Component modified;
    if(input instanceof ComponentText) {
      modified = requireNonNull(modifier).apply(((ComponentText) input).wrapped());
    } else {
      final Component original = nonWrappingSerializer().deserialize(input);
      modified = modifier.apply(original);
    }
    return new ComponentText(modified);
  }

  private static Identifier id(final @NonNull String value) {
    return new Identifier("adventure", value);
  }

  /**
   * Internal mod initializer for registrations.
   *
   * <p>Should only be called by Loader</p>
   */
  public static void init() {
    // Register custom argument types
    if(FabricLoader.getInstance().isModLoaded("colonel")) { // we can do server-only arg types
      ServerArgumentType.<ComponentArgumentType>builder(id("component"))
        .type(ComponentArgumentType.class)
        .serializer(new ComponentArgumentType.Serializer())
        .fallbackProvider(arg -> TextArgumentType.text())
        .fallbackSuggestions(null) // client text parsing is fine
        .register();
      ServerArgumentType.<KeyArgumentType>builder(id("key"))
        .type(KeyArgumentType.class)
        .serializer(new ConstantArgumentSerializer<>(KeyArgumentType::key))
        .fallbackProvider(arg -> IdentifierArgumentType.identifier())
        .fallbackSuggestions(null)
        .register();
    } else {
      ArgumentTypes.register("adventure:component", ComponentArgumentType.class, new ComponentArgumentType.Serializer());
      ArgumentTypes.register("adventure:key", KeyArgumentType.class, new ConstantArgumentSerializer<>(KeyArgumentType::key));
    }
  }

  /**
   * Convert a MC {@link Identifier} instance to a text Key
   *
   * @param ident The Identifier to convert
   * @return The equivalent data as a Key
   */
  public static @PolyNull Key adapt(@PolyNull final Identifier ident) {
    if(ident == null) {
      return null;
    }
    return Key.of(ident.getNamespace(), ident.getPath());
  }

  /**
   * Convert a Kyori {@link Key} instance to a MC Identifier
   *
   * @param key The Key to convert
   * @return The equivalent data as an Identifier
   */
  public static @PolyNull Identifier adapt(@PolyNull final Key key) {
    if(key == null) {
      return null;
    }
    return new Identifier(key.namespace(), key.value());
  }

  @Override
  public @NonNull Audience all() {
    return Audience.of(this.console(), this.players());
  }

  @Override
  public @NonNull Audience console() {
    return (Audience) this.server;
  }

  @Override
  public @NonNull Audience players() {
    return Audience.of(this.audiences(this.server.getPlayerManager().getPlayerList()));
  }

  @Override
  public @NonNull Audience player(final @NonNull UUID playerId) {
    final /* @Nullable */ ServerPlayerEntity player = this.server.getPlayerManager().getPlayer(playerId);
    return player != null ? (Audience) player : Audience.empty();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private Iterable<Audience> audiences(final Iterable<? extends ServerPlayerEntity> players) {
    return (Iterable) players;
  }

  @Override
  public @NonNull Audience permission(final @NonNull String permission) {
    return Audience.of(); // TODO: permissions api
  }

  public AdventureCommandSource audience(final @NonNull ServerCommandSource source) {
    if(!(source instanceof AdventureCommandSource)) {
      throw new IllegalArgumentException("The AdventureCommandSource mixin failed!");
    }

    return (AdventureCommandSource) source;
  }

  public Audience audience(final @NonNull CommandOutput output) {
    return CommandOutputAudience.of(output);
  }

  public Audience audience(final @NonNull Iterable<ServerPlayerEntity> players) {
    return Audience.of(this.audiences(players));
  }

  @Override
  public @NonNull Audience world(final @NonNull Key worldId) {
    final /* @Nullable */ ServerWorld world = this.server.getWorld(RegistryKey.of(Registry.DIMENSION, adapt(requireNonNull(worldId, "worldId"))));
    if(world != null) {
      return this.audience(world.getPlayers());
    }
    return Audience.of();
  }

  @Override
  public @NonNull Audience server(final @NonNull String serverName) {
    return this.all();
  }

  @Override
  public @NonNull ComponentRenderer<Locale> localeRenderer() {
    return this.renderer;
  }

  @Override
  public @NonNull GsonComponentSerializer gsonSerializer() {
    return GSON;
  }

  @Override
  public void close() {
  }
}
