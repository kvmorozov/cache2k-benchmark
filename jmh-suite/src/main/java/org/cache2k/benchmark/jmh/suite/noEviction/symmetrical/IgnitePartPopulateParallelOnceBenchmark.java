package org.cache2k.benchmark.jmh.suite.noEviction.symmetrical;

import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/**
 * Created by sbt-morozov-kv on 27.09.2016.
 */
@State(Scope.Benchmark)
public class IgnitePartPopulateParallelOnceBenchmark extends PopulateParallelOnceBenchmark {

    Ignite ignite;

    {
        if (ignite == null)
            ignite = Ignition.start("ignite/ignite-cache-part.xml");
    }

    @TearDown(Level.Trial)
    public void destroy() {
        if (ignite != null) {
            ignite.close();
            ignite = null;
        }
    }
}
