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
package jetbrains.exodus.query;


import jetbrains.exodus.entitystore.Entity;
import jetbrains.exodus.entitystore.EntityIterable;
import jetbrains.exodus.entitystore.PersistentStoreTransaction;
import jetbrains.exodus.entitystore.metadata.*;

public class LinkNotNull extends NodeBase {
    private final String name;

    public LinkNotNull(String name) {
        this.name = name;
    }

    @Override
    public Iterable<Entity> instantiate(String entityType, QueryEngine queryEngine, ModelMetaData metaData) {
        queryEngine.assertOperational();
        final PersistentStoreTransaction txn = queryEngine.getPersistentStore().getAndCheckCurrentTransaction();
        if (metaData != null) {
            EntityMetaData emd = metaData.getEntityMetaData(entityType);
            if (emd != null) {
                AssociationEndMetaData aemd = emd.getAssociationEndMetaData(name);
                if (aemd != null) {
                    AssociationMetaData amd = aemd.getAssociationMetaData();
                    if (amd.getType() != AssociationType.Directed) {
                        emd = aemd.getOppositeEntityMetaData();
                        aemd = amd.getOppositeEnd(aemd);
                        final String oppositeLinkName = aemd.getName();
                        EntityIterable result = txn.findWithLinks(entityType, name, emd.getType(), oppositeLinkName);
                        for (final String subType : emd.getAllSubTypes()) {
                            result = result.union(txn.findWithLinks(entityType, name, subType, oppositeLinkName));
                        }
                        // NB! findWithLinks(entityType, name, emd.getType(), oppositeLinkName) can return duplicates,
                        // so please do not remove .distinct()
                        return result.distinct();
                    }
                }
            }
        }
        return txn.findWithLinks(entityType, name);
    }

    @Override
    public NodeBase getClone() {
        return new LinkNotNull(name);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        checkWildcard(obj);
        if (!(obj instanceof LinkNotNull)) {
            return false;
        }
        LinkNotNull linkNotNull = (LinkNotNull) obj;
        return eq_eiki4g_a0e0c(name, linkNotNull.name);
    }

    @Override
    public String toString(String prefix) {
        return super.toString(prefix) + '(' + name + "!=null) ";
    }

    @Override
    public StringBuilder getHandle(StringBuilder sb) {
        return super.getHandle(sb).append('(').append(name).append(')');
    }

    @Override
    public String getSimpleName() {
        return "lnn";
    }

    private static boolean eq_eiki4g_a0e0c(Object a, Object b) {
        return a != null ? a.equals(b) : a == b;
    }
}
