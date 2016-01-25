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
package jetbrains.exodus.entitystore.iterate;

import jetbrains.exodus.core.dataStructures.persistent.PersistentLong23TreeMap;
import jetbrains.exodus.entitystore.*;
import jetbrains.exodus.entitystore.util.EntityIdSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;

@SuppressWarnings("unchecked")
public class EntitiesOfTypeIterableWrapper extends UpdatableCachedWrapperIterable {

    private static final PersistentLong23TreeMap EMPTY_IDS = new PersistentLong23TreeMap();

    private final int entityTypeId;
    @NotNull
    private final PersistentLong23TreeMap localIds;
    @Nullable
    private PersistentLong23TreeMap.MutableMap mutableLocalIds;
    @Nullable
    private EntityIdSet idSet;

    public EntitiesOfTypeIterableWrapper(@NotNull final PersistentStoreTransaction txn,
                                         @Nullable final PersistentEntityStoreImpl store,
                                         @NotNull final EntitiesOfTypeIterable source) {
        super(store, source);
        entityTypeId = source.getEntityTypeId();
        final EntityIteratorBase it = source.getIteratorImpl(txn);
        try {
            if (!it.hasNext()) {
                localIds = EMPTY_IDS;
            } else {
                localIds = new PersistentLong23TreeMap();
                final PersistentLong23TreeMap.MutableMap mutableLocalIds = localIds.beginWrite();
                do {
                    final EntityId entityId = it.nextId();
                    if (entityId == null) {
                        throw new NullPointerException("EntitiesOfTypeIterator.nextId() returned null!");
                    }
                    mutableLocalIds.put(entityId.getLocalId(), EMPTY_IDS);
                } while (it.hasNext());
                mutableLocalIds.endWrite();
            }
        } finally {
            it.disposeIfShouldBe();
        }
        mutableLocalIds = null;
        idSet = null;
    }

    // constructor for mutating
    private EntitiesOfTypeIterableWrapper(@NotNull final EntitiesOfTypeIterableWrapper source) {
        super(source.getStore(), source);
        entityTypeId = source.entityTypeId;
        localIds = source.localIds.getClone();
        mutableLocalIds = localIds.beginWrite();
    }

    @NotNull
    @Override
    public EntityIterator getIteratorImpl(@NotNull PersistentStoreTransaction txn) {
        if (localIds == EMPTY_IDS && mutableLocalIds == null) {
            return EntityIteratorBase.EMPTY;
        }
        return new NonDisposableEntityIterator(this) {

            private final Iterator<PersistentLong23TreeMap.Entry> it = getCurrentMap().iterator();

            @Override
            protected boolean hasNextImpl() {
                return it.hasNext();
            }

            @Nullable
            @Override
            protected EntityId nextIdImpl() {
                return new PersistentEntityId(entityTypeId, it.next().getKey());
            }
        };
    }

    @Override
    public long size() {
        return getCurrentMap().size();
    }

    @Override
    public EntityIdSet toSet(@NotNull final PersistentStoreTransaction txn) {
        if (idSet == null) {
            final EntityIdSet result = new EntityIdSet();
            final EntityIterator it = getIteratorImpl(txn);
            while (it.hasNext()) {
                result.add(it.nextId());
            }
            idSet = result;
        }
        return idSet;
    }

    @Override
    protected long countImpl(@NotNull final PersistentStoreTransaction txn) {
        return size();
    }

    @Override
    public EntitiesOfTypeIterableWrapper beginUpdate() {
        return new EntitiesOfTypeIterableWrapper(this);
    }

    @Override
    public boolean isMutated() {
        return mutableLocalIds != null;
    }

    @Override
    public void endUpdate() {
        checkMutableIds().endWrite();
        mutableLocalIds = null;
    }

    public final void addEntity(final EntityId id) {
        checkEntityType(id);
        checkMutableIds().put(id.getLocalId(), EMPTY_IDS);
    }

    public final void removeEntity(final EntityId id) {
        checkEntityType(id);
        checkMutableIds().remove(id.getLocalId());
    }

    private PersistentLong23TreeMap.MutableMap getCurrentMap() {
        return mutableLocalIds == null ? localIds.beginWrite() : mutableLocalIds;
    }

    private PersistentLong23TreeMap.MutableMap checkMutableIds() {
        PersistentLong23TreeMap.MutableMap mutableLocalIds = this.mutableLocalIds;
        if (mutableLocalIds == null) {
            throw new IllegalStateException("EntitiesOfTypeIterableWrapper was not mutated");
        }
        return mutableLocalIds;
    }

    private void checkEntityType(EntityId id) {
        if (id.getTypeId() != entityTypeId) {
            throw new IllegalStateException("Unexpected entity type id: " + id.getTypeId());
        }
    }
}
