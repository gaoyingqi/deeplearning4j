package org.deeplearning4j.nn.layers.convolution;

import org.deeplearning4j.exception.DL4JInvalidInputException;
import org.deeplearning4j.nn.conf.ConvolutionMode;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.Convolution3D;
import org.deeplearning4j.nn.gradient.DefaultGradient;
import org.deeplearning4j.nn.gradient.Gradient;
import org.deeplearning4j.nn.params.Convolution3DParamInitializer;
import org.deeplearning4j.util.ConvolutionUtils;
import org.nd4j.linalg.activations.IActivation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.CustomOp;
import org.nd4j.linalg.api.ops.DynamicCustomOp;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.primitives.Pair;

import java.util.Arrays;

/**
 * 3D convolution layer implementation.
 *
 * @author Max Pumperla
 */
public class Convolution3DLayer extends ConvolutionLayer {

    public Convolution3DLayer(NeuralNetConfiguration conf) {
        super(conf);
    }

    public Convolution3DLayer(NeuralNetConfiguration conf, INDArray input) {
        super(conf, input);
    }


    @Override
    void initializeHelper() {
        // no op
    }

    @Override
    public Pair<Gradient, INDArray> backpropGradient(INDArray epsilon) {

        if (input.rank() != 5) {
            throw new DL4JInvalidInputException("Got rank " + input.rank()
                    + " array as input to SubsamplingLayer with shape " + Arrays.toString(input.shape())
                    + ". Expected rank 5 array with shape [minibatchSize, channels, "
                    + "inputHeight, inputWidth, inputDepth]. "
                    + layerId());
        }

        INDArray bias;
        INDArray weights = getParamWithNoise(Convolution3DParamInitializer.WEIGHT_KEY, true);

        int miniBatch = input.size(0);
        int inH = input.size(2);
        int inW = input.size(3);
        int inD = input.size(4);

        int outChannels = weights.size(0);

        int kH = weights.size(2);
        int kW = weights.size(3);
        int kD = weights.size(4);

        Convolution3D layerConfig = (Convolution3D) layerConf();

        int[] dilation = layerConfig.getDilation();
        int[] kernel = layerConfig.getKernelSize();
        int[] strides = layerConfig.getStride();
        int[] pad;
        int[] outSize;

        if (convolutionMode == ConvolutionMode.Same) {
            outSize = ConvolutionUtils.get3DOutputSize(input, kernel, strides, null, convolutionMode, dilation);
            pad = ConvolutionUtils.get3DSameModeTopLeftPadding(outSize, new int[]{inH, inW, inD}, kernel, strides, dilation);
        } else {
            pad = layerConfig.getPadding();
            outSize = ConvolutionUtils.get3DOutputSize(input, kernel, strides, pad, convolutionMode, dilation);
        }
        int outH = outSize[0];
        int outW = outSize[1];
        int outD = outSize[2];

        INDArray biasGradView = gradientViews.get(Convolution3DParamInitializer.BIAS_KEY);
        INDArray weightGradView = gradientViews.get(Convolution3DParamInitializer.WEIGHT_KEY);

        INDArray outEpsilon = Nd4j.create(miniBatch * outChannels * outH * outW * outD);
        INDArray reshapedEpsilon = outEpsilon.reshape('c', miniBatch, outChannels, outH, outW, outD);

        Integer sameMode = (convolutionMode == ConvolutionMode.Same) ? 1 : 0;

        int[] args = new int[]{
                kH, kW, kD, strides[0], strides[1], strides[2],
                pad[0], pad[1], pad[2], dilation[0], dilation[1], dilation[2], sameMode
        };

        INDArray delta;
        IActivation afn = layerConfig.getActivationFn();
        // TODO: Make sure this is 5D
        Pair<INDArray, INDArray> p = preOutput(true, true);
        delta = afn.backprop(p.getFirst(), epsilon).getFirst();

        CustomOp op;
        if (layerConfig.hasBias()) {

            bias = getParamWithNoise(Convolution3DParamInitializer.BIAS_KEY, true);

            op = DynamicCustomOp.builder("conv3d_bp")
                    .addInputs(input, weights, bias, delta)
                    .addIntegerArguments(args)
                    .addOutputs(reshapedEpsilon, weightGradView, biasGradView)
                    .callInplace(false)
                    .build();
        } else {
            op = DynamicCustomOp.builder("conv3d_bp")
                    .addInputs(input, weights, delta)
                    .addIntegerArguments(args)
                    .addOutputs(reshapedEpsilon, weightGradView)
                    .callInplace(false)
                    .build();
        }
        Nd4j.getExecutioner().exec(op);

        Gradient retGradient = new DefaultGradient();
        if (layerConfig.hasBias()) {
            retGradient.setGradientFor(Convolution3DParamInitializer.BIAS_KEY, biasGradView);
        }
        retGradient.setGradientFor(Convolution3DParamInitializer.WEIGHT_KEY, weightGradView, 'c');
        weightNoiseParams.clear();

        return new Pair<>(retGradient, reshapedEpsilon);
    }


