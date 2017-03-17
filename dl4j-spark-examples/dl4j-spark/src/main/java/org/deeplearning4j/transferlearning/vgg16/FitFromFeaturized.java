package org.deeplearning4j.transferlearning.vgg16;

import com.beust.jcommander.Parameter;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.deeplearning4j.eval.Evaluation;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.distribution.NormalDistribution;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.modelimport.keras.InvalidKerasConfigurationException;
import org.deeplearning4j.nn.modelimport.keras.UnsupportedKerasConfigurationException;
import org.deeplearning4j.nn.modelimport.keras.trainedmodels.TrainedModelHelper;
import org.deeplearning4j.nn.modelimport.keras.trainedmodels.TrainedModels;
import org.deeplearning4j.nn.transferlearning.FineTuneConfiguration;
import org.deeplearning4j.nn.transferlearning.TransferLearning;
import org.deeplearning4j.nn.transferlearning.TransferLearningHelper;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.spark.api.TrainingMaster;
import org.deeplearning4j.spark.impl.graph.SparkComputationGraph;
import org.deeplearning4j.spark.impl.paramavg.ParameterAveragingTrainingMaster;
import org.deeplearning4j.transferlearning.vgg16.dataHelpers.FeaturizedPreSave;
import org.deeplearning4j.transferlearning.vgg16.dataHelpers.FlowerDataSetIteratorFeaturized;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.io.IOException;

/**
 * @author susaneraly on 3/10/17.
 *
 * Important:
 * Run the class "FeaturizePreSave" before attempting to run this. The outputs at the boundary of the frozen and unfrozen
 * vertices of a model are saved. These are referred to as "featurized" datasets in this description.
 * On a dataset of about 3000 images which is what is downloaded this can take "a while"
 *
 * Here we see how the transfer learning helper can be used to fit from a featurized datasets.
 * We attempt to train the same model architecture as the one in "EditLastLayerOthersFrozen".
 * Since the helper avoids the forward pass through the frozen layers we save on computation time when running multiple epochs.
 * In this manner, users can iterate quickly tweaking learning rates, weight initialization etc` to settle on a model that gives good results.
 */
@Slf4j
public class FitFromFeaturized {

    public static final String featureExtractionLayer = FeaturizedPreSave.featurizeExtractionLayer;
    protected static final long seed = 12345;
    protected static final int numClasses = 5;
    protected static final int nEpochs = 3;
    @Parameter(names = "-useSparkLocal", description = "Use spark local (helper for testing/running without spark submit)", arity = 1)
    private boolean useSparkLocal = true;

    @Parameter(names = "-batchSizePerWorker", description = "Number of examples to fit each worker with")
    private int batchSizePerWorker = 16;

    @Parameter(names = "-numEpochs", description = "Number of epochs for training")
    private int numEpochs = 15;
    public  void main(String [] args) throws IOException, InvalidKerasConfigurationException, UnsupportedKerasConfigurationException {

        //Import vgg
        //Note that the model imported does not have an output layer (check printed summary)
        //  nor any training related configs (model from keras was imported with only weights and json)
        TrainedModelHelper modelImportHelper = new TrainedModelHelper(TrainedModels.VGG16);
        log.info("\n\nLoading org.deeplearning4j.transferlearning.vgg16...\n\n");
        ComputationGraph vgg16 = modelImportHelper.loadModel();
        log.info(vgg16.summary());
        //Decide on a fine tune configuration to use.
        //In cases where there already exists a setting the fine tune setting will
        //  override the setting for all layers that are not "frozen".
        FineTuneConfiguration fineTuneConf = new FineTuneConfiguration.Builder()
            .learningRate(3e-5)
            .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
            .updater(Updater.NESTEROVS)
            .seed(seed)
            .build();

        //Construct a new model with the intended architecture and print summary
        ComputationGraph vgg16Transfer = new TransferLearning.GraphBuilder(vgg16)
            .fineTuneConfiguration(fineTuneConf)
            .setFeatureExtractor(featureExtractionLayer) //the specified layer and below are "frozen"
            .removeVertexKeepConnections("predictions") //replace the functionality of the final vertex
            .addLayer("predictions",
                       new OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                            .nIn(4096).nOut(numClasses)
                            .weightInit(WeightInit.DISTRIBUTION)
                            .dist(new NormalDistribution(0,0.2*(2.0/(4096 + numClasses)))) //This weight init dist gave better results than Xavier
                            .activation(Activation.SOFTMAX).build(),
                       "fc2")
            .build();

        //Instantiate the transfer learning helper to fit and output from the featurized dataset
        //The .unfrozenGraph() is the unfrozen subset of the computation graph passed in.
        //If using with a UI or a listener attach them directly to the unfrozenGraph instance
        //With each iteration updated params from unfrozenGraph are copied over to the original model
        TransferLearningHelper transferLearningHelper = new TransferLearningHelper(vgg16Transfer);
        log.info(transferLearningHelper.unfrozenGraph().summary());

        //Configuration for Spark training: see http://deeplearning4j.org/spark for explanation of these configuration options
        TrainingMaster tm = new ParameterAveragingTrainingMaster.Builder(batchSizePerWorker)    //Each DataSet object: contains (by default) 32 examples
            .averagingFrequency(5)
            .workerPrefetchNumBatches(2)            //Async prefetching: 2 examples per worker
            .batchSizePerWorker(batchSizePerWorker)
            .build();

        log.info(vgg16Transfer.summary());
        SparkConf sparkConf = new SparkConf();
        JavaSparkContext sc = new JavaSparkContext(sparkConf);

        SparkComputationGraph sparkComputationGraph = new SparkComputationGraph(sc,transferLearningHelper.unfrozenGraph(),tm);

        DataSetIterator trainIter = FlowerDataSetIteratorFeaturized.trainIterator();
        DataSetIterator testIter = FlowerDataSetIteratorFeaturized.testIterator();


        for (int epoch = 0; epoch < nEpochs; epoch++) {
            if (epoch == 0) {
                Evaluation eval = transferLearningHelper.unfrozenGraph().evaluate(testIter);
                log.info("Eval stats BEFORE fit.....");
                log.info(eval.stats()+"\n");
                testIter.reset();
            }
            int iter = 0;
            while (trainIter.hasNext()) {
                transferLearningHelper.fitFeaturized(trainIter.next());
                if (iter % 10 == 0) {
                    log.info("Evaluate model at iter " + iter + " ....");
                    Evaluation eval = transferLearningHelper.unfrozenGraph().evaluate(testIter);
                    log.info(eval.stats());
                    testIter.reset();
                }
                iter++;
            }
            trainIter.reset();
            log.info("Epoch #"+epoch+" complete");
        }
        log.info("Model build complete");
    }
}
