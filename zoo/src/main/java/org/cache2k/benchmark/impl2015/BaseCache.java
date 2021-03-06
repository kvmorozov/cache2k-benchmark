package org.cache2k.benchmark.impl2015;

/*
 * #%L
 * zoo
 * %%
 * Copyright (C) 2013 - 2016 headissue GmbH, Munich
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import org.cache2k.benchmark.impl2015.util.Log;
import org.cache2k.benchmark.impl2015.util.ThreadDump;
import org.cache2k.benchmark.impl2015.util.TunableConstants;
import org.cache2k.benchmark.impl2015.util.TunableFactory;
import static org.cache2k.benchmark.impl2015.util.Util.*;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.Date;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Foundation for all cache variants. All common functionality is in here.
 * For a (in-memory) cache we need three things: a fast hash table implementation, an
 * LRU list (a simple double linked list), and a fast timer.
 * The variants implement different eviction strategies.
 *
 * <p>Locking: The cache has a single structure lock obtained via {@link #lock} and also
 * locks on each entry for operations on it. Though, mutation operations that happen on a
 * single entry get serialized.
 *
 * @author Jens Wilke; created: 2013-07-09
 */
@SuppressWarnings({"unchecked", "SynchronizationOnLocalVariableOrMethodParameter"})
public abstract class BaseCache<E extends Entry, K, T> implements Cache<K,T> {

  static final Random SEED_RANDOM = new Random(new SecureRandom().nextLong());
  static int cacheCnt = 0;

  protected static final Tunable TUNABLE = TunableFactory.get(Tunable.class);

  protected int hashSeed;

  {
    if (TUNABLE.disableHashRandomization) {
      hashSeed = TUNABLE.hashSeed;
    } else {
      hashSeed = SEED_RANDOM.nextInt();
    }
  }

  /** Maximum amount of elements in cache */
  protected int maxSize = 5000;

  protected String name;
  protected CacheSourceWithMetaInfo<K, T> source;
  /** Statistics */

  /** Time in milliseconds we keep an element */
  protected long maxLinger = 10 * 60 * 1000;

  protected long exceptionMaxLinger = 1 * 60 * 1000;

  protected EntryExpiryCalculator<K, T> entryExpiryCalculator;

  protected ExceptionExpiryCalculator<K> exceptionExpiryCalculator;

  protected CacheBaseInfo info;

  protected long clearedTime = 0;
  protected long startedTime;
  protected long touchedTime;
  protected int timerCancelCount = 0;

  protected long keyMutationCount = 0;
  protected long putCnt = 0;
  protected long putButExpiredCnt = 0;
  protected long putNewEntryCnt = 0;
  protected long removedCnt = 0;
  /** Number of entries removed by clear. */
  protected long clearedCnt = 0;
  protected long expiredKeptCnt = 0;
  protected long expiredRemoveCnt = 0;
  protected long evictedCnt = 0;
  protected long refreshCnt = 0;
  protected long suppressedExceptionCnt = 0;
  protected long fetchExceptionCnt = 0;
  /* that is a miss, but a hit was already counted. */
  protected long peekHitNotFreshCnt = 0;
  /* no heap hash hit */
  protected long peekMissCnt = 0;

  protected long fetchCnt = 0;

  protected long fetchButHitCnt = 0;
  protected long bulkGetCnt = 0;
  protected long fetchMillis = 0;
  protected long refreshHitCnt = 0;
  protected long newEntryCnt = 0;

  /**
   * Entries created for processing via invoke or replace, but no operation happened on it.
   * The entry processor may just have checked the entry state or an exception happened.
   */
  protected long atomicOpNewEntryCnt = 0;

  /**
   * Loaded from storage, but the entry was not fresh and cannot be returned.
   */
  protected long loadNonFreshCnt = 0;

  /**
   * Entry was loaded from storage and fresh.
   */
  protected long loadHitCnt = 0;

  /**
   * Separate counter for loaded entries that needed a fetch.
   */
  protected long loadNonFreshAndFetchedCnt;

  protected long refreshSubmitFailedCnt = 0;

  /**
   * An exception that should not have happened and was not thrown to the
   * application. Only used for the refresh thread yet.
   */
  protected long internalExceptionCnt = 0;

  /**
   * Needed to correct the counter invariants, because during eviction the entry
   * might be removed from the replacement list, but still in the hash.
   */
  protected int evictedButInHashCnt = 0;

  /**
   * Storage did not contain the requested entry.
   */
  protected long loadMissCnt = 0;

  /**
   * A newly inserted entry was removed by the eviction without the fetch to complete.
   */
  protected long virginEvictCnt = 0;

  protected long timerEvents = 0;

  /**
   * Structure lock of the cache. Every operation that needs a consistent structure
   * of the cache or modifies it needs to synchronize on this. Since this is a global
   * lock, locking on it should be avoided and any operation under the lock should be
   * quick.
   */
  protected final Object lock = new Object();

  protected CacheRefreshThreadPool refreshPool;

  protected Hash<E> mainHashCtrl;
  protected E[] mainHash;

  protected Hash<E> refreshHashCtrl;
  protected E[] refreshHash;

  protected Timer timer;

  protected boolean shutdownInitiated = false;

  /**
   * Flag during operation that indicates, that the cache is full and eviction needs
   * to be done. Eviction is only allowed to happen after an entry is fetched, so
   * at the end of an cache operation that increased the entry count we check whether
   * something needs to be evicted.
   */
  protected boolean evictionNeeded = false;

  /**
   * Stops creation of new entries when clear is ongoing.
   */
  protected boolean waitForClear = false;

  private int featureBits = 0;

  private static final int SHARP_TIMEOUT_FEATURE = 1;
  private static final int KEEP_AFTER_EXPIRED = 2;
  private static final int SUPPRESS_EXCEPTIONS = 4;
  private static final int NULL_VALUE_SUPPORT = 8;

  protected final boolean hasSharpTimeout() {
    return (featureBits & SHARP_TIMEOUT_FEATURE) > 0;
  }

  protected final boolean hasKeepAfterExpired() {
    return (featureBits & KEEP_AFTER_EXPIRED) > 0;
  }

  protected final boolean hasNullValueSupport() {
    return (featureBits & NULL_VALUE_SUPPORT) > 0;
  }

  protected final boolean hasSuppressExceptions() {
    return (featureBits & SUPPRESS_EXCEPTIONS) > 0;
  }

  protected final void setFeatureBit(int _bitmask, boolean _flag) {
    if (_flag) {
      featureBits |= _bitmask;
    } else {
      featureBits &= ~_bitmask;
    }
  }

  /**
   * Enabling background refresh means also serving expired values.
   */
  protected final boolean hasBackgroundRefreshAndServesExpiredValues() {
    return refreshPool != null;
  }

  /**
   * Returns name of the cache with manager name.
   */
  protected String getCompleteName() {
    return name;
  }

  /**
   * Normally a cache itself logs nothing, so just construct when needed.
   */
  protected Log getLog() {
    return
      Log.getLog(Cache.class.getName() + '/' + getCompleteName());
  }

