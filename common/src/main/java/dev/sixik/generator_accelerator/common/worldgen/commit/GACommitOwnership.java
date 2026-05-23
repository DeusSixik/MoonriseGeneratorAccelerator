package dev.sixik.generator_accelerator.common.worldgen.commit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Helper for chunk-owner commit routing and deterministic mailbox handoff.
 */
public final class GACommitOwnership {
    private GACommitOwnership() {
    }

    public static boolean isOwnedBy(GAChunkPosition ownerChunk, GACommitCommand<?> command) {
        if (ownerChunk == null) {
            throw new NullPointerException("ownerChunk");
        }
        if (command == null) {
            throw new NullPointerException("command");
        }
        return ownerChunk.equals(GAChunkPosition.fromBlock(command.position()));
    }

    public static <T> GACommitOwnershipSplit<T> split(
            GAChunkPosition ownerChunk,
            Collection<GACommitCommand<T>> commands
    ) {
        if (ownerChunk == null) {
            throw new NullPointerException("ownerChunk");
        }
        if (commands == null) {
            throw new NullPointerException("commands");
        }
        List<GACommitCommand<T>> owned = new ArrayList<>();
        Map<GAChunkPosition, List<GACommitCommand<T>>> forwarded = new TreeMap<>();
        for (GACommitCommand<T> command : commands) {
            if (command == null) {
                throw new NullPointerException("command");
            }
            GAChunkPosition targetChunk = GAChunkPosition.fromBlock(command.position());
            if (ownerChunk.equals(targetChunk)) {
                owned.add(command);
            } else {
                forwarded.computeIfAbsent(targetChunk, ignored -> new ArrayList<>()).add(command);
            }
        }
        owned.sort(GACommitCommand::compareTo);
        Map<GAChunkPosition, List<GACommitCommand<T>>> copied = new TreeMap<>();
        for (Map.Entry<GAChunkPosition, List<GACommitCommand<T>>> entry : forwarded.entrySet()) {
            List<GACommitCommand<T>> ordered = new ArrayList<>(entry.getValue());
            ordered.sort(GACommitCommand::compareTo);
            copied.put(entry.getKey(), List.copyOf(ordered));
        }
        return new GACommitOwnershipSplit<>(ownerChunk, owned, copied);
    }

    public static <T> GACommitBatch.GAResolvedCommitBatch<T> drainOwned(
            GAChunkPosition ownerChunk,
            Collection<GACommitCommand<T>> localCommands,
            GACommitMailbox<T> mailbox,
            GACommitCollisionPolicy policy
    ) {
        if (mailbox == null) {
            throw new NullPointerException("mailbox");
        }
        if (policy == null) {
            throw new NullPointerException("policy");
        }
        GACommitOwnershipSplit<T> split = split(ownerChunk, localCommands);
        if (!split.forwardedByTarget().isEmpty()) {
            throw new IllegalArgumentException("localCommands contain non-owned targets for " + ownerChunk);
        }
        List<GACommitCommand<T>> combined = new ArrayList<>(split.owned());
        combined.addAll(mailbox.drainCommands(ownerChunk));
        return GACommitBatch.of(combined).resolve(policy);
    }

    public record GACommitOwnershipSplit<T>(
            GAChunkPosition ownerChunk,
            List<GACommitCommand<T>> owned,
            Map<GAChunkPosition, List<GACommitCommand<T>>> forwardedByTarget
    ) {
        public GACommitOwnershipSplit {
            if (ownerChunk == null) {
                throw new NullPointerException("ownerChunk");
            }
            owned = owned == null ? List.of() : List.copyOf(owned);
            if (forwardedByTarget == null) {
                forwardedByTarget = Map.of();
            } else {
                Map<GAChunkPosition, List<GACommitCommand<T>>> copied = new TreeMap<>();
                for (Map.Entry<GAChunkPosition, List<GACommitCommand<T>>> entry : forwardedByTarget.entrySet()) {
                    if (entry.getKey() == null) {
                        throw new NullPointerException("forwarded target");
                    }
                    copied.put(entry.getKey(), entry.getValue() == null ? List.of() : List.copyOf(entry.getValue()));
                }
                forwardedByTarget = Collections.unmodifiableMap(copied);
            }
        }

        public int forwardedCount() {
            int count = 0;
            for (List<GACommitCommand<T>> commands : forwardedByTarget.values()) {
                count += commands.size();
            }
            return count;
        }
    }
}
