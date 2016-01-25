/**
 * Copyright 2010 - 2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.entitystore;

import jetbrains.exodus.entitystore.iterate.EntityIterableHandleBase;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

public class PersistentEntityId implements EntityId {

    @NotNull
    public static final PersistentEntityId EMPTY_ID = new PersistentEntityId(0, 0);

    public static final int ACTUAL_VERSION = -1;
    private static final Pattern ID_SPLIT_PATTERN = Pattern.compile("-");

    private final int entityTypeId;
    private final long entityLocalId;
    private final int version;

    /**
     * Generic entity id constructor.
     *
     * @param entityTypeId  entity type id.
     * @param entityLocalId local entity id within entity type.
     * @param version       entity version.
     */
    public PersistentEntityId(final int entityTypeId, final long entityLocalId, final int version) {
        this.entityTypeId = entityTypeId;
        this.entityLocalId = entityLocalId;
        this.version = version;
    }

    /**
     * Generic constructor for id of the last (up-to-date) version of entity.
     *
     * @param entityTypeId  entity type id.
     * @param entityLocalId local entity id within entity type.
     */
    public PersistentEntityId(final int entityTypeId, final long entityLocalId) {
        this(entityTypeId, entityLocalId, ACTUAL_VERSION);
    }

    /**
     * Constructs id for another version of specified id.
     *
     * @param id      source entity id.
     * @param version entity version.
     */
    public PersistentEntityId(@NotNull final EntityId id, final int version) {
        this(id.getTypeId(), id.getLocalId(), version);
    }

    /**
     * Constructs id for last (up-to-date) version of specified id.
     *
     * @param id source entity id.
     */
    public PersistentEntityId(@NotNull final EntityId id) {
        this(id.getTypeId(), id.getLocalId());
    }

    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof PersistentEntityId)) {
            return false;
        }
        final PersistentEntityId that = (PersistentEntityId) obj;
        return entityLocalId == that.entityLocalId &&
                entityTypeId == that.entityTypeId && version == that.version;
    }

    public int hashCode() {
        return (int) (entityTypeId + version + 1 << 20 ^ entityLocalId);
    }

    @Override
    public int getTypeId() {
        return entityTypeId;
    }

    @Override
    public long getLocalId() {
        return entityLocalId;
    }

    @NotNull
    public String toString() {
        final StringBuilder builder = new StringBuilder(10);
        toString(builder);
        return builder.toString();
    }

    public void toString(@NotNull final StringBuilder builder) {
        builder.append(entityTypeId);
        builder.append('-');
        builder.append(entityLocalId);
        if (version != ACTUAL_VERSION) {
            builder.append('-');
            builder.append(version);
        }
    }

    public void toHash(@NotNull final EntityIterableHandleBase.EntityIterableHandleHash hash) {
        hash.apply(entityTypeId);
        hash.applyDelimiter();
        hash.apply(entityLocalId);
        if (version != ACTUAL_VERSION) {
            hash.applyDelimiter();
            hash.apply(version);
        }
    }

    public static EntityId toEntityId(@NotNull final CharSequence representation) {
        final String[] idParts = ID_SPLIT_PATTERN.split(representation);
        final int partsCount = idParts.length;
        if (partsCount < 2 || partsCount > 3) {
            throw new IllegalArgumentException("Invalid structure of entity id");
        }
        final int entityTypeId = Integer.parseInt(idParts[0]);
        final long entityLocalId = Long.parseLong(idParts[1]);
        return partsCount == 2 ?
                new PersistentEntityId(entityTypeId, entityLocalId) :
                new PersistentEntityId(entityTypeId, entityLocalId, Integer.parseInt(idParts[2]));
    }

    public static EntityId toEntityId(@NotNull final String representation, @NotNull final PersistentEntityStoreImpl store) {
        EntityId result = store.getCachedEntityId(representation);
        if (result != null) {
            return result;
        }
        result = toEntityId(representation);
        store.cacheEntityId(representation, result);
        return result;
    }

    @Override
    public int compareTo(@NotNull final EntityId o) {
        if (!(o instanceof PersistentEntityId)) {
            throw new RuntimeException("Illegal type of entity id, PersistentEntityId is expected.");
        }
        PersistentEntityId bid = (PersistentEntityId) o;
        final long rightLocalId = o.getLocalId();
        final int rightType = o.getTypeId();
        if (entityTypeId < rightType) {
            return -3;
        }
        if (entityTypeId > rightType) {
            return 3;
        }
        if (entityLocalId < rightLocalId) {
            return -2;
        }
        if (entityLocalId > rightLocalId) {
            return 2;
        }
        final int rightVersion = bid.version;
        if (version < rightVersion) {
            return -1;
        }
        if (version > rightVersion) {
            return 1;
        }
        return 0;
    }

    public int getVersion() {
        return version;
    }
}
