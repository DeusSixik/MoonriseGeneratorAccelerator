import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.chunk.status.*;

public class DumpChunkDeps {
  public static void main(String[] args) {
    SharedConstants.tryDetectVersion();
    Bootstrap.bootStrap();
    for (ChunkStatus status : ChunkStatus.getStatusList()) {
      ChunkStep gen = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
      ChunkDependencies deps = gen.directDependencies();
      System.out.println("GEN " + status + " radius=" + deps.getRadius());
      for (int d = 0; d <= deps.getRadius(); d++) {
        System.out.println("  d=" + d + " -> " + deps.get(d));
      }
      ChunkStep load = ChunkPyramid.LOADING_PYRAMID.getStepTo(status);
      ChunkDependencies ldeps = load.directDependencies();
      System.out.println("LOAD " + status + " radius=" + ldeps.getRadius());
      for (int d = 0; d <= ldeps.getRadius(); d++) {
        System.out.println("  d=" + d + " -> " + ldeps.get(d));
      }
    }
  }
}
