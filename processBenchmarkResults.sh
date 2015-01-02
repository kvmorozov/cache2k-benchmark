#!/bin/bash

# processBenchmarkResults.sh

# Copyright 2013 headissue GmbH, Jens Wilke

# This script processes the benchmark results into svg graphics. Needed tools: gnuplot and xml2. 
# 
# Run it with the following subcommands: 
#
# copyData    copy the data from the benchmark run to the target directory
# process     paint nice diagrams
# copyToSite  copy the diagrams and all results the the cache2k project site

RESULT="target/benchmark-result";
SITE="../cache2k/src/site/resources/benchmark-result";

# pivot "<impl>,<impl2>,..."
#
# Input format:
#
# benchmarkMostlyHit_6E|org.cache2k.benchmark.LruCacheBenchmark|0.596
# benchmarkMostlyHit_6E|org.cache2k.benchmark.LruSt030709CacheBenchmark|0.248
# benchmarkMostlyHit_6E|org.cache2k.benchmark.RecentDefaultCacheBenchmark|0.573
# benchmarkRandom_6E|org.cache2k.benchmark.ArcCacheBenchmark|0.169
# benchmarkRandom_6E|org.cache2k.benchmark.ClockCacheBenchmark|0.138
# . . .

# Output format:
#
# benchmarkAllMiss_6E 0.207 0.403 
# benchmarkEffective90_6E 0.047 0.074 
# benchmarkEffective95_6E 0.04 0.059 
#

pivot() {
local cols="$1";
shift;
while [ "$1" != "" ]; do
  cols="${cols},$1";
  shift;
done
awk -v cols="$cols" "$pivot_awk";
}

pivot_awk=`cat <<"EOF"
BEGIN { FS="|";
  split(cols, colKeys, ",");
}
  
  row!=$1 { flushRow(); row=$1; for (i in data) delete data[i]; }
  { data[$2]=$3; }

END { flushRow(); }

function flushRow() {
 if (row == "") return;
 printf ("%s ", row);
 for (k in colKeys) {
   key=colKeys[k];
   printf ("%s ", data[key]);
 }
 printf "\n";
}
EOF
`

# renameBenchmarks
#
# Strip benchmark from the name.
#
cleanName() {
  awk '{ sub(/benchmark/, "", $1); print; }';
}

copyData() {
test -d $RESULT || mkdir -p $RESULT;
cp thirdparty/target/junit-benchmark.xml $RESULT/thirdparty-junit-benchmark.xml;
cp zoo/target/junit-benchmark.xml $RESULT/zoo-junit-benchmark.xml;
cp thirdparty/target/cache2k-benchmark-result.csv $RESULT/thirdparty-cache2k-benchmark-result.csv;
cp zoo/target/cache2k-benchmark-result.csv $RESULT/zoo-cache2k-benchmark-result.csv;
}

