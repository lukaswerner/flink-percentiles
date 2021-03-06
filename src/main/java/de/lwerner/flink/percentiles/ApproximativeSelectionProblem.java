package de.lwerner.flink.percentiles;

import de.lwerner.flink.percentiles.algorithm.AbstractSelectionProblem;
import de.lwerner.flink.percentiles.data.SinkInterface;
import de.lwerner.flink.percentiles.data.SourceInterface;
import de.lwerner.flink.percentiles.functions.redis.SolveRemainingMapPartition;
import de.lwerner.flink.percentiles.model.Result;
import org.apache.flink.api.common.functions.MapPartitionFunction;
import org.apache.flink.api.common.operators.Order;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.tuple.Tuple1;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.util.Collector;

import java.util.Random;
import java.util.TreeMap;

/**
 * Calculates an approximative selection over a huge amount of values.
 *
 * @author Lukas Werner
 */
public class ApproximativeSelectionProblem extends AbstractSelectionProblem {

    /**
     * Should we use the sink?
     */
    private boolean useSink;

    /**
     * The sample size
     */
    private long sampleSize;

    /**
     * The result
     */
    private Result result;

    /**
     * ApproximativeSelectionProblem constructor, sets the required values
     *
     * @param source the data source
     * @param sink   the data sink
     * @param k      the rank
     * @param t      serial computation threshold
     */
    public ApproximativeSelectionProblem(SourceInterface source, SinkInterface sink, long k, long t) {
        this(source, sink, k, t, true);
    }

    /**
     * ApproximativeSelectionProblem constructor, sets the required values
     *
     * @param source  the data source
     * @param sink    the data sink
     * @param k       the rank
     * @param t       serial computation threshold
     * @param useSink use the sink?
     */
    public ApproximativeSelectionProblem(SourceInterface source, SinkInterface sink, long k, long t, boolean useSink) {
        super(source, sink, k, t);

        this.useSink = useSink;
    }

    /**
     * Get the sample size
     *
     * @return the sample size
     */
    private long getSampleSize() {
        return sampleSize;
    }

    /**
     * Set the sample size
     *
     * @param sampleSize the sample size to set
     */
    public void setSampleSize(long sampleSize) {
        this.sampleSize = sampleSize;
    }

    /**
     * Get the result
     *
     * @return the result
     */
    public Result getResult() {
        return result;
    }

    @Override
    public void solve() throws Exception {
        DataSet<Tuple1<Float>> solution = getSource()
                .getDataSet()
                .partitionByHash(0)
                .mapPartition(new GetRandomValuesMapPartitionFunction(getSampleSize()))
                .partitionByHash(0).setParallelism(1)
                .sortPartition(0, Order.ASCENDING).setParallelism(1)
                .mapPartition(new SolveRemainingMapPartition(getSource().getCount(), getK())).setParallelism(1);

        result = new Result();
        result.setSolution(solution);
        result.setK(getK());
        result.setT(getSampleSize());

        if (useSink) {
            getSink().processResult(result);
        }
    }

    /**
     * Add value to map if conditions are met
     *
     * @param map the map
     * @param value the value
     * @param maxMapSize the maximum map size
     */
    private static void conditionallyAddToMap(TreeMap<Float, Tuple2<Float, Float>> map, Tuple2<Float, Float> value, long maxMapSize) {
        if (map.size() < maxMapSize) {
            map.put(value.f1, value);
        } else if (map.firstKey() < value.f1) {
            map.remove(map.firstKey());
            map.put(value.f1, value);
        }
    }

    /**
     * The main application method, fetches execution environment, generates random values and executes the main
     * algorithm
     *
     * @param args the command line arguments
     *
     * @throws Exception if something goes wrong
     */
    public static void main(String[] args) throws Exception {
        ParameterTool params = ParameterTool.fromArgs(args);

        long k = Long.valueOf(params.getRequired("k"));
        long s = Long.valueOf(params.getRequired("sample-size"));

        ApproximativeSelectionProblem algorithm = factory(ApproximativeSelectionProblem.class, params, k);
        algorithm.setSampleSize(s);
        algorithm.solve();
    }

    /**
     * Map function to associate each value with a random float number and get top values
     *
     * @author Lukas Werner
     */
    private static final class GetRandomValuesMapPartitionFunction implements MapPartitionFunction<Tuple1<Float>, Tuple1<Float>> {

        /**
         * Random object
         */
        private final Random random;

        /**
         * The sample size
         */
        private long sampleSize;

        /**
         * Constructor to initialize random object
         *
         * @param sampleSize the sample size
         */
        public GetRandomValuesMapPartitionFunction(long sampleSize) {
            random = new Random();

            this.sampleSize = sampleSize;
        }

        @Override
        public void mapPartition(Iterable<Tuple1<Float>> values, Collector<Tuple1<Float>> out) {
            TreeMap<Float, Tuple2<Float, Float>> sortedMap = new TreeMap<>();

            for (Tuple1<Float> t: values) {
                Tuple2<Float, Float> newValue = new Tuple2<>(t.f0, (float)(random.nextDouble() * 1234569241));

                conditionallyAddToMap(sortedMap, newValue, sampleSize);
            }

            for (Tuple2<Float, Float> t: sortedMap.values()) {
                out.collect(new Tuple1<>(t.f0));
            }
        }
    }
}
