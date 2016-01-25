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
package jetbrains.exodus.tree.btree;

import jetbrains.exodus.ExodusException;
import jetbrains.exodus.core.dataStructures.LongObjectCacheBase;
import jetbrains.exodus.log.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BTree extends BTreeBase {

    private final RandomAccessLoggable rootLoggable;
    private final BasePageImmutable root;

    public BTree(@NotNull final Log log, final long rootAddress, final boolean allowsDuplicates, final int structureId) {
        this(log, BTreeBalancePolicy.DEFAULT, rootAddress, allowsDuplicates, structureId);
    }

    public BTree(@NotNull final Log log,
                 @NotNull final BTreeBalancePolicy policy,
                 final long rootAddress,
                 final boolean allowsDuplicates,
                 final int structureId) {
        super(log, policy, allowsDuplicates, structureId);
        if (rootAddress == Loggable.NULL_ADDRESS) {
            throw new IllegalArgumentException("Can't instantiate not empty tree with null root address.");
        }
        rootLoggable = getLoggable(rootAddress);
        final byte type = rootLoggable.getType();
        // load size, but check if it exists
        if (type != BOTTOM_ROOT && type != INTERNAL_ROOT) {
            throw new ExodusException("Unexpected root page type: " + type);
        }
        final ByteIterableWithAddress data = rootLoggable.getData();
        final ByteIteratorWithAddress it = data.iterator();
        size = CompressedUnsignedLongByteIterable.getLong(it);
        root = loadPage(type, data.clone((int) (it.getAddress() - data.getDataAddress())));
    }

    @Override
    public long getRootAddress() {
        return rootLoggable.getAddress();
    }

    @Override
    @NotNull
    public BTreeMutable getMutableCopy() {
        final BTreeMutable result = new BTreeMutable(this);
        result.addExpiredLoggable(rootLoggable);
        return result;
    }

    @Override
    public void setTreeNodesCache(@Nullable final LongObjectCacheBase cache) {
        if (size > 0) {
            root.setTreeNodesCache(cache);
        }
    }

    @Override
    @NotNull
    protected BasePage getRoot() {
        return root;
    }
}