  /** called via reflection from CacheBuilder */
  public void setCacheConfig(CacheConfig c) {
    if (name != null) {
      throw new IllegalStateException("already configured");
    }
    setName(c.getName());
    maxSize = c.getEntryCapacity();
    if (c.getHeapEntryCapacity() >= 0) {
      maxSize = c.getHeapEntryCapacity();
    }
    if (c.isBackgroundRefresh()) {
      refreshPool = CacheRefreshThreadPool.getInstance();
    }
    long _expiryMillis  = c.getExpiryMillis();
    if (_expiryMillis == Long.MAX_VALUE || _expiryMillis < 0) {
      maxLinger = -1;
    } else if (_expiryMillis >= 0) {
      maxLinger = _expiryMillis;
    }
    long _exceptionExpiryMillis = c.getExceptionExpiryMillis();
    if (_exceptionExpiryMillis == -1) {
      if (maxLinger == -1) {
        exceptionMaxLinger = -1;
      } else {
        exceptionMaxLinger = maxLinger / 10;
      }
    } else {
      exceptionMaxLinger = _exceptionExpiryMillis;
    }
    setFeatureBit(KEEP_AFTER_EXPIRED, c.isKeepDataAfterExpired());
    setFeatureBit(SHARP_TIMEOUT_FEATURE, c.isSharpExpiry());
    setFeatureBit(SUPPRESS_EXCEPTIONS, c.isSuppressExceptions());
    /*
    if (c.isPersistent()) {
      storage = new PassingStorageAdapter();
    }
    -*/
  }

  @SuppressWarnings("unused")
  public void setSource(CacheSourceWithMetaInfo<K, T> eg) {
    source = eg;
  }

  @SuppressWarnings("unused")
  public void setSource(final CacheSource<K, T> g) {
    if (g != null) {
      source = new CacheSourceWithMetaInfo<K, T>() {
        @Override
        public T get(K key, long _currentTime, T _previousValue, long _timeLastFetched) throws Throwable {
          return g.get(key);
        }
      };
    }
  }

  /**
   * Set the name and configure a logging, used within cache construction.
   */
  public void setName(String n) {
    if (n == null) {
      n = this.getClass().getSimpleName() + "#" + cacheCnt++;
    }
    name = n;
  }

  /**
   * Set the time in seconds after which the cache does an refresh of the
   * element. -1 means the element will be hold forever.
   * 0 means the element will not be cached at all.
   */
  public void setExpirySeconds(int s) {
    if (s < 0 || s == Integer.MAX_VALUE) {
      maxLinger = -1;
      return;
    }
    maxLinger = s * 1000;
  }

  @Override
  public String getName() {
    return name;
  }

  /**
   * Registers the cache in a global set for the clearAllCaches function and
   * registers it with the resource monitor.
   */
  public void init() {
    synchronized (lock) {
      if (name == null) {
        name = "" + cacheCnt++;
      }
      initializeHeapCache();
      initTimer();
      if (refreshPool != null &&
        source == null) {
        throw new CacheMisconfigurationException("backgroundRefresh, but no source");
      }
      if (refreshPool != null && timer == null) {
        if (maxLinger == 0) {
          getLog().warn("Background refresh is enabled, but elements are fetched always. Disable background refresh!");
        } else {
          getLog().warn("Background refresh is enabled, but elements are eternal. Disable background refresh!");
        }
        refreshPool.destroy();
        refreshPool = null;
      }
    }
  }

  boolean isNeedingTimer() {
    return
        maxLinger > 0 || entryExpiryCalculator != null ||
        exceptionMaxLinger > 0 || exceptionExpiryCalculator != null;
  }

  /**
   * Either add a timer or remove the timer if needed or not needed.
   */
  private void initTimer() {
    if (isNeedingTimer()) {
      if (timer == null) {
        timer = new Timer(name, true);
      }
    } else {
      if (timer != null) {
        timer.cancel();
        timer = null;
      }
    }
  }

  protected void checkClosed() {
    if (isClosed()) {
      throw new CacheClosedException();
    }
    while (waitForClear) {
      try {
        lock.wait();
      } catch (InterruptedException ignore) { }
    }
  }

  public final void clear() {
    synchronized (lock) {
      checkClosed();
      clearLocalCache();
    }
  }

  protected final void clearLocalCache() {
    iterateAllEntriesRemoveAndCancelTimer();
    clearedCnt += getLocalSize();
    initializeHeapCache();
    clearedTime = System.currentTimeMillis();
    touchedTime = clearedTime;
  }

  protected void iterateAllEntriesRemoveAndCancelTimer() {
    Iterator<Entry> it = iterateAllHeapEntries();
    int _count = 0;
    while (it.hasNext()) {
      Entry e = it.next();
      e.removedFromList();
      cancelExpiryTimer(e);
      _count++;
    }
  }

  protected void initializeHeapCache() {
    if (mainHashCtrl != null) {
      mainHashCtrl.cleared();
      refreshHashCtrl.cleared();
    }
    mainHashCtrl = new Hash<E>();
    refreshHashCtrl = new Hash<E>();
    mainHash = mainHashCtrl.init((Class<E>) newEntry().getClass());
    refreshHash = refreshHashCtrl.init((Class<E>) newEntry().getClass());
    if (startedTime == 0) {
      startedTime = System.currentTimeMillis();
    }
    if (timer != null) {
      timer.cancel();
      timer = null;
      initTimer();
    }
  }

  /**
   * Preparation for shutdown. Cancel all pending timer jobs e.g. for
   * expiry/refresh or flushing the storage.
   */
  void cancelTimerJobs() {
    synchronized (lock) {
      if (timer != null) {
        timer.cancel();
      }
    }
  }

  @Override
  public boolean isClosed() {
    return shutdownInitiated;
  }

