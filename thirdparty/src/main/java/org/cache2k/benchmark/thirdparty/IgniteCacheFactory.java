package org.cache2k.benchmark.thirdparty;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.Ignition;
import org.cache2k.benchmark.BenchmarkCache;
import org.cache2k.benchmark.BenchmarkCacheFactory;

/**
 * Created by sbt-morozov-kv on 27.09.2016.
 */
public class IgniteCacheFactory extends BenchmarkCacheFactory {

    static final String CACHE_NAME = "testCache";
    static IgniteCache cache;
    static Ignite ignite;

    static synchronized IgniteCache getIgniteCache() {
        if (ignite == null)
            ignite = Ignition.ignite("testGrid");

        cache = ignite.getOrCreateCache(CACHE_NAME);

        return cache;
    }

    @Override
    public BenchmarkCache<Integer, Integer> create(int _maxElements) {
        return new MyBenchmarkCache(getIgniteCache());
    }

    static class MyBenchmarkCache extends BenchmarkCache<Integer, Integer> {

        IgniteCache<Integer, Integer> cache;

        MyBenchmarkCache(IgniteCache<Integer, Integer> cache) {
            this.cache = cache;
        }

        @Override
        public Integer getIfPresent(final Integer key) {
            return cache.get(key);
        }

        @Override
        public void put(Integer key, Integer value) {
            cache.put(key, value);
        }

        @Override
        public void destroy() {
            cache.destroy();
        }

        @Override
        public int getCacheSize() {
            return cache.localSize();
        }

        @Override
        public String getStatistics() {
            return cache.toString() + ": size=" + cache.size();
        }
    }
}
