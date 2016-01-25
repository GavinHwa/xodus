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

import jetbrains.exodus.ByteIterable;
import jetbrains.exodus.core.dataStructures.IntArrayList;
import jetbrains.exodus.core.dataStructures.hash.IntHashMap;
import jetbrains.exodus.entitystore.*;
import jetbrains.exodus.entitystore.tables.LinkValue;
import jetbrains.exodus.entitystore.tables.PropertyKey;
import jetbrains.exodus.env.Cursor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class EntityFromLinkSetIterable extends EntityLinksIterableBase {

    private final IntHashMap<String> linkNames;

    static {
        registerType(getType(), new EntityIterableInstantiator() {
            @Override
            public EntityIterableBase instantiate(PersistentStoreTransaction txn, PersistentEntityStoreImpl store, Object[] parameters) {
                Integer linkCount = Integer.valueOf((String) parameters[2]);
                IntHashMap<String> linkNames = new IntHashMap<>(linkCount);
                for (int i = 0; i < linkCount; i++) {
                    linkNames.put(Integer.valueOf((String) parameters[4 + i]), null);
                }
                PersistentEntityId entityId = new PersistentEntityId(Integer.valueOf((String) parameters[0]),
                        Long.valueOf((String) parameters[1]));
                List<String> entityLinkNames = store.getLinkNames(txn, new PersistentEntity(store, entityId));
                for (String linkName : entityLinkNames) {
                    int linkId = store.getLinkId(txn, linkName, false);
                    if (linkNames.containsKey(linkId)) {
                        linkNames.put(linkId, linkName);
                    }
                }
                return new EntityFromLinkSetIterable(txn, store, entityId, linkNames);
            }
        });
    }

    @SuppressWarnings({"AssignmentToCollectionOrArrayFieldFromParameter"})
    public EntityFromLinkSetIterable(@NotNull final PersistentStoreTransaction txn, @NotNull PersistentEntityStoreImpl store,
                                     @NotNull EntityId entityId, @NotNull final IntHashMap<String> linkNames) {
        super(store, entityId);
        this.linkNames = linkNames;
        if (!txn.isCurrent()) {
            txnGetter = txn;
        }
    }

    public static EntityIterableType getType() {
        return EntityIterableType.ENTITY_FROM_LINKS_SET;
    }

    @Override
    public boolean isSortedById() {
        return false;
    }

    @Override
    @NotNull
    public EntityIteratorBase getIteratorImpl(@NotNull final PersistentStoreTransaction txn) {
        return new LinksIterator(openCursor(txn), getFirstKey());
    }

    @Override
    @NotNull
    protected EntityIterableHandle getHandleImpl() {
        return new ConstantEntityIterableHandle(getStore(), EntityFromLinkSetIterable.getType()) {
            @Override
            public boolean hasLinkId(int id) {
                return linkNames.containsKey(id);
            }

            @Override
            public int[] getLinkIds() {
                final int[] result = new int[linkNames.size()];
                int i = 0;
                for (final int id : linkNames.keySet()) {
                    result[i++] = id;
                }
                if (i == 0) {
                    return null;
                }
                return result;
            }

            @Override
            public void toString(@NotNull final StringBuilder builder) {
                super.toString(builder);
                ((PersistentEntityId) entityId).toString(builder);
                builder.append('-');
                builder.append(linkNames.size());
                for (final int id : linkNames.keySet()) {
                    builder.append('-');
                    builder.append(id);
                }
            }

            @Override
            public void hashCode(@NotNull final EntityIterableHandleHash hash) {
                ((PersistentEntityId) entityId).toHash(hash);
                hash.applyDelimiter();
                hash.apply(linkNames.size());
                for (final int id : linkNames.keySet()) {
                    hash.applyDelimiter();
                    hash.apply(id);
                }
            }

            @Override
            public boolean isMatchedLinkAdded(@NotNull final EntityId source,
                                              @NotNull final EntityId target,
                                              final int linkId) {
                return entityId.equals(source);
            }

            @Override
            public boolean isMatchedLinkDeleted(@NotNull final EntityId source,
                                                @NotNull final EntityId target,
                                                final int linkId) {
                return entityId.equals(source);
            }
        };
    }

    @Override
    protected CachedWrapperIterable createCachedWrapper(@NotNull final PersistentStoreTransaction txn) {
        final IntArrayList propIds = new IntArrayList();
        return new EntityIdArrayIterableWrapper(txn, EntityFromLinkSetIterable.this.getStore(), EntityFromLinkSetIterable.this) {
            @Override
            protected EntityId extractNextId(final EntityIterator it) {
                final EntityId result = super.extractNextId(it);
                propIds.add(((EntityFromLinkSetIteratorBase) it).currentPropId());
                return result;
            }

            @NotNull
            @Override
            public EntityIteratorBase getIteratorImpl(@NotNull final PersistentStoreTransaction txn) {
                return new EntityIdArrayWithSetIteratorWrapper(this, super.getIteratorImpl(txn), propIds, linkNames);
            }
        };
    }

    private Cursor openCursor(@NotNull final PersistentStoreTransaction txn) {
        return getStore().getLinksFirstIndexCursor(txn, entityId.getTypeId());
    }

    private ByteIterable getFirstKey() {
        return PropertyKey.propertyKeyToEntry(new PropertyKey(entityId.getLocalId(), 0));
    }

    private class LinksIterator extends EntityFromLinkSetIteratorBase {
        private boolean hasNext;
        private boolean hasNextValid;
        private int currentPropId;

        private LinksIterator(@NotNull final Cursor index,
                              @NotNull final ByteIterable key) {
            super(EntityFromLinkSetIterable.this);
            setCursor(index);
            hasNextValid = index.getSearchKeyRange(key) == null;
        }

        @Override
        public int currentPropId() {
            return currentPropId;
        }

        @Override
        protected String getLinkName(int linkId) {
            return linkNames.get(linkId);
        }

        @Override
        protected boolean hasNextImpl() {
            if (!hasNextValid) {
                hasNext = hasNextProp();
                hasNextValid = true;
            }
            return hasNext;
        }

        @Override
        protected EntityId nextIdImpl() {
            if (hasNextImpl()) {
                explain(getType());
                final LinkValue value = LinkValue.entryToLinkValue(getCursor().getValue());
                final EntityId result = value.getEntityId();
                if (getCursor().getNext()) {
                    hasNextValid = false;
                } else {
                    hasNext = false;
                }
                return result;
            }
            return null;
        }

        private boolean hasNextProp() {
            Cursor index = getCursor();
            do {
                final PropertyKey prop = PropertyKey.entryToPropertyKey(index.getKey());
                if (prop.getEntityLocalId() != entityId.getLocalId()) {
                    return false;
                }
                final int propId = prop.getPropertyId();
                if (linkNames.containsKey(propId)) {
                    currentPropId = propId;
                    return true;
                }
            } while (index.getNext());
            return false;
        }
    }
}
