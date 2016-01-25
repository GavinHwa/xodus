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
package jetbrains.exodus.gc;

import jetbrains.exodus.ExodusException;
import jetbrains.exodus.core.dataStructures.hash.IntHashMap;
import jetbrains.exodus.core.dataStructures.hash.LongHashSet;
import jetbrains.exodus.core.dataStructures.hash.LongSet;
import jetbrains.exodus.core.execution.Job;
import jetbrains.exodus.core.execution.JobProcessorAdapter;
import jetbrains.exodus.env.EnvironmentConfig;
import jetbrains.exodus.env.EnvironmentImpl;
import jetbrains.exodus.env.StoreImpl;
import jetbrains.exodus.env.TransactionImpl;
import jetbrains.exodus.io.RemoveBlockType;
import jetbrains.exodus.log.*;
import jetbrains.exodus.tree.IExpirationChecker;
import jetbrains.exodus.util.DeferredIO;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;

@SuppressWarnings({"ThisEscapedInObjectConstruction"})
public final class GarbageCollector {

    public static final String UTILIZATION_PROFILE_STORE_NAME = "exodus.gc.up";

    private static final Logger logger = LoggerFactory.getLogger(GarbageCollector.class);

    @NotNull
    private final EnvironmentImpl env;
    @NotNull
    private final EnvironmentConfig ec;
    @NotNull
    private final UtilizationProfile utilizationProfile;
    @NotNull
    private final LongSet pendingFilesToDelete;
    @NotNull
    private final ConcurrentLinkedQueue<Long> deletionQueue;
    @NotNull
    private final BackgroundCleaner cleaner;
    private volatile int newFiles; // number of new files appeared after last cleaning job
    @NotNull
    private final IExpirationChecker expirationChecker;
    @NotNull
    private final IntHashMap<StoreImpl> openStoresCache;

    public GarbageCollector(@NotNull final EnvironmentImpl env) {
        this.env = env;
        ec = env.getEnvironmentConfig();
        pendingFilesToDelete = new LongHashSet();
        deletionQueue = new ConcurrentLinkedQueue<>();
        utilizationProfile = new UtilizationProfile(env, this);
        cleaner = new BackgroundCleaner(this);
        newFiles = ec.getGcFilesInterval() + 1;
        if (!ec.getGcUseExpirationChecker()) {
            expirationChecker = IExpirationChecker.NONE;
        } else {
            expirationChecker = new IExpirationChecker() {

                @Override
                public boolean expired(@NotNull final Loggable loggable) {
                    return utilizationProfile.isExpired(loggable);
                }

                @Override
                public boolean expired(long startAddress, int length) {
                    return utilizationProfile.isExpired(startAddress, length);
                }
            };
        }
        openStoresCache = new IntHashMap<>();
        env.getLog().addNewFileListener(new NewFileListener() {
            @Override
            public void fileCreated(long fileAddress) {
                ++newFiles;
                if (!cleaner.isCleaning() && newFiles > ec.getGcFilesInterval() && isTooMuchFreeSpace()) {
                    wake();
                }
            }
        });
    }

    public void setCleanerJobProcessor(@NotNull final JobProcessorAdapter processor) {
        cleaner.setJobProcessor(processor);
    }

    public void wake() {
        if (ec.isGcEnabled()) {
            env.executeTransactionSafeTask(new Runnable() {
                @Override
                public void run() {
                    cleaner.queueCleaningJob();
                }
            });
        }
    }

    public void wakeAt(final long millis) {
        if (ec.isGcEnabled()) {
            cleaner.queueCleaningJobAt(millis);
        }
    }

    public int getMaximumFreeSpacePercent() {
        return 100 - ec.getGcMinUtilization();
    }

    public void fetchExpiredLoggables(@NotNull final Iterable<Loggable> loggables) {
        utilizationProfile.fetchExpiredLoggables(loggables);
    }

    public long getFileFreeBytes(final long fileAddress) {
        return utilizationProfile.getFileFreeBytes(fileAddress);
    }

    public boolean isExpired(final long startAddress, final int length) {
        return utilizationProfile.isExpired(startAddress, length);
    }

    public void suspend() {
        cleaner.suspend();
    }

    public void resume() {
        cleaner.resume();
    }

    public void finish() {
        cleaner.finish();
    }

    @NotNull
    public UtilizationProfile getUtilizationProfile() {
        return utilizationProfile;
    }

    public void saveUtilizationProfile() {
        // this condition is necessary for LogRecoveryTest
        if (ec.isGcEnabled()) {
            utilizationProfile.save();
        }
    }