  @Override
  public void destroy() {
    close();
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (shutdownInitiated) {
        return;
      }
      shutdownInitiated = true;
    }
    cancelTimerJobs();
    synchronized (lock) {
      mainHashCtrl.close();
      refreshHashCtrl.close();
    }
    synchronized (lock) {
      if (timer != null) {
        timer.cancel();
        timer = null;
      }
      if (refreshPool != null) {
        refreshPool.destroy();
        refreshPool = null;
      }
      mainHash = refreshHash = null;
      source = null;
    }
  }

  @Override
  public void removeAll() {
    ClosableIterator<E> it;
    synchronized (lock) {
      it = new IteratorFilterFresh((ClosableIterator<E>) iterateAllHeapEntries());
    }
    while (it.hasNext()) {
      E e = it.next();
      remove((K) e.getKey());
    }
  }

  @Override
  public ClosableIterator<CacheEntry<K, T>> iterator() {
    synchronized (lock) {
      return new IteratorFilterEntry2Entry((ClosableIterator<E>) iterateAllHeapEntries(), true);
    }
  }

  /**
   * Filter out non valid entries and wrap each entry with a cache
   * entry object.
   */
  class IteratorFilterEntry2Entry implements ClosableIterator<CacheEntry<K, T>> {

    ClosableIterator<E> iterator;
    E entry;
    CacheEntry<K, T> lastEntry;
    boolean filter = true;

    IteratorFilterEntry2Entry(ClosableIterator<E> it) { iterator = it; }

    IteratorFilterEntry2Entry(ClosableIterator<E> it, boolean _filter) {
      iterator = it;
      filter = _filter;
    }

    /**
     * Between hasNext() and next() an entry may be evicted or expired.
     * In practise we have to deliver a next entry if we return hasNext() with
     * true, furthermore, there should be no big gap between the calls to
     * hasNext() and next().
     */
    @Override
    public boolean hasNext() {
      if (entry != null) {
        return true;
      }
      if (iterator == null) {
        return false;
      }
      while (iterator.hasNext()) {
        E e = iterator.next();
        if (filter) {
          if (e.hasFreshData()) {
            entry = e;
            return true;
          }
        } else {
          entry = e;
          return true;
        }
      }
      entry = null;
      close();
      return false;
    }

    @Override
    public void close() {
      if (iterator != null) {
        iterator.close();
        iterator = null;
      }
    }

    @Override
    public CacheEntry<K, T> next() {
      if (entry == null && !hasNext()) {
        throw new NoSuchElementException("not available");
      }
      recordHitLocked(entry);
      lastEntry = returnEntry(entry);
      entry = null;
      return lastEntry;
    }

    @Override
    public void remove() {
      if (lastEntry == null) {
        throw new IllegalStateException("hasNext() / next() not called or end of iteration reached");
      }
      BaseCache.this.remove((K) lastEntry.getKey());
    }
  }

  /**
   * Filter out non valid entries and wrap each entry with a cache
   * entry object.
   */
  class IteratorFilterFresh implements ClosableIterator<E> {

    ClosableIterator<E> iterator;
    E entry;
    CacheEntry<K, T> lastEntry;
    boolean filter = true;

    IteratorFilterFresh(ClosableIterator<E> it) { iterator = it; }

    /**
     * Between hasNext() and next() an entry may be evicted or expired.
     * In practise we have to deliver a next entry if we return hasNext() with
     * true, furthermore, there should be no big gap between the calls to
     * hasNext() and next().
     */
    @Override
    public boolean hasNext() {
      if (entry != null) {
        return true;
      }
      if (iterator == null) {
        return false;
      }
      while (iterator.hasNext()) {
        E e = iterator.next();
        if (e.hasFreshData()) {
          entry = e;
          return true;
        }
      }
      entry = null;
      close();
      return false;
    }

    @Override
    public void close() {
      if (iterator != null) {
        iterator.close();
        iterator = null;
      }
    }

    @Override
    public E next() {
      if (entry == null && !hasNext()) {
        throw new NoSuchElementException("not available");
      }
      E tmp = entry;
      entry = null;
      return tmp;
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }

  }

  protected static void removeFromList(final Entry e) {
    e.prev.next = e.next;
    e.next.prev = e.prev;
    e.removedFromList();
  }

  protected static void insertInList(final Entry _head, final Entry e) {
    e.prev = _head;
    e.next = _head.next;
    e.next.prev = e;
    _head.next = e;
  }

  protected static final int getListEntryCount(final Entry _head) {
    Entry e = _head.next;
    int cnt = 0;
    while (e != _head) {
      cnt++;
      if (e == null) {
        return -cnt;
      }
      e = e.next;
    }
    return cnt;
  }

  protected static final <E extends Entry> void moveToFront(final E _head, final E e) {
    removeFromList(e);
    insertInList(_head, e);
  }

  protected static final <E extends Entry> E insertIntoTailCyclicList(final E _head, final E e) {
    if (_head == null) {
      return (E) e.shortCircuit();
    }
    e.next = _head;
    e.prev = _head.prev;
    _head.prev = e;
    e.prev.next = e;
    return _head;
  }

  /**
   * Insert X into A B C, yields: A X B C.
   */
  protected static final <E extends Entry> E insertAfterHeadCyclicList(final E _head, final E e) {
    if (_head == null) {
      return (E) e.shortCircuit();
    }
    e.prev = _head;
    e.next = _head.next;
    _head.next.prev = e;
    _head.next = e;
    return _head;
  }

  /** Insert element at the head of the list */
  protected static final <E extends Entry> E insertIntoHeadCyclicList(final E _head, final E e) {
    if (_head == null) {
      return (E) e.shortCircuit();
    }
    e.next = _head;
    e.prev = _head.prev;
    _head.prev.next = e;
    _head.prev = e;
    return e;
  }

  protected static <E extends Entry> E removeFromCyclicList(final E _head, E e) {
    if (e.next == e) {
      e.removedFromList();
      return null;
    }
    Entry _eNext = e.next;
    e.prev.next = _eNext;
    e.next.prev = e.prev;
    e.removedFromList();
    return e == _head ? (E) _eNext : _head;
  }

  protected static Entry removeFromCyclicList(final Entry e) {
    Entry _eNext = e.next;
    e.prev.next = _eNext;
    e.next.prev = e.prev;
    e.removedFromList();
    return _eNext == e ? null : _eNext;
  }

  protected static int getCyclicListEntryCount(Entry e) {
    if (e == null) { return 0; }
    final Entry _head = e;
    int cnt = 0;
    do {
      cnt++;
      e = e.next;
      if (e == null) {
        return -cnt;
      }
    } while (e != _head);
    return cnt;
  }

  protected static boolean checkCyclicListIntegrity(Entry e) {
    if (e == null) { return true; }
    Entry _head = e;
    do {
      if (e.next == null) {
        return false;
      }
      if (e.next.prev == null) {
        return false;
      }
      if (e.next.prev != e) {
        return false;
      }
      e = e.next;
    } while (e != _head);
    return true;
  }

  /**
   * Record an entry hit.
   */
  protected abstract void recordHit(E e);

  /**
   * New cache entry, put it in the replacement algorithm structure
   */
  protected abstract void insertIntoReplacementList(E e);

  /**
   * Entry object factory. Return an entry of the proper entry subtype for
   * the replacement/eviction algorithm.
   */
  protected abstract E newEntry();


  /**
   * Find an entry that should be evicted. Called within structure lock.
   * After doing some checks the cache will call {@link #removeEntryFromReplacementList(Entry)}
   * if this entry will be really evicted. Pinned entries may be skipped. A
   * good eviction algorithm returns another candidate on sequential calls, even
   * if the candidate was not removed.
   *
   * <p/>Rationale: Within the structure lock we can check for an eviction candidate
   * and may remove it from the list. However, we cannot process additional operations or
   * events which affect the entry. For this, we need to acquire the lock on the entry
   * first.
   */
  protected abstract E findEvictionCandidate();


  /**
   *
   */
  protected void removeEntryFromReplacementList(E e) {
    removeFromList(e);
  }

  /**
   * Check whether we have an entry in the ghost table
   * remove it from ghost and insert it into the replacement list.
   * null if nothing there. This may also do an optional eviction
   * if the size limit of the cache is reached, because some replacement
   * algorithms (ARC) do this together.
   */
  protected E checkForGhost(K key, int hc) { return null; }

  /**
   * Implement unsynchronized lookup if it is supported by the eviction.
   * If a null is returned the lookup is redone synchronized.
   */
  protected E lookupEntryUnsynchronized(K key, int hc) { return null; }

  protected E lookupEntryUnsynchronizedNoHitRecord(K key, int hc) { return null; }

  protected void recordHitLocked(E e) {
    synchronized (lock) {
      recordHit(e);
    }
  }

  @Override
  public T get(K key) {
    return (T) returnValue(getEntryInternal(key));
  }

  /**
   * Wrap entry in a separate object instance. We can return the entry directly, however we lock on
   * the entry object.
   */
  protected CacheEntry<K, T> returnEntry(final Entry<E, K, T> e) {
    if (e == null) {
      return null;
    }
    synchronized (e) {
      final K _key = e.getKey();
      final T _value = e.getValue();
      final Throwable _exception = e.getException();
      final long _lastModification = e.getLastModification();
      return returnCacheEntry(_key, _value, _exception, _lastModification);
    }
  }

  public String getEntryState(K key) {
    E e = getEntryInternal(key);
    return generateEntryStateString(e);
  }

  private String generateEntryStateString(E e) {
    synchronized (e) {
      String _timerState = "n/a";
      if (e.task != null) {
        _timerState = "<unavailable>";
        try {
          Field f = TimerTask.class.getDeclaredField("state");
          f.setAccessible(true);
          int _state = f.getInt(e.task);
          _timerState = _state + "";
        } catch (Exception x) {
          _timerState = x.toString();
        }
      }
      return
          "Entry{" + System.identityHashCode(e) + "}, " +
          "keyIdentityHashCode=" + System.identityHashCode(e.key) + ", " +
          "valueIdentityHashCode=" + System.identityHashCode(e.value) + ", " +
          "keyHashCode" + e.key.hashCode() + ", " +
          "keyModifiedHashCode=" + e.hashCode + ", " +
          "keyMutation=" + (modifiedHash(e.key.hashCode()) != e.hashCode) + ", " +
          "modified=" + e.getLastModification() + ", " +
          "nextRefreshTime(with state)=" + e.nextRefreshTime + ", " +
          "hasTimer=" + (e.task != null ? "true" : "false") + ", " +
          "timerState=" + _timerState;
    }
  }

  private CacheEntry<K, T> returnCacheEntry(final K _key, final T _value, final Throwable _exception, final long _lastModification) {
    CacheEntry<K, T> ce = new CacheEntry<K, T>() {
      @Override
      public K getKey() {
        return _key;
      }

      @Override
      public T getValue() {
        return _value;
      }

      @Override
      public Throwable getException() {
        return _exception;
      }

      @Override
      public long getLastModification() {
        return _lastModification;
      }

      @Override
      public String toString() {
        return "CacheEntry(" +
            "key=" + getKey() +
            ((getException() != null) ? ", exception=" + getException() + ", " : ", value=" + getValue()) +
            ", updated=" + formatMillis(getLastModification());
      }

    };
    return ce;
  }

  @Override
  public CacheEntry<K, T> getEntry(K key) {
    return returnEntry(getEntryInternal(key));
  }

  protected E getEntryInternal(K key) {
    long _previousNextRefreshTime;
    E e;
    for (;;) {
      e = lookupOrNewEntrySynchronized(key);
      if (e.hasFreshData()) {
        return e;
      }
      synchronized (e) {
        e.waitForFetch();
        if (e.hasFreshData()) {
          return e;
        }
        if (e.isRemovedState()) {
          continue;
        }
        _previousNextRefreshTime = e.startFetch();
        break;
      }
    }
    boolean _finished = false;
    try {
      finishFetch(e, fetch(e, _previousNextRefreshTime));
      _finished = true;
    } finally {
      e.ensureFetchAbort(_finished, _previousNextRefreshTime);
    }
    evictEventually();
    return e;
  }

  protected void finishFetch(E e, long _nextRefreshTime) {
    synchronized (e) {
      e.nextRefreshTime = stopStartTimer(_nextRefreshTime, e, System.currentTimeMillis());
      e.notifyAll();
    }
  }

  /** Always fetch the value from the source. That is a copy of getEntryInternal without fresh checks. */
  protected void fetchAndReplace(K key) {
    long _previousNextRefreshTime;
    E e;
    for (;;) {
      e = lookupOrNewEntrySynchronized(key);
      synchronized (e) {
        e.waitForFetch();
        if (e.isRemovedState()) {
          continue;
        }
        _previousNextRefreshTime = e.startFetch();
        break;
      }
    }
    boolean _finished = false;
    try {
      finishFetch(e, fetch(e, _previousNextRefreshTime));
      _finished = true;
    } finally {
      e.ensureFetchAbort(_finished, _previousNextRefreshTime);
    }
    evictEventually();
  }

  protected final void evictEventually() {
    int _spinCount = TUNABLE.maximumEvictSpins;
    E _previousCandidate = null;
    while (evictionNeeded) {
      if (_spinCount-- <= 0) { return; }
      E e;
      synchronized (lock) {
        checkClosed();
        if (getLocalSize() <= maxSize) {
          evictionNeeded = false;
          return;
        }
        e = findEvictionCandidate();
      }
      synchronized (e) {
        if (e.isRemovedState()) {
          continue;
        }
        if (e.isPinned()) {
          if (e != _previousCandidate) {
            _previousCandidate = e;
            continue;
          } else {
            return;
          }
        }

        boolean _storeEvenImmediatelyExpired = hasKeepAfterExpired() && (e.isDataValidState() || e.isExpiredState() || e.nextRefreshTime == Entry.FETCH_NEXT_TIME_STATE);
        evictEntryFromHeap(e);
      }
    }
  }

  private void evictEntryFromHeap(E e) {
    synchronized (lock) {
      if (e.isRemovedFromReplacementList()) {
        if (removeEntryFromHash(e)) {
          evictedButInHashCnt--;
          evictedCnt++;
        }
      } else {
        if (removeEntry(e)) {
          evictedCnt++;
        }
      }
      evictionNeeded = getLocalSize() > maxSize;
    }
    e.notifyAll();
  }

  /**
   * Remove the entry from the hash and the replacement list.
   * There is a race condition to catch: The eviction may run
   * in a parallel thread and may have already selected this
   * entry.
   */
  protected boolean removeEntry(E e) {
    if (!e.isRemovedFromReplacementList()) {
      removeEntryFromReplacementList(e);
    }
    return removeEntryFromHash(e);
  }

  final protected E peekEntryInternal(K key) {
    final int hc = modifiedHash(key.hashCode());
    int _spinCount = TUNABLE.maximumEntryLockSpins;
    for (;;) {
      if (_spinCount-- <= 0) { throw new CacheLockSpinsExceededError(); }
      E e = lookupEntryUnsynchronized(key, hc);
      if (e == null) {
        synchronized (lock) {
          e = lookupEntry(key, hc);
        }
      }
      if (e == null) {
        peekMissCnt++;
        return null;
      }
      if (e.hasFreshData()) { return e; }
      boolean _hasFreshData = false;
      evictEventually();
      if (_hasFreshData) {
        return e;
      }
      peekHitNotFreshCnt++;
      return null;
    }
  }

  @Override
  public boolean contains(K key) {
    E e = lookupEntrySynchronizedNoHitRecord(key);
    return e != null && e.hasFreshData();
  }

  @Override
  public T peek(K key) {
    E e = peekEntryInternal(key);
    if (e != null) {
      return (T) returnValue(e);
    }
    return null;
  }

  @Override
  public CacheEntry<K, T> peekEntry(K key) {
    return returnEntry(peekEntryInternal(key));
  }

  @Override
  public void put(K key, T value) {
    int _spinCount = TUNABLE.maximumEntryLockSpins;
    E e;
    for (;;) {
      if (_spinCount-- <= 0) {
        throw new CacheLockSpinsExceededError();
      }
      e = lookupOrNewEntrySynchronized(key);
      synchronized (e) {
        if (e.isRemovedState()) {
          continue;
        }
        if (e.isFetchInProgress()) {
          e.waitForFetch();
          continue;
        }
        long t = System.currentTimeMillis();
        long _nextRefreshTime = e.nextRefreshTime;
        if (e.hasFreshData(t)) {
          e.nextRefreshTime = Entry.REPUT_STATE;
        }
        _nextRefreshTime = insertOnPut(e, value, t, t, e.nextRefreshTime);
        e.nextRefreshTime = stopStartTimer(_nextRefreshTime, e, System.currentTimeMillis());
      }
      evictEventually();
      return;
    }
  }

  @Override
  public boolean remove(K key, T _value) {
    return removeWithFlag(key, true, _value);
  }

  public boolean removeWithFlag(K key) {
    return removeWithFlag(key, false, null);
  }

  /**
   * Remove the object mapped to a key from the cache.
   *
   * <p>Operation with storage: If there is no entry within the cache there may
   * be one in the storage, so we need to send the remove to the storage. However,
   * if a remove() and a get() is going on in parallel it may happen that the entry
   * gets removed from the storage and added again by the tail part of get(). To
   * keep the cache and the storage consistent it must be ensured that this thread
   * is the only one working on the entry.
   */
  public boolean removeWithFlag(K key, boolean _checkValue, T _value) {
    E e = lookupEntrySynchronized(key);
    if (e != null) {
      synchronized (e) {
        e.waitForFetch();
        if (!e.isRemovedState()) {
          synchronized (lock) {
            boolean f = e.hasFreshData();
            if (_checkValue) {
              if (!f || !e.equalsValue(_value)) {
                return false;
              }
            }
            if (removeEntry(e)) {
              removedCnt++;
              return f;
            }
            return false;
          }
        }
      }
    }
    return false;
  }

  @Override
  public void remove(K key) {
    removeWithFlag(key);
  }

  /**
   * Lookup or create a new entry. The new entry is created, because we need
   * it for locking within the data fetch.
   */
  protected E lookupOrNewEntrySynchronized(K key) {
    int hc = modifiedHash(key.hashCode());
    return lookupOrNewEntrySynchronized(key, hc);
  }

  protected E lookupOrNewEntrySynchronized(K key, int hc) {
    E e = lookupEntryUnsynchronized(key, hc);
    if (e == null) {
      synchronized (lock) {
        checkClosed();
        e = lookupEntry(key, hc);
        if (e == null) {
          e = newEntry(key, hc);
        }
      }
    }
    return e;
  }

  protected T returnValue(Entry<E, K,T> e) {
    T v = e.value;
    if (v instanceof ExceptionWrapper) {
      ExceptionWrapper w = (ExceptionWrapper) v;
      if (w.additionalExceptionMessage == null) {
        synchronized (e) {
          long t = e.getValueExpiryTime();
          w.additionalExceptionMessage = "(expiry=" + (t > 0 ? formatMillis(t) : "none") + ") " + w.getException();
        }
      }
      throw new PropagatedCacheException(w.additionalExceptionMessage, w.getException());
    }
    return v;
  }

  protected E lookupEntrySynchronized(K key) {
    int hc = modifiedHash(key.hashCode());
    E e = lookupEntryUnsynchronized(key, hc);
    if (e == null) {
      synchronized (lock) {
        e = lookupEntry(key, hc);
      }
    }
    return e;
  }

  protected E lookupEntrySynchronizedNoHitRecord(K key) {
    int hc = modifiedHash(key.hashCode());
    E e = lookupEntryUnsynchronizedNoHitRecord(key, hc);
    if (e == null) {
      synchronized (lock) {
        e = lookupEntryNoHitRecord(key, hc);
      }
    }
    return e;
  }

  protected final E lookupEntry(K key, int hc) {
    E e = Hash.lookup(mainHash, key, hc);
    if (e != null) {
      recordHit(e);
      return e;
    }
    e = refreshHashCtrl.remove(refreshHash, key, hc);
    if (e != null) {
      refreshHitCnt++;
      mainHash = mainHashCtrl.insert(mainHash, e);
      recordHit(e);
      return e;
    }
    return null;
  }

  protected final E lookupEntryNoHitRecord(K key, int hc) {
    E e = Hash.lookup(mainHash, key, hc);
    if (e != null) {
      return e;
    }
    e = refreshHashCtrl.remove(refreshHash, key, hc);
    if (e != null) {
      refreshHitCnt++;
      mainHash = mainHashCtrl.insert(mainHash, e);
      return e;
    }
    return null;
  }


  /**
   * Insert new entry in all structures (hash and replacement list). May evict an
   * entry if the maximum capacity is reached.
   */
  protected E newEntry(K key, int hc) {
    if (getLocalSize() >= maxSize) {
      evictionNeeded = true;
    }
    E e = checkForGhost(key, hc);
    if (e == null) {
      e = newEntry();
      e.key = key;
      e.hashCode = hc;
      insertIntoReplacementList(e);
    }
    mainHash = mainHashCtrl.insert(mainHash, e);
    newEntryCnt++;
    return e;
  }

  /**
   * Called when expiry of an entry happens. Remove it from the
   * main cache, refresh cache and from the (lru) list. Also cancel the timer.
   * Called under big lock.
   */

  /**
   * The entry is already removed from the replacement list. stop/reset timer, if needed.
   * Called under big lock.
   */
  private boolean removeEntryFromHash(E e) {
    boolean f = mainHashCtrl.remove(mainHash, e) || refreshHashCtrl.remove(refreshHash, e);
    checkForHashCodeChange(e);
    cancelExpiryTimer(e);
    if (e.isVirgin()) {
      virginEvictCnt++;
    }
    e.setRemovedState();
    return f;
  }

  protected final void cancelExpiryTimer(Entry e) {
    if (e.task != null) {
      e.task.cancel();
      timerCancelCount++;
      if (timerCancelCount >= 10000) {
        timer.purge();
        timerCancelCount = 0;
      }
      e.task = null;
    }
  }

  /**
   * Check whether the key was modified during the stay of the entry in the cache.
   * We only need to check this when the entry is removed, since we expect that if
   * the key has changed, the stored hash code in the cache will not match any more and
   * the item is evicted very fast.
   */
  private void checkForHashCodeChange(Entry e) {
    if (modifiedHash(e.key.hashCode()) != e.hashCode && !e.isStale()) {
      if (keyMutationCount ==  0) {
        getLog().warn("Key mismatch! Key hashcode changed! keyClass=" + e.key.getClass().getName());
        String s;
        try {
          s = e.key.toString();
          if (s != null) {
            getLog().warn("Key mismatch! key.toString(): " + s);
          }
        } catch (Throwable t) {
          getLog().warn("Key mismatch! key.toString() threw exception", t);
        }
      }
      keyMutationCount++;
    }
  }

  /**
   * Time when the element should be fetched again from the underlying storage.
   * If 0 then the object should not be cached at all. -1 means no expiry.
   *
   * @param _newObject might be a fetched value or an exception wrapped into the {@link ExceptionWrapper}
   */
  static <K, T>  long calcNextRefreshTime(
      K _key, T _newObject, long now, Entry _entry,
      EntryExpiryCalculator<K, T> ec, long _maxLinger,
      ExceptionExpiryCalculator<K> _exceptionEc, long _exceptionMaxLinger) {
    if (!(_newObject instanceof ExceptionWrapper)) {
      if (_maxLinger == 0) {
        return 0;
      }
      if (ec != null) {
        long t = ec.calculateExpiryTime(_key, _newObject, now, _entry);
        return limitExpiryToMaxLinger(now, _maxLinger, t);
      }
      if (_maxLinger > 0) {
        return _maxLinger + now;
      }
      return -1;
    }
    if (_exceptionMaxLinger == 0) {
      return 0;
    }
    if (_exceptionEc != null) {
      ExceptionWrapper _wrapper = (ExceptionWrapper) _newObject;
      long t = _exceptionEc.calculateExpiryTime(_key, _wrapper.getException(), now);
      t = limitExpiryToMaxLinger(now, _exceptionMaxLinger, t);
      return t;
    }
    if (_exceptionMaxLinger > 0) {
      return _exceptionMaxLinger + now;
    } else {
      return _exceptionMaxLinger;
    }
  }

  static long limitExpiryToMaxLinger(long now, long _maxLinger, long t) {
    if (_maxLinger > 0) {
      long _tMaximum = _maxLinger + now;
      if (t > _tMaximum) {
        return _tMaximum;
      }
      if (t < -1 && -t > _tMaximum) {
        return -_tMaximum;
      }
    }
    return t;
  }

  protected long calcNextRefreshTime(K _key, T _newObject, long now, Entry _entry) {
    return calcNextRefreshTime(
        _key, _newObject, now, _entry,
        entryExpiryCalculator, maxLinger,
        exceptionExpiryCalculator, exceptionMaxLinger);
  }

  protected long fetch(final E e, long _previousNextRefreshTime) {
    return fetchFromSource(e, _previousNextRefreshTime);
  }

  protected boolean conditionallyStartProcess(E e) {
    if (!e.isVirgin()) {
      return false;
    }
    e.startFetch();
    return true;
  }

  protected long fetchFromSource(E e, long _previousNextRefreshValue) {
    T v;
    long t0 = System.currentTimeMillis();
    try {
      if (source == null) {
        throw new CacheUsageExcpetion("source not set");
      }
      if (e.isVirgin() || e.hasException()) {
        v = source.get((K) e.key, t0, null, e.getLastModification());
      } else {
        v = source.get((K) e.key, t0, (T) e.getValue(), e.getLastModification());
      }
      e.setLastModification(t0);
    } catch (Throwable _ouch) {
      v = (T) new ExceptionWrapper(_ouch);
    }
    long t = System.currentTimeMillis();
    return insertOrUpdateAndCalculateExpiry(e, v, t0, t, INSERT_STAT_UPDATE, _previousNextRefreshValue);
  }

  protected final long insertOnPut(E e, T v, long t0, long t, long _previousNextRefreshValue) {
    e.setLastModification(t0);
    return insertOrUpdateAndCalculateExpiry(e, v, t0, t, INSERT_STAT_PUT, _previousNextRefreshValue);
  }

  /**
   * Calculate the next refresh time if a timer / expiry is needed and call insert.
   */
  protected final long insertOrUpdateAndCalculateExpiry(E e, T v, long t0, long t, byte _updateStatistics, long _previousNextRefreshTime) {
    long _nextRefreshTime = maxLinger == 0 ? 0 : Long.MAX_VALUE;
    if (timer != null) {
      try {
        _nextRefreshTime = calculateNextRefreshTime(e, v, t0, _previousNextRefreshTime);
      } catch (Exception ex) {
        updateStatistics(e, v, t0, t, _updateStatistics, false);
        throw new CacheException("exception in expiry calculation", ex);
      }
    }
    return insert(e, v, t0, t, _updateStatistics, _nextRefreshTime);
  }

  /**
   * @throws Exception any exception from the ExpiryCalculator
   */
  private long calculateNextRefreshTime(E _entry, T _newValue, long t0, long _previousNextRefreshTime) {
    long _nextRefreshTime;
    if (Entry.isDataValidState(_previousNextRefreshTime) || Entry.isExpiredState(_previousNextRefreshTime)) {
      _nextRefreshTime = calcNextRefreshTime((K) _entry.getKey(), _newValue, t0, _entry);
    } else {
      _nextRefreshTime = calcNextRefreshTime((K) _entry.getKey(), _newValue, t0, null);
    }
    return _nextRefreshTime;
  }

  final static byte INSERT_STAT_NO_UPDATE = 0;
  final static byte INSERT_STAT_UPDATE = 1;
  final static byte INSERT_STAT_PUT = 2;

  /**
   * @param _nextRefreshTime -1/MAXVAL: eternal, 0: expires immediately
   */
  protected final long insert(E e, T _value, long t0, long t, byte _updateStatistics, long _nextRefreshTime) {

    if (_nextRefreshTime == -1) {
      _nextRefreshTime = Long.MAX_VALUE;
    }
    final boolean _suppressException =
      _value instanceof ExceptionWrapper && hasSuppressExceptions() && e.getValue() != Entry.INITIAL_VALUE && !e.hasException();

    if (!_suppressException) {
      e.value = _value;
    }
    if (_value instanceof ExceptionWrapper && !_suppressException) {
      Log log = getLog();
      if (log.isDebugEnabled()) {
        log.debug(
            "source caught exception, expires at: " + formatMillis(_nextRefreshTime),
            ((ExceptionWrapper) _value).getException());
      }
    }

    CacheStorageException _storageException = null;
    synchronized (lock) {
      checkClosed();
      updateStatisticsNeedsLock(e, _value, t0, t, _updateStatistics, _suppressException);
      if (_storageException != null) {
        throw _storageException;
      }
      if (_nextRefreshTime == 0) {
        _nextRefreshTime = Entry.FETCH_NEXT_TIME_STATE;
      } else {
        if (_nextRefreshTime == Long.MAX_VALUE) {
          _nextRefreshTime = Entry.FETCHED_STATE;
        }
      }
      if (_updateStatistics == INSERT_STAT_PUT && !e.hasFreshData(t, _nextRefreshTime)) {
        putButExpiredCnt++;
      }
    } // synchronized (lock)

    return _nextRefreshTime;
  }

  private void updateStatistics(E e, T _value, long t0, long t, byte _updateStatistics, boolean _suppressException) {
    synchronized (lock) {
      updateStatisticsNeedsLock(e, _value, t0, t, _updateStatistics, _suppressException);
    }
  }

  private void updateStatisticsNeedsLock(E e, T _value, long t0, long t, byte _updateStatistics, boolean _suppressException) {
    touchedTime = t;
    if (_updateStatistics == INSERT_STAT_UPDATE) {
      if (_suppressException) {
        suppressedExceptionCnt++;
        fetchExceptionCnt++;
      } else {
        if (_value instanceof ExceptionWrapper) {
          fetchExceptionCnt++;
        }
      }
      fetchCnt++;
      fetchMillis += t - t0;
      if (e.isGettingRefresh()) {
        refreshCnt++;
      }
      if (e.isLoadedNonValidAndFetch()) {
        loadNonFreshAndFetchedCnt++;
      } else if (!e.isVirgin()) {
        fetchButHitCnt++;
      }

    } else if (_updateStatistics == INSERT_STAT_PUT) {
      putCnt++;
      eventuallyAdjustPutNewEntryCount(e);
      if (e.nextRefreshTime == Entry.LOADED_NON_VALID_AND_PUT) {
        peekHitNotFreshCnt++;
      }
    }
  }

  private void eventuallyAdjustPutNewEntryCount(E e) {
    if (e.isVirgin()) {
      putNewEntryCnt++;
    }
  }

  private void cleanupAfterWriterException(E e) {
    if (e.isVirgin()) {
      synchronized (lock) {
        putNewEntryCnt++;
      }
    }
  }

  protected long stopStartTimer(long _nextRefreshTime, E e, long now) {
    if (e.task != null) {
      e.task.cancel();
    }
    if ((_nextRefreshTime > Entry.EXPIRY_TIME_MIN && _nextRefreshTime <= now) &&
        (_nextRefreshTime < -1 && (now >= -_nextRefreshTime))) {
      return Entry.EXPIRED_STATE;
    }
    if (hasSharpTimeout() && _nextRefreshTime > Entry.EXPIRY_TIME_MIN && _nextRefreshTime != Long.MAX_VALUE) {
      _nextRefreshTime = -_nextRefreshTime;
    }
    if (timer != null &&
      (_nextRefreshTime > Entry.EXPIRY_TIME_MIN || _nextRefreshTime < -1)) {
      if (_nextRefreshTime < -1) {
        long _timerTime =
          -_nextRefreshTime - TUNABLE.sharpExpirySafetyGapMillis;
        if (_timerTime >= now) {
          MyTimerTask tt = new MyTimerTask();
          tt.entry = e;
          timer.schedule(tt, new Date(_timerTime));
          e.task = tt;
          _nextRefreshTime = -_nextRefreshTime;
        }
      } else {
        MyTimerTask tt = new MyTimerTask();
        tt.entry = e;
        timer.schedule(tt, new Date(_nextRefreshTime));
        e.task = tt;
      }
    } else {
    }
    return _nextRefreshTime;
  }

  /**
   * When the time has come remove the entry from the cache.
   */
  protected void timerEvent(final E e, long _executionTime) {
    /* checked below, if we do not go through a synchronized clause, we may see old data
    if (e.isRemovedFromReplacementList()) {
      return;
    }
    */
    if (refreshPool != null) {
      synchronized (e) {
        synchronized (lock) {
          timerEvents++;
          if (isClosed()) {
            return;
          }
          touchedTime = _executionTime;
          if (e.isRemovedState()) {
            return;
          }
          if (mainHashCtrl.remove(mainHash, e)) {
            refreshHash = refreshHashCtrl.insert(refreshHash, e);
            if (e.hashCode != modifiedHash(e.key.hashCode())) {
              if (!e.isRemovedState() && removeEntryFromHash(e)) {
                expiredRemoveCnt++;
              }
              return;
            }
            Runnable r = new Runnable() {
              @Override
              public void run() {
                long _previousNextRefreshTime;
                synchronized (e) {
                  if (e.isRemovedFromReplacementList() || e.isRemovedState() || e.isFetchInProgress()) {
                    return;
                  }
                  _previousNextRefreshTime = e.nextRefreshTime;
                  e.setGettingRefresh();
                }
                try {
                  long t = fetch(e, _previousNextRefreshTime);
                  finishFetch(e, t);
                } catch (CacheClosedException ignore) {
                } catch (Throwable ex) {
                  e.ensureFetchAbort(false);
                  synchronized (lock) {
                    internalExceptionCnt++;
                  }
                  getLog().warn("Refresh exception", ex);
                  try {
                    expireEntry(e);
                  } catch (CacheClosedException ignore) {
                  }
                }
              }
            };
            boolean _submitOkay = refreshPool.submit(r);
            if (_submitOkay) {
              return;
            }
            refreshSubmitFailedCnt++;
          } else { // if (mainHashCtrl.remove(mainHash, e)) ...
          }
        }
      }

    } else {
      synchronized (lock) {
        timerEvents++;
      }
    }
    synchronized (e) {
      long nrt = e.nextRefreshTime;
      if (nrt < Entry.EXPIRY_TIME_MIN) {
        return;
      }
      long t = System.currentTimeMillis();
      if (t >= e.nextRefreshTime) {
        try {
          expireEntry(e);
        } catch (CacheClosedException ignore) { }
      } else {
        e.nextRefreshTime = -e.nextRefreshTime;
      }
    }
  }

  protected void expireEntry(E e) {
    synchronized (e) {
      if (e.isRemovedState() || e.isExpiredState()) {
        return;
      }
      if (e.isFetchInProgress()) {
        e.nextRefreshTime = Entry.FETCH_IN_PROGRESS_NON_VALID;
        return;
      }
      e.setExpiredState();
      synchronized (lock) {
        checkClosed();
        if (hasKeepAfterExpired()) {
          expiredKeptCnt++;
        } else {
          if (removeEntry(e)) {
            expiredRemoveCnt++;
          }
        }
      }
    }
  }

  /**
   * Returns all cache entries within the heap cache. Entries that
   * are expired or contain no valid data are not filtered out.
   */
  final protected ClosableConcurrentHashEntryIterator<Entry> iterateAllHeapEntries() {
    return
      new ClosableConcurrentHashEntryIterator(
        mainHashCtrl, mainHash, refreshHashCtrl, refreshHash);
  }

  public abstract long getHitCnt();

  protected final int calculateHashEntryCount() {
    return Hash.calcEntryCount(mainHash) + Hash.calcEntryCount(refreshHash);
  }

  protected final int getLocalSize() {
    return mainHashCtrl.size + refreshHashCtrl.size;
  }

  public final int getTotalEntryCount() {
    synchronized (lock) {
      return getLocalSize();
    }
  }

  public long getExpiredCnt() {
    return expiredRemoveCnt + expiredKeptCnt;
  }

  /**
   * For peek no fetch is counted if there is a storage miss, hence the extra counter.
   */
  public long getFetchesBecauseOfNewEntries() {
    return fetchCnt - fetchButHitCnt;
  }

  protected int getFetchesInFlight() {
    long _fetchesBecauseOfNoEntries = getFetchesBecauseOfNewEntries();
    return (int) (newEntryCnt - putNewEntryCnt - virginEvictCnt
        - loadNonFreshCnt
        - loadHitCnt
        - _fetchesBecauseOfNoEntries
        - atomicOpNewEntryCnt
    );
  }

  protected IntegrityState getIntegrityState() {
    synchronized (lock) {
      return new IntegrityState()
        .checkEquals(
            "newEntryCnt - virginEvictCnt == " +
                "getFetchesBecauseOfNewEntries() + getFetchesInFlight() + putNewEntryCnt + loadNonFreshCnt + loadHitCnt + atomicOpNewEntryCnt",
            newEntryCnt - virginEvictCnt,
            getFetchesBecauseOfNewEntries() + getFetchesInFlight() + putNewEntryCnt + loadNonFreshCnt + loadHitCnt + atomicOpNewEntryCnt)
        .checkLessOrEquals("getFetchesInFlight() <= 100", getFetchesInFlight(), 100)
        .checkEquals("newEntryCnt == getSize() + evictedCnt + expiredRemoveCnt + removeCnt + clearedCnt", newEntryCnt, getLocalSize() + evictedCnt + expiredRemoveCnt + removedCnt + clearedCnt)
        .checkEquals("newEntryCnt == getSize() + evictedCnt + getExpiredCnt() - expiredKeptCnt + removeCnt + clearedCnt", newEntryCnt, getLocalSize() + evictedCnt + getExpiredCnt() - expiredKeptCnt + removedCnt + clearedCnt)
        .checkEquals("mainHashCtrl.size == Hash.calcEntryCount(mainHash)", mainHashCtrl.size, Hash.calcEntryCount(mainHash))
        .checkEquals("refreshHashCtrl.size == Hash.calcEntryCount(refreshHash)", refreshHashCtrl.size, Hash.calcEntryCount(refreshHash))
        .check("!!evictionNeeded | (getSize() <= maxSize)", !!evictionNeeded | (getLocalSize() <= maxSize));
    }
  }

  /** Check internal data structures and throw and exception if something is wrong, used for unit testing */
  public final void checkIntegrity() {
    synchronized (lock) {
      checkClosed();
      IntegrityState is = getIntegrityState();
      if (is.getStateFlags() > 0) {
        throw new CacheIntegrityError(is.getStateDescriptor(), is.getFailingChecks(), toString());
      }
    }
  }


  public final InternalCacheInfo getInfo() {
    synchronized (lock) {
      checkClosed();
      long t = System.currentTimeMillis();
      if (info != null &&
          (info.creationTime + info.creationDeltaMs * TUNABLE.minimumStatisticsCreationTimeDeltaFactor + TUNABLE.minimumStatisticsCreationDeltaMillis > t)) {
        return info;
      }
      info = generateInfo(t);
    }
    return info;
  }

  public final InternalCacheInfo getLatestInfo() {
    return generateInfo(System.currentTimeMillis());
  }

  private CacheBaseInfo generateInfo(long t) {
    synchronized (lock) {
      checkClosed();
      info = new CacheBaseInfo(this);
      info.creationTime = t;
      info.creationDeltaMs = (int) (System.currentTimeMillis() - t);
      return info;
    }
  }

  protected String getExtraStatistics() { return ""; }

  /**
   * Return status information. The status collection is time consuming, so this
   * is an expensive operation.
   */
  @Override
  public String toString() {
    synchronized (lock) {
      InternalCacheInfo fo = getLatestInfo();
      return "Cache{" + name + "}"
              + "(" + fo.toString() + ")";
    }
  }

  static class CollisionInfo {
    int collisionCnt; int collisionSlotCnt; int longestCollisionSize;
  }

  /**
   * This function calculates a modified hash code. The intention is to
   * "rehash" the incoming integer hash codes to overcome weak hash code
   * implementations. We expect good results for integers also.
   * Also add a random seed to the hash to protect against attacks on hashes.
   * This is actually a slightly reduced version of the java.util.HashMap
   * hash modification.
   */
  protected final int modifiedHash(int h) {
    h ^= hashSeed;
    h ^= h >>> 7;
    h ^= h >>> 15;
    return h;

  }

  protected class MyTimerTask extends TimerTask {
    E entry;

    public void run() {
      timerEvent(entry, scheduledExecutionTime());
    }
  }

  public static class Tunable extends TunableConstants {

    /**
     * Implementation class to use by default.
     */
    public Class<? extends BaseCache> defaultImplementation =
            "64".equals(System.getProperty("sun.arch.data.model"))
                    ? ClockProPlus64Cache.class : ClockProPlusCache.class;

    /**
     * Log exceptions from the source just as they happen. The log goes to the debug output
     * of the cache log, debug level of the cache log must be enabled also.
     */
    public boolean logSourceExceptions = false;

    public int waitForTimerJobsSeconds = 5;

    /**
     * Limits the number of spins until an entry lock is expected to
     * succeed. The limit is to detect deadlock issues during development
     * and testing. It is set to an arbitrary high value to result in
     * an exception after about one second of spinning.
     */
    public int maximumEntryLockSpins = 333333;

    /**
     * Maximum number of tries to find an entry for eviction if maximum size
     * is reached.
     */
    public int maximumEvictSpins = 5;

    /**
     * Size of the hash table before inserting the first entry. Must be power
     * of two. Default: 64.
     */
    public int initialHashSize = 64;

    /**
     * Fill percentage limit. When this is reached the hash table will get
     * expanded. Default: 64.
     */
    public int hashLoadPercent = 64;

    /**
     * The hash code will randomized by default. This is a countermeasure
     * against from outside that know the hash function.
     */
    public boolean disableHashRandomization = false;

    /**
     * Seed used when randomization is disabled. Default: 0.
     */
    public int hashSeed = 0;

    /**
     * When sharp expiry is enabled, the expiry timer goes
     * before the actual expiry to switch back to a time checking
     * scheme when the get method is invoked. This prevents
     * that an expired value gets served by the cache if the time
     * is too late. A recent GC should not produce more then 200
     * milliseconds stall. If longer GC stalls are expected, this
     * value needs to be changed. A value of LONG.MaxValue
     * suppresses the timer usage completely.
     */
    public long sharpExpirySafetyGapMillis = 666;

    /**
     * Some statistic values need processing time to gather and compute it. This is a safety
     * time delta, to ensure that the machine is not busy due to statistics generation. Default: 333.
     */
    public int minimumStatisticsCreationDeltaMillis = 333;

    /**
     *  Factor of the statistics creation time, that determines the time difference when new
     *  statistics are generated.
     */
    public int minimumStatisticsCreationTimeDeltaFactor = 123;

  }

}
