/* Reproducible HotSpot microbenchmark; this does not measure a Minecraft frame. */
package cn.minerealms.rigworks.ysm.runtime;

import cn.minerealms.rigworks.ysm.model.YsmChannel;
import cn.minerealms.rigworks.ysm.model.YsmKeyframe;
import cn.minerealms.rigworks.ysm.model.YsmTypedValue;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PowerShell: $env:RIGWORKS_YSM_BENCHMARK='1'
 * ./gradlew :1.20.1-forge:test --tests '*YsmSamplingBenchmarkTest' -PassimpLoaderDir=H:/MinecraftMods/AssimpLoader --rerun-tasks
 * Remove-Item Env:RIGWORKS_YSM_BENCHMARK
 * Results are printed and written under build/reports/ysm-sampling-benchmark-<pid>.json.
 */
@EnabledIfEnvironmentVariable(named = "RIGWORKS_YSM_BENCHMARK", matches = "1")
class YsmSamplingBenchmarkTest {
    private static final int SAMPLES = 500_000;
    private static final int WARMUP_ROUNDS = 5;
    private static final int MEASURE_ROUNDS = 5;
    private static volatile double blackhole;

    @Test
    void reportPreparedSamplingCpuAndAllocationAgainstOriginalImplementation() throws Exception {
        if (!(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported() || !bean.isCurrentThreadCpuTimeSupported())
            throw new IllegalStateException("This benchmark requires HotSpot thread CPU and allocated-byte counters");
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        if (!bean.isThreadCpuTimeEnabled()) bean.setThreadCpuTimeEnabled(true);
        long thread = Thread.currentThread().getId();
        var numeric = channel(256, false);
        var expression = channel(32, true);
        Workload[] cases = {
                new Workload("numeric-original", numeric, true), new Workload("numeric-prepared", numeric, false),
                new Workload("expression-original", expression, true), new Workload("expression-prepared", expression, false)
        };
        assertEquals(cases[0].run(4096), cases[1].run(4096), 0, "numeric benchmark paths disagree");
        assertEquals(cases[2].run(4096), cases[3].run(4096), 0, "expression benchmark paths disagree");
        for (int round = 0; round < WARMUP_ROUNDS; round++)
            for (Workload work : cases) blackhole = work.run(work.samples());

        double[][] wall = new double[cases.length][MEASURE_ROUNDS];
        double[][] cpu = new double[cases.length][MEASURE_ROUNDS];
        double[][] allocated = new double[cases.length][MEASURE_ROUNDS];
        double[] checksums = new double[cases.length];
        for (int round = 0; round < MEASURE_ROUNDS; round++) {
            for (int step = 0; step < cases.length; step++) {
                int index = round % 2 == 0 ? step : cases.length - 1 - step;
                long beforeBytes = bean.getThreadAllocatedBytes(thread);
                long beforeCpu = bean.getCurrentThreadCpuTime();
                long beforeWall = System.nanoTime();
                int count = cases[index].samples();
                checksums[index] = cases[index].run(count);
                wall[index][round] = (System.nanoTime() - beforeWall) / (double) count;
                cpu[index][round] = (bean.getCurrentThreadCpuTime() - beforeCpu) / (double) count;
                allocated[index][round] = (bean.getThreadAllocatedBytes(thread) - beforeBytes) / (double) count;
                blackhole = checksums[index];
            }
        }

        JsonObject report = new JsonObject();
        report.addProperty("description", "Warmed CPU keyframe sampling microbenchmark; not FPS or game frame time");
        report.addProperty("baseline_commit", "40dda30");
        report.addProperty("java_version", System.getProperty("java.version"));
        report.addProperty("java_vm", System.getProperty("java.vm.name"));
        report.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        report.addProperty("logical_processors", Runtime.getRuntime().availableProcessors());
        report.addProperty("warmup_rounds", WARMUP_ROUNDS);
        report.addProperty("measurement_rounds", MEASURE_ROUNDS);
        report.addProperty("note", "Prepared cases use four times as many samples to reduce OS thread CPU timer quantization");
        JsonArray measurements = new JsonArray();
        for (int index = 0; index < cases.length; index++) {
            JsonObject row = new JsonObject();
            row.addProperty("name", cases[index].name);
            row.addProperty("keyframes", cases[index].channel.frames().size());
            row.addProperty("samples_per_round", cases[index].samples());
            row.addProperty("median_wall_ns_per_sample", median(wall[index]));
            row.addProperty("median_cpu_ns_per_sample", median(cpu[index]));
            row.addProperty("median_allocated_bytes_per_sample", median(allocated[index]));
            row.addProperty("checksum_per_sample", checksums[index] / cases[index].samples());
            measurements.add(row);
            System.out.printf(Locale.ROOT, "%s keys=%d wall_ns=%.2f cpu_ns=%.2f allocated_bytes=%.4f checksum=%.8f%n",
                    cases[index].name, cases[index].channel.frames().size(), median(wall[index]), median(cpu[index]),
                    median(allocated[index]), checksums[index] / cases[index].samples());
        }
        report.add("measurements", measurements);
        Path output = Path.of("build/reports/ysm-sampling-benchmark-" + ProcessHandle.current().pid() + ".json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, report.toString());
        System.out.println("Benchmark report: " + output.toAbsolutePath());
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static YsmChannel channel(int keyframes, boolean expression) {
        List<YsmKeyframe> frames = new ArrayList<>();
        for (int i = 0; i < keyframes; i++) {
            List<YsmTypedValue> post = expression
                    ? List.of(YsmTypedValue.expression("math.sin(q.phase) * 12 + q.speed"),
                            YsmTypedValue.ofFloat(i % 7), YsmTypedValue.expression("q.speed * 2 + " + i))
                    : List.of(YsmTypedValue.ofFloat(i * 0.25f), YsmTypedValue.ofFloat(i % 7), YsmTypedValue.ofFloat(i % 11));
            frames.add(new YsmKeyframe(i * 0.05f, List.of(), post, "linear", i % 3 == 0 ? 2 : 0, false));
        }
        return new YsmChannel("benchmark_bone", "position", frames);
    }

    private static final class Workload {
        private final String name;
        private final YsmChannel channel;
        private final YsmKeyframeSampler.PreparedTrack prepared;
        private final boolean original;
        private final float[] out = new float[3];
        private final Queries queries = new Queries();

        private Workload(String name, YsmChannel channel, boolean original) {
            this.name = name;
            this.channel = channel;
            this.original = original;
            prepared = YsmKeyframeSampler.prepare(channel);
        }

        private double run(int count) {
            double sum = 0;
            double duration = channel.frames().size() * 0.05;
            for (int i = 0; i < count; i++) {
                queries.phase = i * 0.013;
                queries.speed = i % 7 * 0.125;
                double time = (i * 37 % 4096) * (duration / 4096);
                if (original) {
                    float[] value = LegacyYsmSamplingReference.sample(channel, time, queries);
                    sum += value[0] + value[1] + value[2];
                } else {
                    prepared.sample(time, queries, out);
                    sum += out[0] + out[1] + out[2];
                }
            }
            return sum;
        }

        private int samples() { return original ? SAMPLES : SAMPLES * 4; }
    }

    private static final class Queries implements YsmExpressionRuntime.Context {
        private double phase;
        private double speed;
        @Override public double value(String name) {
            return switch (name) {
                case "q.phase" -> phase;
                case "q.speed" -> speed;
                default -> 0;
            };
        }
        @Override public boolean has(String name) { return name.equals("q.phase") || name.equals("q.speed"); }
        @Override public boolean mayMatchFunction(String name) { return false; }
    }
}