    @SuppressWarnings("OverlyLongMethod")
    public /* public access is necessary to invoke the method from the Reflect class */
    boolean doCleanFile(final long fileAddress) {
        // the file may be already cleaned
        if (isFileToBeDeleted(fileAddress)) {
            return false;
        }
        loggingInfo("start cleanFile(" + env.getLocation() + File.separatorChar + LogUtil.getLogFilename(fileAddress) + ')');
        // At first, we clone whole meta tree inside of 'begin transaction'
        // in order to save it completely on commit of transaction.
        // Thus we can ignore all loggables belonging to the meta tree.
        final TransactionImpl txn = env.beginTransactionWithClonedMetaTree();
        try {
            final Log log = getLog();
            if (logger.isDebugEnabled()) {
                final long high = log.getHighAddress();
                final long highFile = log.getHighFileAddress();
                logger.debug(String.format(
                        "Cleaner acquired txn when log high address was: %d (%s@%d) when cleaning file %s",
                        high, LogUtil.getLogFilename(highFile), high - highFile, LogUtil.getLogFilename(fileAddress)
                ));
            }
            final long nextFileAddress = fileAddress + log.getFileLengthBound();
            final Iterator<RandomAccessLoggable> loggables = log.getLoggableIterator(fileAddress);
            while (loggables.hasNext()) {
                final RandomAccessLoggable loggable = loggables.next();
                if (loggable.getAddress() >= nextFileAddress) {
                    break;
                }
                final int structureId = loggable.getStructureId();
                if (structureId != Loggable.NO_STRUCTURE_ID && structureId != EnvironmentImpl.META_TREE_ID) {
                    StoreImpl store = openStoresCache.get(structureId);
                    if (store == null) {
                        // TODO: remove openStoresCache when txn.openStoreByStructureId() is fast enough (XD-381)
                        store = txn.openStoreByStructureId(structureId);
                        openStoresCache.put(structureId, store);
                    }
                    store.reclaim(txn, loggable, loggables, expirationChecker);
                }
            }
            if (!txn.forceFlush()) {
                Thread.yield();
                return false;
            }
        } catch (Throwable e) {
            logger.error("cleanFile(" + LogUtil.getLogFilename(fileAddress) + ')', e);
            throw ExodusException.toExodusException(e);
        } finally {
            txn.abort();
        }
        pendingFilesToDelete.add(fileAddress);
        env.executeTransactionSafeTask(new Runnable() {
            @Override
            public void run() {
                final int filesDeletionDelay = ec.getGcFilesDeletionDelay();
                if (filesDeletionDelay == 0) {
                    deletionQueue.offer(fileAddress);
                } else {
                    DeferredIO.getJobProcessor().queueIn(new Job() {
                        @Override
                        protected void execute() throws Throwable {
                            deletionQueue.offer(fileAddress);
                        }
                    }, filesDeletionDelay);
                }
            }
        });
        return true;
    }

    public static boolean isUtilizationProfile(@NotNull final String storeName) {
        return UTILIZATION_PROFILE_STORE_NAME.equals(storeName);
    }

    @NotNull
    BackgroundCleaner getCleaner() {
        return cleaner;
    }

    int getMinFileAge() {
        return ec.getGcFileMinAge();
    }

    boolean isTooMuchFreeSpace() {
        return utilizationProfile.totalFreeSpacePercent() > getMaximumFreeSpacePercent();
    }

    void deletePendingFiles() {
        cleaner.checkThread();
        Long fileAddress;
        boolean aFileWasDeleted = false;
        while ((fileAddress = deletionQueue.poll()) != null) {
            aFileWasDeleted |= doDeletePendingFile(fileAddress);
        }
        if (aFileWasDeleted) {
            utilizationProfile.estimateTotalBytes();
            utilizationProfile.save();
        }
    }

    @NotNull
    EnvironmentImpl getEnvironment() {
        return env;
    }

    Log getLog() {
        return env.getLog();
    }

    /**
     * Cleans a file by address. In order to avoid race conditions and synchronization issues,
     * this method should be called from the thread of background cleaner.
     *
     * @param fileAddress address of file.
     * @return true if the file was actually cleaned
     */
    boolean cleanFile(final long fileAddress) {
        cleaner.checkThread();
        return doCleanFile(fileAddress);
    }

    /**
     * Is file already cleaned and is to be deleted soon.
     *
     * @param fileAddress address of file.
     * @return true if file is pending to be deleted soon.
     */
    boolean isFileToBeDeleted(long fileAddress) {
        return pendingFilesToDelete.contains(fileAddress);
    }

    int getNewFiles() {
        return newFiles;
    }

    void resetNewFiles() {
        newFiles = 0;
    }

    /**
     * For tests only!!!
     */
    void cleanWholeLog() {
        cleaner.cleanWholeLog();
    }

    /**
     * For tests only!!!
     */
    void testDeletePendingFiles() {
        final long[] files = pendingFilesToDelete.toLongArray();
        boolean aFileWasDeleted = false;
        for (final long fileAddress : files) {
            aFileWasDeleted |= doDeletePendingFile(fileAddress);
        }
        if (aFileWasDeleted) {
            utilizationProfile.estimateTotalBytes();
        }
    }

    static void loggingInfo(@NotNull final String message) {
        if (logger.isInfoEnabled()) {
            logger.info(message);
        }
    }

    private boolean doDeletePendingFile(long fileAddress) {
        if (pendingFilesToDelete.remove(fileAddress)) {
            // force flush and fsync in order to fix XD-249
            // in order to avoid data loss, it's necessary to make sure that any GC transaction is flushed
            // to underlying storage device before any file is deleted
            env.flushAndSync();
            utilizationProfile.removeFile(fileAddress);
            getLog().removeFile(fileAddress, ec.getGcRenameFiles() ? RemoveBlockType.Rename : RemoveBlockType.Delete);
            return true;
        }
        return false;
    }
}