    @Override
    public INDArray preOutput(boolean training) {
        return preOutput(training, false).getFirst();
    }

    protected Pair<INDArray, INDArray> preOutput(boolean training, boolean forBackprop) {

        Convolution3D layerConfig = (Convolution3D) layerConf();

        ConvolutionMode mode = layerConfig.getConvolutionMode();
        boolean isNCDHW = layerConfig.isNCDHW();

        INDArray weights = getParamWithNoise(Convolution3DParamInitializer.WEIGHT_KEY, training);

        if (input.rank() != 5) {
            String layerName = conf.getLayer().getLayerName();
            if (layerName == null)
                layerName = "(not named)";
            throw new DL4JInvalidInputException("Got rank " + input.rank()
                    + " array as input to Convolution3DLayer (layer name = " + layerName + ", layer index = "
                    + index + ") with shape " + Arrays.toString(input.shape()) + ". "
                    + "Expected rank 5 array with shape [minibatchSize, numChannels, inputHeight,"
                    + "inputWidth, inputDepth]."
                    + (input.rank() == 2
                    ? " (Wrong input type (see InputType.convolutionalFlat()) or wrong data type?)"
                    : "")
                    + " " + layerId());
        }


        int miniBatch = input.size(0);
        int inputChannels = isNCDHW ? input.size(1) : input.size(4);
        int inD = isNCDHW ? input.size(2) : input.size(1);
        int inH = isNCDHW ? input.size(3) : input.size(2);
        int inW = isNCDHW ? input.size(4) : input.size(3);

        int outChannels = isNCDHW ? weights.size(0) : weights.size(4);
        int inChannels = isNCDHW ? weights.size(1) : weights.size(3);

        if (inputChannels != inChannels) {
            String layerName = conf.getLayer().getLayerName();
            if (layerName == null)
                layerName = "(not named)";
            throw new DL4JInvalidInputException("Cannot do forward pass in Convolution3D layer (layer name = " + layerName
                    + ", layer index = " + index + "): input array depth does not match CNN layer configuration"
                    + " (data input depth = " + input.size(1)
                    + ", [minibatch, inputChannels, height, width, depth]="
                    + Arrays.toString(input.shape()) + "; expected" + " input channels = " + inChannels + ") "
                    + layerId());
        }


        int[] kernel = layerConfig.getKernelSize();
        int[] dilation = layerConfig.getDilation();
        int[] strides = layerConfig.getStride();

        int[] pad;
        int[] outSize;
        if (mode == ConvolutionMode.Same) {
            outSize = ConvolutionUtils.get3DOutputSize(
                    input, kernel, strides, null, convolutionMode, dilation, isNCDHW);
            int[] inSize =  new int[]{inD, inH, inW};
            pad = ConvolutionUtils.get3DSameModeTopLeftPadding(outSize,
                   inSize, kernel, strides, dilation);
        } else {
            pad = layerConfig.getPadding();
            outSize = ConvolutionUtils.get3DOutputSize(input, kernel, strides, pad, convolutionMode, dilation, isNCDHW);
        }
        int outH = outSize[0];
        int outW = outSize[1];
        int outD = outSize[2];

        INDArray output = Nd4j.create(miniBatch * outChannels * outH * outW * outD);
        INDArray reshapedOutput = output.reshape('c', miniBatch, outChannels, outH, outW, outD);

        int[] intArgs = new int[]{
                kernel[0], kernel[1], kernel[2],
                strides[0], strides[1], strides[2],
                pad[0], pad[1], pad[2],
                dilation[0], dilation[1], dilation[2],
                mode == ConvolutionMode.Same ? 0 : 1,
                isNCDHW ? 1 : 0
        };

        CustomOp op;
        if (layerConfig.hasBias()) {
            INDArray bias = getParamWithNoise(Convolution3DParamInitializer.BIAS_KEY, training);

            op = DynamicCustomOp.builder("conv3dnew")
                    .addInputs(input, weights, bias)
                    .addIntegerArguments(intArgs)
                    .addOutputs(reshapedOutput)
                    .callInplace(false)
                    .build();
        } else {
            op = DynamicCustomOp.builder("conv3dnew")
                    .addInputs(input, weights)
                    .addIntegerArguments(intArgs)
                    .addOutputs(reshapedOutput)
                    .callInplace(false)
                    .build();
        }
        Nd4j.getExecutioner().exec(op);

        return new Pair<>(reshapedOutput, null);
    }
}