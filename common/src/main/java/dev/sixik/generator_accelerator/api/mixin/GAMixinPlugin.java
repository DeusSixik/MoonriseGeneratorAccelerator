package dev.sixik.generator_accelerator.api.mixin;

import ca.spottedleaf.yamlconfig.config.YamlConfig;
import dev.sixik.generator_accelerator.api.config.GAConfig;
import dev.sixik.generator_accelerator.api.config.GAConfigHolder;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Base {@link IMixinConfigPlugin} implementation used by GA mixin modules.
 *
 * <p>This class provides three main pieces of infrastructure:</p>
 *
 * <ul>
 *   <li>config-controlled mixin toggles via {@link #addMixinToConfig(String, Function)}</li>
 *   <li>runtime compat rules via {@link #create(String, GAMixinApplier.Param...)},
 *   {@link #createAll(String[], GAMixinApplier.Param...)}, and
 *   {@link #createAny(String[], GAMixinApplier.Param...)}</li>
 *   <li>generated annotation bootstrap metadata produced during the build</li>
 * </ul>
 *
 * <p>Typical usage:</p>
 *
 * <pre>{@code
 * @AutoMixinPlugin
 * public final class BasicGAMixinPlugin extends GAMixinPlugin {
 *     @Override
 *     public boolean isConfigEnable(GAConfig config) {
 *         return config.enableBasicPatchGroup;
 *     }
 * }
 * }</pre>
 *
 * <p>Most projects should prefer the annotation-based API on mixin classes. Manual
 * registration methods are still useful for advanced or legacy setups.</p>
 */
public abstract class GAMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("GeneratorAccelerator Mixin");
    private static final String GENERATED_BOOTSTRAP_CLASS = "dev.sixik.generator_accelerator.api.mixin.generated.GeneratedGAMixinBootstrap";
    private static final Set<String> BOOTSTRAPPED_PLUGIN_CLASSES = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> LOGGED_DISABLED_MIXINS = Collections.synchronizedSet(new HashSet<>());

    public static final List<GAMixinApplier> MixinAppliers = new ObjectArrayList<>();

    private static final Function<GAConfig, Boolean> DEFAULT_FUNC = (config) -> true;
    public static final Object2ObjectArrayMap<String, Function<GAConfig, Boolean>> MIXINS_CONFIG_PARAMS = new Object2ObjectArrayMap<>();
    public static final Object2ObjectArrayMap<String, Function<GAConfig, Boolean>> ACTIVE_MIXIN_PARAMS = new Object2ObjectArrayMap<>();

    /**
     * Tracks when a compat mixin should be considered active for cancellation purposes.
     */
    private void registerActiveMixins(GAMixinApplier.Param... params) {
        for (GAMixinApplier.Param param : params) {
            final String mixinClass = param.mixinClass();
            ACTIVE_MIXIN_PARAMS.putIfAbsent(mixinClass, (config) -> this.isConfigEnable(config) && isMixinConfigEnabled(mixinClass, config));
        }
    }

    /**
     * Registers a compat rule that depends on a single class path.
     *
     * <pre>{@code
     * create("com.example.SomeMod", new GAMixinApplier.Param(
     *         "dev.example.mixin.SomeCompatMixin",
     *         "com.example.foreign.mixin.TargetMixin"
     * ));
     * }</pre>
     *
     * @param modClass required class path; empty means always available
     * @param params owned GA mixins and optional foreign mixins to cancel
     */
    public final void create(String modClass, GAMixinApplier.Param... params) {
        registerActiveMixins(params);
        MixinAppliers.add(new GAMixinApplier(modClass, params));
    }

    /**
     * Registers a compat rule that requires all supplied class paths to be available.
     *
     * @param modClasses required class paths
     * @param params owned GA mixins and optional foreign mixins to cancel
     */
    public final void createAll(String[] modClasses, GAMixinApplier.Param... params) {
        registerActiveMixins(params);
        MixinAppliers.add(new GAMixinApplier(String.join(";", modClasses), GAMixinApplier.MatchMode.ALL, params));
    }

    /**
     * Registers a compat rule that becomes active when any supplied class path is available.
     *
     * @param modClasses alternative class paths
     * @param params owned GA mixins and optional foreign mixins to cancel
     */
    public final void createAny(String[] modClasses, GAMixinApplier.Param... params) {
        registerActiveMixins(params);
        MixinAppliers.add(new GAMixinApplier(String.join(";", modClasses), GAMixinApplier.MatchMode.ANY, params));
    }

    /**
     * Binds a single mixin class to a config predicate.
     *
     * <p>This is normally generated from {@code @MixinOnConfig}, but can also be registered
     * manually for custom logic.</p>
     *
     * @param mixin fully qualified GA mixin class name
     * @param func predicate that returns {@code true} when the mixin should be allowed to apply
     */
    public final void addMixinToConfig(String mixin, Function<GAConfig, Boolean> func) {
        MIXINS_CONFIG_PARAMS.put(mixin, func);
    }

    /**
     * Checks whether a mixin is enabled by its per-mixin config predicate.
     *
     * @param mixin fully qualified GA mixin class name
     * @param config resolved config instance
     * @return {@code true} if the mixin is enabled by config
     */
    public static boolean isMixinConfigEnabled(String mixin, GAConfig config) {
        return MIXINS_CONFIG_PARAMS.getOrDefault(mixin, DEFAULT_FUNC).apply(config);
    }

    /**
     * Checks whether a mixin is active for compat-cancellation purposes.
     *
     * <p>An active mixin must pass both the owning plugin gate and the per-mixin config gate.</p>
     *
     * @param mixin fully qualified GA mixin class name
     * @param config resolved config instance
     * @return {@code true} if the mixin is considered active
     */
    public static boolean isMixinActive(String mixin, GAConfig config) {
        return ACTIVE_MIXIN_PARAMS.getOrDefault(mixin, (cfg) -> isMixinConfigEnabled(mixin, cfg)).apply(config);
    }

    /**
     * Logs a disabled mixin once per category/detail combination.
     *
     * @param mixin fully qualified mixin class name
     * @param category short category such as {@code CONFIG}, {@code COMPAT}, or {@code MISSING_MOD}
     * @param detail human-readable explanation shown in the log
     */
    public static void logDisabledMixin(String mixin, String category, String detail) {
        final String key = mixin + "|" + category + "|" + detail;
        if (LOGGED_DISABLED_MIXINS.add(key)) {
            LOGGER.info("[{}] Disabled mixin {} ({})", category, mixin, detail);
        }
    }

    /**
     * Loads generated annotation metadata into this plugin instance once.
     *
     * <p>The generated bootstrap is created during the Gradle build and contains the code that
     * wires {@code @MixinOnConfig}, {@code @CompatMixin}, and related annotations.</p>
     */
    public final void bootstrapGeneratedMetadata() {
        if (!BOOTSTRAPPED_PLUGIN_CLASSES.add(this.getClass().getName())) {
            return;
        }

        try {
            Class.forName(GENERATED_BOOTSTRAP_CLASS)
                    .getMethod("register", GAMixinPlugin.class)
                    .invoke(null, this);
        } catch (ClassNotFoundException ignored) {
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new RuntimeException("Failed to load generated GA mixin bootstrap", exception);
        }
    }

    /**
     * Performs generated bootstrap loading and then calls the lightweight plugin hook.
     *
     * @param mixinPackage package name supplied by Mixin during plugin initialization
     */
    public final void initializePlugin(String mixinPackage) {
        bootstrapGeneratedMetadata();
        load(mixinPackage);
    }

    @Override
    public final void onLoad(String mixinPackage) {
        initializePlugin(mixinPackage);
        onGAMixinLoad(mixinPackage);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {

        final YamlConfig<GAConfig> configRaw = GAConfigHolder.getConfigRaw();
        if (
            // Disable full mixin package
            !isConfigEnable(configRaw.config) ||
            // Disable current mixin class
            !isMixinConfigEnabled(mixinClassName, configRaw.config)
        ) {
            logDisabledMixin(mixinClassName, "CONFIG", "disabled by config");
            return false;
        }

        for (final GAMixinApplier mixinApplier : MixinAppliers) {
            if (mixinApplier.hasMixin(mixinClassName) && !mixinApplier.isModLoaded()) {
                logDisabledMixin(mixinClassName, "MISSING_MOD", "required mod class is missing");
                return false;
            }
        }

        return true;
    }

    @Override
    public void acceptTargets(Set<String> set, Set<String> set1) { }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) { }

    @Override
    public void postApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) { }

    /**
     * Optional early hook called after generated metadata has been loaded but before
     * {@link #onGAMixinLoad(String)}.
     *
     * <p>This is the preferred place for lightweight manual compat registration when annotations
     * are not enough.</p>
     *
     * @param mixinPackage package name supplied by Mixin
     */
    protected void load(String mixinPackage) { }

    /**
     * Optional late hook called from {@link #onLoad(String)} after {@link #load(String)}.
     *
     * @param mixinPackage package name supplied by Mixin
     */
    protected void onGAMixinLoad(String mixinPackage) { }

    /**
     * Controls whether the entire plugin-owned mixin group is enabled.
     *
     * <p>If this returns {@code false}, every mixin controlled by the plugin is skipped before any
     * per-mixin config checks are evaluated.</p>
     *
     * @param config resolved GA config
     * @return {@code true} if the plugin-owned mixin group is allowed to apply
     */
    public abstract boolean isConfigEnable(GAConfig config);
}
