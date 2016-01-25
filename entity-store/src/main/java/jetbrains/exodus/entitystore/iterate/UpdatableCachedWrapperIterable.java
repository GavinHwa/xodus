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

import jetbrains.exodus.entitystore.PersistentEntityStoreImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class UpdatableCachedWrapperIterable extends CachedWrapperIterable {

    protected UpdatableCachedWrapperIterable(@Nullable final PersistentEntityStoreImpl store, @NotNull final EntityIterableBase source) {
        super(store, source);
    }

    @Override
    protected void orderById() {
        throw new UnsupportedOperationException();
    }

    public abstract UpdatableCachedWrapperIterable beginUpdate();

    public abstract boolean isMutated();

    public abstract void endUpdate();
}