copyToSite() {
test -d "$SITE" || mkdir -p "$SITE";
cp "$RESULT"/* "$SITE"/;
}

printJubCsv() {
for I in $RESULT/*-junit-benchmark.xml; do
  xml2 < $I | 2csv -d"|" testname @name @classname @round-avg @round-stddev @benchmark-rounds
done
}

printCacheCsv() {
for I in $RESULT/*-cache2k-benchmark-result.csv; do
  cat $I;
done
}

onlySpeed() {
grep -v "^benchmark.*_.*" | grep -v "^test"
}

stripEmpty() {
awk 'NF > 1 { print; }';
}

maxYRange_awk=`cat <<"EOF"
NR==1 { next; } 
{ for (i=2; i<=NF;i++) if ($i>range) range=$i; } 
END { 
  range=range*1.1; 
  if (range > 100) { print 100; } else { print range; }
}
EOF`

maxYRange() {
awk "$maxYRange_awk";
}

plot() {
local in="$1";
local out="`dirname "$in"`/`basename "$in" .dat`.svg";
local title="$2";
local yTitle="$3";
local xTitle="$4";
local maxYRange=`maxYRange < "$in"`;
(
echo "set terminal svg"
echo "set output '$out'"
echo "set boxwidth 0.9 absolute";
echo "set style fill solid 1.00 border lt -1";
echo "set key outside right top vertical Right noreverse noenhanced autotitles nobox";
echo "set style histogram clustered gap 2 title  offset character 0, 0, 0";
echo "set datafile missing '-'";
echo "set style data histograms";
echo "set xtics border in scale 0,0 nomirror rotate by -45  offset character 0, 0, 0 autojustify";
echo 'set xtics  norangelimit font "1"';
echo "set xtics   ()"
test -z "$xTitle" || echo "set xlabel '${xTitle}'";
test -z "$yTitle" || echo "set ylabel '${yTitle}'";
echo "set title '$title'";
echo "set yrange [ 0.0 : $maxYRange ] noreverse nowriteback";
# echo "i = 22";
# echo "plot '$in' using 2:xtic(1) ti col, '' u 3 ti col, '' u 4 ti col";
#if [ "`cat $in | wc -l`" -lt 3 ]; then
#  echo  -n "plot '$in' using 2 ti col";
#else
  echo  -n "plot '$in' using 2:xtic(1) ti col";
#fi
cols=$(( `head -n 1 "$in" | wc -w` ));
  idx=3;
  while [ $idx -le $cols ]; do
    echo -n ", '' u $idx ti col";
    idx=$(( $idx + 1 ));
  done
  echo ""; 
) > "$in".plot
gnuplot "$in".plot;
}

# printOptHitrate
#
# In column 7 of the results there is the optimum hitrate for the trace.
# Extract it and print it out as cache implementation.
printOptHitrate() {
  printCacheCsv | awk -F\| '{ print $1"|OPT|"$7"|"$4; }' | sort | uniq
}

printRandomHitrate() {
  printCacheCsv | awk -F\| '{ print $1"|RAND|"$8"|"$4; }' | sort | uniq
}

alongSize() {
  awk -F\| '{ print $4"|"$2"|"$3; }';
}

printHitrate() {
  printCacheCsv;
  printOptHitrate;
  printRandomHitrate;
}

process() {
rm -rf $RESULT/*.dat;
rm -rf $RESULT/*.svg;
rm -rf $RESULT/*.plot;

f=$RESULT/speedHits.dat;
(
echo Benchmark HashMap+Counter cache2k/CLOCK cache2k/CP+ cache2k/ARC EHCache Infinispan Guava;
printJubCsv | onlySpeed | grep "^benchmarkHits" | cleanName | sort | \
  pivot org.cache2k.benchmark.HashMapBenchmark \
        org.cache2k.benchmark.ClockCacheBenchmark \
        org.cache2k.benchmark.ClockProPlusCacheBenchmark \
        org.cache2k.benchmark.ArcCacheBenchmark \
        org.cache2k.benchmark.thirdparty.EhCacheBenchmark \
        org.cache2k.benchmark.thirdparty.InfinispanCacheBenchmark \
        org.cache2k.benchmark.thirdparty.GuavaCacheBenchmark | \
  stripEmpty
) > $f
plot $f "Runtime of 3 million cache hits" "runtime in seconds"

f=$RESULT/3ptySpeed.dat;
(
echo Benchmark cache2k/CLOCK cache2k/CP+ cache2k/ARC EHCache Infinispan Guava;
printJubCsv | onlySpeed | cleanName | sort | \
  pivot org.cache2k.benchmark.ClockCacheBenchmark \
        org.cache2k.benchmark.ClockProPlusCacheBenchmark \
        org.cache2k.benchmark.ArcCacheBenchmark \
        org.cache2k.benchmark.thirdparty.EhCacheBenchmark \
        org.cache2k.benchmark.thirdparty.InfinispanCacheBenchmark \
        org.cache2k.benchmark.thirdparty.GuavaCacheBenchmark | \
  stripEmpty
) > $f
plot $f "Runtime of 3 million cache requests" "runtime in seconds"

f=$RESULT/3ptyHitrate.dat;
(
echo Benchmark cache2k/CLOCK cache2k/CP+ cache2k/ARC EHCache Infinispan Guava;
printCacheCsv | onlySpeed | cleanName | sort | \
  pivot org.cache2k.benchmark.ClockCacheBenchmark \
	org.cache2k.benchmark.ClockProPlusCacheBenchmark \
        org.cache2k.benchmark.ArcCacheBenchmark \
        org.cache2k.benchmark.thirdparty.EhCacheBenchmark \
        org.cache2k.benchmark.thirdparty.InfinispanCacheBenchmark \
        org.cache2k.benchmark.thirdparty.GuavaCacheBenchmark | \
  stripEmpty
) > $f
plot $f "Hitrate of 3 million cache requests" "runtime in seconds"

f=$RESULT/3ptySpeedWithExpiry.dat;
(
echo Benchmark cache2k/CLOCK cache2k/CP+ cache2k/ARC EHCache Infinispan Guava;
printJubCsv | onlySpeed | cleanName | sort | \
  pivot org.cache2k.benchmark.ClockCacheWithExpiryBenchmark \
	org.cache2k.benchmark.ClockProPlusCacheWithExpiryBenchmark \
        org.cache2k.benchmark.ArcCacheWithExpiryBenchmark \
        org.cache2k.benchmark.thirdparty.EhCacheWithExpiryBenchmark \
        org.cache2k.benchmark.thirdparty.InfinispanCacheWithExpiryBenchmark \
        org.cache2k.benchmark.thirdparty.GuavaCacheWithExpiryBenchmark | \
  stripEmpty
) > $f
plot $f "Runtime of 3 million cache requests with entry expiry" "runtime in seconds"


header="Size OPT LRU CLOCK CP+ ARC EHCache Infinispan Guava RAND";
impls="OPT \
	org.cache2k.benchmark.LruCacheBenchmark \
        org.cache2k.benchmark.ClockCacheBenchmark \
        org.cache2k.benchmark.ClockProPlusCacheBenchmark \
        org.cache2k.benchmark.ArcCacheBenchmark \
        org.cache2k.benchmark.thirdparty.EhCacheBenchmark \
        org.cache2k.benchmark.thirdparty.InfinispanCacheBenchmark \
        org.cache2k.benchmark.thirdparty.GuavaCacheBenchmark \
        RAND";
for I in Web07 Web12 Cpp Sprite Multi2; do
  f=$RESULT/trace${I}hitrate.dat;
  (
  echo $header;
  printHitrate | grep "^benchmark${I}_" | alongSize | sort -n -k1 -t'|' | \
    pivot $impls | \
    stripEmpty
  ) > $f
  plot $f "Hitrates for $I trace";
done
}

if test -z "$1"; then
  echo "Run with: processBenchmarkResults.sh copyData | process | copyToSite";
else
 "$@";
fi
