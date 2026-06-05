package com.jiashi.db.engine.math;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * VectorMath：SIMD 向量化 vs 纯标量 的基准测试。
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class VectorMathBenchmark {

    // 128: 小模型 / 768: 主流中文 embedding(BGE 等) / 1536: OpenAI text-embedding-ada-002
    @Param({"128", "768", "1536"})
    private int dim;

    private float[] v1;
    private float[] v2;

    @Setup(Level.Trial)
    public void setup() {
        Random r = new Random(42); // 固定种子，保证可复现
        v1 = new float[dim];
        v2 = new float[dim];
        for (int i = 0; i < dim; i++) {
            v1[i] = r.nextFloat();
            v2[i] = r.nextFloat();
        }
    }

    @Benchmark
    public void dotProductScalar(Blackhole bh) {
        bh.consume(VectorMath.dotProductScalar(v1, v2));
    }

    @Benchmark
    public void dotProductSimd(Blackhole bh) {
        bh.consume(VectorMath.dotProductSimd(v1, v2));
    }

    @Benchmark
    public void l2DistanceScalar(Blackhole bh) {
        bh.consume(VectorMath.l2DistanceSquareScalar(v1, v2));
    }

    @Benchmark
    public void l2DistanceSimd(Blackhole bh) {
        bh.consume(VectorMath.l2DistanceSquareSimd(v1, v2));
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(VectorMathBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}