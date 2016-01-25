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
package jetbrains.exodus.env;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface Transaction {

    boolean isIdempotent();

    void abort();

    boolean commit();

    boolean flush();

    void revert();

    Transaction getSnapshot();

    @NotNull
    Environment getEnvironment();

    void setCommitHook(@Nullable Runnable hook);

    /**
     * Time when the transaction acquired its database snapshot, i.e. time when it was created, reverted or successfully flushed.
     *
     * @return the difference, measured in milliseconds, between the current time and midnight, January 1, 1970 UTC.
     */
    long getCreated();

    /**
     * @return the value of Log.getHighAddress() that was actual when the transaction was created.
     */
    long getHighAddress();
}
