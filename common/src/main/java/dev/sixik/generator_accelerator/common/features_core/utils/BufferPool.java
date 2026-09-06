package dev.sixik.generator_accelerator.common.features_core.utils;

import dev.sixik.generator_accelerator.utils.collections.BlockPosFlatList;
import net.minecraft.core.BlockPos;

public final class BufferPool {
    private Frame[] frames = new Frame[] { new Frame(), new Frame() };
    private int depth = 0;

    public Frame push() {
        if (this.depth >= this.frames.length) {
            // Если вложенность больше 2, расширяем пул (крайне редкий случай)
            Frame[] newFrames = new Frame[this.frames.length * 2];
            System.arraycopy(this.frames, 0, newFrames, 0, this.frames.length);
            for (int i = this.frames.length; i < newFrames.length; i++) {
                newFrames[i] = new Frame();
            }
            this.frames = newFrames;
        }
        return this.frames[this.depth++];
    }

    public void pop() {
        this.depth--;
        Frame f = this.frames[this.depth];
        if (f.curList.size() > 256) f.curList.clear();
        if (f.nextList.size() > 256) f.nextList.clear();
    }

    public static final class Frame {
        public final BlockPosFlatList curList = new BlockPosFlatList(16);
        public final BlockPosFlatList nextList = new BlockPosFlatList(16);
        public final BlockPos.MutableBlockPos tempPos = new BlockPos.MutableBlockPos();
    }
}
