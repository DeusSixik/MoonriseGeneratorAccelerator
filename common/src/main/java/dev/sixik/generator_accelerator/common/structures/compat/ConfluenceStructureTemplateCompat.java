package dev.sixik.generator_accelerator.common.structures.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public final class ConfluenceStructureTemplateCompat {
    private static final Bridge BRIDGE = createBridge();

    private ConfluenceStructureTemplateCompat() {
    }

    public static void load(CompoundTag blockInfoTag, StructureTemplate.StructureBlockInfo blockInfo) {
        BRIDGE.load(blockInfoTag, blockInfo);
    }

    public static void save(StructureTemplate.StructureBlockInfo blockInfo, CompoundTag blockInfoTag) {
        BRIDGE.save(blockInfo, blockInfoTag);
    }

    private static Bridge createBridge() {
        String[] bridgeClassNames = new String[]{
                "dev.sixik.generator_accelerator.neoforge.structures.compat.ConfluenceStructureTemplateCompatImpl",
                "dev.sixik.generator_accelerator.fabric.structures.compat.ConfluenceStructureTemplateCompatImpl"
        };

        ClassLoader classLoader = ConfluenceStructureTemplateCompat.class.getClassLoader();
        for (String className : bridgeClassNames) {
            try {
                Class<?> bridgeClass = Class.forName(className, false, classLoader);
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                MethodHandle load = lookup.findStatic(
                        bridgeClass,
                        "load",
                        MethodType.methodType(void.class, CompoundTag.class, StructureTemplate.StructureBlockInfo.class)
                );
                MethodHandle save = lookup.findStatic(
                        bridgeClass,
                        "save",
                        MethodType.methodType(void.class, StructureTemplate.StructureBlockInfo.class, CompoundTag.class)
                );
                return new MethodHandleBridge(load, save);
            } catch (ClassNotFoundException ignored) {
                // Platform bridge is optional; try the next platform.
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return NoOpBridge.INSTANCE;
            }
        }
        return NoOpBridge.INSTANCE;
    }

    private interface Bridge {
        void load(CompoundTag blockInfoTag, StructureTemplate.StructureBlockInfo blockInfo);

        void save(StructureTemplate.StructureBlockInfo blockInfo, CompoundTag blockInfoTag);
    }

    private enum NoOpBridge implements Bridge {
        INSTANCE;

        @Override
        public void load(CompoundTag blockInfoTag, StructureTemplate.StructureBlockInfo blockInfo) {
        }

        @Override
        public void save(StructureTemplate.StructureBlockInfo blockInfo, CompoundTag blockInfoTag) {
        }
    }

    private record MethodHandleBridge(MethodHandle load, MethodHandle save) implements Bridge {
        @Override
        public void load(CompoundTag blockInfoTag, StructureTemplate.StructureBlockInfo blockInfo) {
            try {
                this.load.invokeExact(blockInfoTag, blockInfo);
            } catch (Throwable throwable) {
                throw new IllegalStateException("Failed to load Confluence structure block metadata", throwable);
            }
        }

        @Override
        public void save(StructureTemplate.StructureBlockInfo blockInfo, CompoundTag blockInfoTag) {
            try {
                this.save.invokeExact(blockInfo, blockInfoTag);
            } catch (Throwable throwable) {
                throw new IllegalStateException("Failed to save Confluence structure block metadata", throwable);
            }
        }
    }
}
