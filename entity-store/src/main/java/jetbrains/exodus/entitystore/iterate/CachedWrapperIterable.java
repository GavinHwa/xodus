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

import jetbrains.exodus.entitystore.EntityIterableHandle;
import jetbrains.exodus.entitystore.EntityIterator;
import jetbrains.exodus.entitystore.PersistentEntityStoreImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class CachedWrapperIterable extends EntityIterableBase {

    @NotNull
    private final EntityIterableHandle sourceHandle;

    protected CachedWrapperIterable(@Nullable final PersistentEntityStoreImpl store,
                                    @NotNull final EntityIterableBase source) {
        super(store);
        sourceHandle = source.getHandle();
        txnGetter = source.txnGetter;
    }

    @Override
    public EntityIterator iterator() {
        return getIteratorImpl();
    }

    @Override
    public long count() {
        return countImpl(getTransaction());
    }

    @Override
    public long size() {
        return countImpl(getTransaction());
    }

    @Override
    @NotNull
    protected EntityIterableHandle getHandleImpl() {
        return sourceHandle;
    }

    @Override
    public boolean canBeCached() {
        return false;
    }

    @Override
    public boolean nonCachedHasFastCount() {
        return true;
    }

    @Override
    public boolean isCachedWrapper() {
        return true;
    }

    protected abstract void orderById();
}
