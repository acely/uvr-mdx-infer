package com.example.audioseparator;

import ai.onnxruntime.*; // For OnnxTensor, OnnxValue, OrtEnvironment, OrtException, OrtSession
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AudioSeparator implements AutoCloseable {

    private final OrtEnvironment env;
    private final OrtSession session;
    private final AudioProcessor audioProcessor;
    private final Map<String, Object> args;

    // Configuration fields
    private final int marginSamples; // Renamed from 'margin' to be specific about units
    private final int chunkSeconds;
    private final boolean denoiseEnabled;
    private final float sampleRate;
    private final int modelChunkSizeInSamples; // Renamed from chunkSizeInSamples for clarity
    private final String modelInputName;

    private static final Logger logger = LoggerFactory.getLogger(AudioSeparator.class);

    public AudioSeparator(Map<String, Object> args, AudioProcessor audioProcessor) {
        this.args = args;
        this.audioProcessor = audioProcessor;

        this.sampleRate = ((Number) this.args.getOrDefault("sample_rate", 44100.0f)).floatValue();
        // Default margin of 1.0 second * sampleRate, cast to int
        this.marginSamples = ((Number) this.args.getOrDefault("margin", 1.0f * this.sampleRate)).intValue();
        this.chunkSeconds = ((Number) this.args.getOrDefault("chunks", 45)).intValue(); // Default to 45s, matching python GUI
        this.denoiseEnabled = (boolean) this.args.getOrDefault("denoise", false); // Default to false, matching python CLI

        if (this.chunkSeconds <= 0) {
            this.modelChunkSizeInSamples = Integer.MAX_VALUE; // Indicates processing whole file as one chunk
            logger.info("Chunking disabled (chunkSeconds <= 0). The entire audio will be processed as a single segment.");
        } else {
            this.modelChunkSizeInSamples = this.chunkSeconds * (int) this.sampleRate;
        }

        logger.info("AudioSeparator configured with: sampleRate={}, marginSamples={}, chunkSeconds={}, denoiseEnabled={}, modelChunkSizeInSamples={}",
                this.sampleRate, this.marginSamples, this.chunkSeconds, this.denoiseEnabled, (this.modelChunkSizeInSamples == Integer.MAX_VALUE ? "MAX (Full Track)" : this.modelChunkSizeInSamples));

        String modelPath = (String) this.args.get("model_path");
        if (modelPath == null || modelPath.isEmpty()) {
            logger.error("Model path is null or empty. Please provide a valid model_path in the arguments.");
            throw new IllegalArgumentException("Model path is required.");
        }

        try {
            this.env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();
            logger.info("Loading ONNX model from path: {}", modelPath);
            this.session = env.createSession(modelPath, sessionOptions);

            if (this.session.getInputNames().isEmpty()) {
                throw new OrtException("Model has no input names defined.");
            }
            this.modelInputName = this.session.getInputInfo().keySet().iterator().next();
            logger.info("ONNX model loaded successfully. Input name: '{}', Output names: {}",
                    this.modelInputName, this.session.getOutputNames());

        } catch (OrtException e) {
            logger.error("Failed to load ONNX model or initialize ONNX Runtime from path: {}", modelPath, e);
            throw new RuntimeException("Failed to initialize AudioSeparator due to ONNX Runtime error", e);
        } catch (Exception e) {
            logger.error("An unexpected error occurred during AudioSeparator initialization with model path: {}", modelPath, e);
            throw new RuntimeException("Unexpected error during AudioSeparator initialization", e);
        }
    }

    public List<float[][]> separate(float[][] fullMixAudio) {
        logger.info("Starting audio separation process for audio of length: {} samples.", fullMixAudio[0].length);
        int totalOriginalSamples = fullMixAudio[0].length;
        int numChannels = fullMixAudio.length;

        if (numChannels != 2) {
            throw new IllegalArgumentException("Input audio must be stereo (2 channels). Got: " + numChannels);
        }

        Map<Long, float[][]> segmentedMix = new LinkedHashMap<>();
        int currentProcessingChunkSize = (this.chunkSeconds <= 0 || totalOriginalSamples <= this.modelChunkSizeInSamples) ?
                                         totalOriginalSamples : this.modelChunkSizeInSamples;
        
        if (currentProcessingChunkSize == totalOriginalSamples) {
             logger.info("Processing the entire audio as a single chunk (length: {} samples).", totalOriginalSamples);
        } else {
            logger.info("Segmenting audio into processing chunks of {} samples with margin of {} samples.", currentProcessingChunkSize, marginSamples);
        }

        for (long skip = 0; skip < totalOriginalSamples; skip += currentProcessingChunkSize) {
            long sMargin = (skip == 0) ? 0 : marginSamples; // No left margin for the very first chunk
            long startSampleWithMargin = Math.max(0, skip - sMargin);
            long endSampleWithMargin = Math.min(totalOriginalSamples, skip + currentProcessingChunkSize + marginSamples);
            int segmentLengthWithMargin = (int) (endSampleWithMargin - startSampleWithMargin);

            float[][] segment = new float[numChannels][segmentLengthWithMargin];
            for (int ch = 0; ch < numChannels; ++ch) {
                System.arraycopy(fullMixAudio[ch], (int)startSampleWithMargin, segment[ch], 0, segmentLengthWithMargin);
            }
            segmentedMix.put(skip, segment); // Key is the original start of the chunk *without* margin
        }
        
        float[][] vocals = processSegmentedMix(segmentedMix, totalOriginalSamples, currentProcessingChunkSize);

        float[][] noVocals = new float[numChannels][totalOriginalSamples];
        for (int ch = 0; ch < numChannels; ++ch) {
            for (int i = 0; i < totalOriginalSamples; ++i) {
                noVocals[ch][i] = fullMixAudio[ch][i] - vocals[ch][i];
            }
        }
        
        List<float[][]> result = new ArrayList<>();
        result.add(vocals);
        result.add(noVocals);
        logger.info("Audio separation process completed.");
        return result;
    }

    private float[][] processSegmentedMix(Map<Long, float[][]> segmentedMix, int totalOriginalSamples, int processingChunkSize) {
        logger.debug("Processing {} audio segments.", segmentedMix.size());
        float[][] finalVocals = new float[2][totalOriginalSamples];
        float[][] sumSquareWindow = new float[2][totalOriginalSamples]; // For weighted overlap-add

        // Precompute a Hann window for overlap-add blending if margin > 0
        float[] overlapAddWindow = (marginSamples > 0) ? audioProcessor.computeHannWindow(marginSamples * 2) : null;


        for (Map.Entry<Long, float[][]> entry : segmentedMix.entrySet()) {
            long segmentOriginalStartSample = entry.getKey();
            float[][] segmentWithMargin = entry.getValue();

            int originalLengthOfThisSegmentBeforeMargin = (int) Math.min(processingChunkSize, totalOriginalSamples - segmentOriginalStartSample);
            
            logger.debug("Processing segment starting at sample {}, original length before margin: {}, segment data length with margin: {}", 
                segmentOriginalStartSample, originalLengthOfThisSegmentBeforeMargin, segmentWithMargin[0].length);

            float[][] processedVocalsForSegmentWithMargin = demixChunk(segmentWithMargin); 

            int copyFromStartInProcessed = (segmentOriginalStartSample == 0) ? 0 : this.marginSamples;
            int copyToEndInProcessed = (segmentOriginalStartSample + originalLengthOfThisSegmentBeforeMargin >= totalOriginalSamples) ?
                                       processedVocalsForSegmentWithMargin[0].length :
                                       processedVocalsForSegmentWithMargin[0].length - this.marginSamples;
            
            int samplesToCopyFromThisSegment = copyToEndInProcessed - copyFromStartInProcessed;

            int writeToStartInFinal = (int) segmentOriginalStartSample;

            if (samplesToCopyFromThisSegment <= 0) {
                logger.warn("Segment at {} resulted in <=0 samples to copy. Skipping.", segmentOriginalStartSample);
                continue;
            }
            if (writeToStartInFinal + samplesToCopyFromThisSegment > totalOriginalSamples) {
                samplesToCopyFromThisSegment = totalOriginalSamples - writeToStartInFinal; // Trim if it overruns
            }


            for (int ch = 0; ch < 2; ++ch) {
                for (int i = 0; i < samplesToCopyFromThisSegment; ++i) {
                    int readIdx = copyFromStartInProcessed + i;
                    int writeIdx = writeToStartInFinal + i;

                    float windowVal = 1.0f;
                    if (overlapAddWindow != null) {
                        // Determine if we are in a left or right margin overlap region
                        if (i < marginSamples && segmentOriginalStartSample != 0) { // Left overlap (not first chunk)
                            windowVal = overlapAddWindow[i];
                        } else if (i >= originalLengthOfThisSegmentBeforeMargin - marginSamples && 
                                   (segmentOriginalStartSample + originalLengthOfThisSegmentBeforeMargin) < totalOriginalSamples) { // Right overlap (not last part of audio)
                             // Index into the second half of the Hann window
                            windowVal = overlapAddWindow[marginSamples + (i - (originalLengthOfThisSegmentBeforeMargin - marginSamples))];
                        }
                        // If not in overlap, windowVal remains 1.0 implicitly for the center part
                        // or if it's the very start/end of the whole audio.
                    }
                    
                    finalVocals[ch][writeIdx] += processedVocalsForSegmentWithMargin[ch][readIdx] * windowVal;
                    sumSquareWindow[ch][writeIdx] += windowVal * windowVal;
                }
            }
            logger.debug("Applied segment (orig_start: {}) to finalVocals. Copied {} samples from processed (start_idx: {}) to final (start_idx: {}).",
                segmentOriginalStartSample, samplesToCopyFromThisSegment, copyFromStartInProcessed, writeToStartInFinal);
        }

        // Normalize the overlap-added regions
        for (int ch = 0; ch < 2; ++ch) {
            for (int i = 0; i < totalOriginalSamples; ++i) {
                if (sumSquareWindow[ch][i] > 1e-8f) { // Avoid division by zero or very small numbers
                    finalVocals[ch][i] /= sumSquareWindow[ch][i];
                }
            }
        }
        return finalVocals;
    }
    
    private float[][] demixChunk(float[][] segmentWithMargin) {
        final int n_fft = audioProcessor.getN_fft();
        final int hop_length = audioProcessor.getHop_length();
        final int dim_t_model = audioProcessor.getDim_t();
        final int dim_f_model = audioProcessor.getDim_f();
        final int stftModelProcessingChunkSize = hop_length * (dim_t_model - 1);
        final int stftInternalTrim = n_fft / 2;
        final int stftContentOutputSizePerBlock = stftModelProcessingChunkSize; // Output of ISTFT will be this size

        int currentSamplesInSegment = segmentWithMargin[0].length;
        
        // Pad the segmentWithMargin so its length is suitable for STFT processing in blocks
        // The python code's `pad = gen_size - (mix_waves_length % gen_size)` and then padding with `trim` on both sides
        // effectively means the signal fed to the loop is `trim + mix_waves + pad + trim`.
        // The loop then processes `stftModelProcessingChunkSize` at a time, with step `gen_size`.
        // `gen_size` in python is `stftModelProcessingChunkSize - 2 * trim`.
        
        final int pythonGenSize = stftModelProcessingChunkSize - 2 * stftInternalTrim;
        if (pythonGenSize <= 0) {
             throw new IllegalStateException("Calculated pythonGenSize (stftModelProcessingChunkSize - 2 * stftInternalTrim) must be positive.");
        }

        int padForPythonGenSize = (pythonGenSize - (currentSamplesInSegment % pythonGenSize)) % pythonGenSize;
        int paddedInputLengthForLoop = stftInternalTrim + currentSamplesInSegment + padForPythonGenSize + stftInternalTrim;
        
        float[][] fullyPaddedSegment = new float[2][paddedInputLengthForLoop];
        for(int ch=0; ch<2; ++ch) {
            System.arraycopy(segmentWithMargin[ch], 0, fullyPaddedSegment[ch], stftInternalTrim, currentSamplesInSegment);
        }
        logger.debug("DemixChunk: segment_len={}, pythonGenSize={}, padForGenSize={}, loop_input_len={}, model_proc_chunk_size={}", 
            currentSamplesInSegment, pythonGenSize, padForPythonGenSize, paddedInputLengthForLoop, stftModelProcessingChunkSize);

        List<float[][]> processedSubChunksAudio = new ArrayList<>();

        for (int i = 0; i <= paddedInputLengthForLoop - stftModelProcessingChunkSize; i += pythonGenSize) {
            float[][] subChunkForStft = new float[2][stftModelProcessingChunkSize];
            for (int ch = 0; ch < 2; ++ch) {
                System.arraycopy(fullyPaddedSegment[ch], i, subChunkForStft[ch], 0, stftModelProcessingChunkSize);
            }

            float[][][][] spectrogram = audioProcessor.stft(subChunkForStft); // Output: [ch][re/im][freq][time]
            OnnxTensor inputTensor = spectrogramToOnnxTensor(spectrogram, dim_f_model, dim_t_model);
            
            float[][][][] resultSpec;
            try (OrtSession.Result result = session.run(Collections.singletonMap(modelInputName, inputTensor))) {
                OnnxValue outputValue = result.get(0);
                resultSpec = onnxValueTo4DSpec(outputValue, dim_f_model, dim_t_model); // [1][4][freq][time]
                
                if (denoiseEnabled) {
                    float[][][][] negativeModelInputSpec = negateSpectrogram(spectrogramToArrayForModel(spectrogram, dim_f_model, dim_t_model));
                    OnnxTensor negativeInputTensor = floatBufferToOnnxTensor(FloatBuffer.wrap(flatten4DArray(negativeModelInputSpec)),
                                                                            new long[]{1, 4, dim_f_model, dim_t_model});
                    try (OrtSession.Result denoisedResult = session.run(Collections.singletonMap(modelInputName, negativeInputTensor))) {
                        float[][][][] denoisedSpec = onnxValueTo4DSpec(denoisedResult.get(0), dim_f_model, dim_t_model);
                        for(int b=0; b<1; ++b) for(int c=0; c<4; ++c) for(int f=0; f<dim_f_model; ++f) for(int t_idx=0; t_idx<dim_t_model; ++t_idx) {
                             resultSpec[b][c][f][t_idx] = (resultSpec[b][c][f][t_idx] - denoisedSpec[b][c][f][t_idx]) * 0.5f;
                        }
                    } finally {
                        negativeInputTensor.close();
                    }
                }
            } catch (OrtException e) {
                throw new RuntimeException("ONNX inference failed.", e);
            } finally {
                inputTensor.close();
            }

            float[][][][] istftInputSpectrogram = modelOutputToIstftInput(resultSpec, dim_f_model, dim_t_model);
            float[][] processedSubChunkAudio = audioProcessor.istft(istftInputSpectrogram, stftModelProcessingChunkSize);

            float[][] trimmedSubChunk = new float[2][pythonGenSize];
            for (int ch = 0; ch < 2; ++ch) {
                 System.arraycopy(processedSubChunkAudio[ch], stftInternalTrim, trimmedSubChunk[ch], 0, pythonGenSize);
            }
            processedSubChunksAudio.add(trimmedSubChunk);
        }
        
        float[][] concatenatedAudio = new float[2][currentSamplesInSegment];
        int currentWritePos = 0;
        for (float[][] subChunk : processedSubChunksAudio) {
            int lengthToCopy = Math.min(subChunk[0].length, currentSamplesInSegment - currentWritePos);
            if (lengthToCopy <= 0) break;
            for (int ch = 0; ch < 2; ++ch) {
                System.arraycopy(subChunk[ch], 0, concatenatedAudio[ch], currentWritePos, lengthToCopy);
            }
            currentWritePos += lengthToCopy;
        }
        logger.debug("Demixing chunk finished. Output length: {}", concatenatedAudio[0].length);
        return concatenatedAudio;
    }

    private float[][][][] spectrogramToArrayForModel(float[][][][] spec, int dim_f, int dim_t) {
        // Input: [2][2][dim_f][dim_t] (ch, real/imag, freq, time)
        // Output: [1][4][dim_f][dim_t] (batch, LRe,LIm,RRe,RIm, freq, time)
        float[][][][] modelInput = new float[1][4][dim_f][dim_t];
        for (int f = 0; f < dim_f; f++) {
            for (int t_idx = 0; t_idx < dim_t; t_idx++) {
                modelInput[0][0][f][t_idx] = spec[0][0][f][t_idx]; // L_Real
                modelInput[0][1][f][t_idx] = spec[0][1][f][t_idx]; // L_Imag
                modelInput[0][2][f][t_idx] = spec[1][0][f][t_idx]; // R_Real
                modelInput[0][3][f][t_idx] = spec[1][1][f][t_idx]; // R_Imag
            }
        }
        return modelInput;
    }
    
    private float[][][][] negateSpectrogram(float[][][][] spec) {
        float[][][][] negated = new float[spec.length][spec[0].length][spec[0][0].length][spec[0][0][0].length];
        for(int i=0; i<spec.length; ++i) for(int j=0; j<spec[0].length; ++j) for(int k=0; k<spec[0][0].length; ++k) for(int l=0; l<spec[0][0][0].length; ++l) {
            negated[i][j][k][l] = -spec[i][j][k][l];
        }
        return negated;
    }


    private OnnxTensor spectrogramToOnnxTensor(float[][][][] spec, int dim_f, int dim_t) {
        // Input: [2][2][dim_f][dim_t] (ch, real/imag, freq, time)
        // Output: ONNXTensor for [1][4][dim_f][dim_t]
        float[][][][] modelInputArray = spectrogramToArrayForModel(spec, dim_f, dim_t);
        try {
            return floatBufferToOnnxTensor(FloatBuffer.wrap(flatten4DArray(modelInputArray)),
                                       new long[]{1, 4, dim_f, dim_t});
        } catch (OrtException e) {
            throw new RuntimeException("Failed to create ONNX tensor from spectrogram", e);
        }
    }
    
    private float[][][][] modelOutputToIstftInput(float[][][][] modelOutputSpec, int dim_f, int dim_t) {
        // Input: [1][4][dim_f][dim_t] (batch, LRe,LIm,RRe,RIm, freq, time)
        // Output: [2][2][dim_f][dim_t] (ch, real/imag, freq, time) for ISTFT
        float[][][][] istftInput = new float[2][2][dim_f][dim_t];
        for (int f = 0; f < dim_f; f++) {
            for (int t_idx = 0; t_idx < dim_t; t_idx++) {
                istftInput[0][0][f][t_idx] = modelOutputSpec[0][0][f][t_idx]; // L_Real
                istftInput[0][1][f][t_idx] = modelOutputSpec[0][1][f][t_idx]; // L_Imag
                istftInput[1][0][f][t_idx] = modelOutputSpec[0][2][f][t_idx]; // R_Real
                istftInput[1][1][f][t_idx] = modelOutputSpec[0][3][f][t_idx]; // R_Imag
            }
        }
        return istftInput;
    }


    private float[] flatten4DArray(float[][][][] array) {
        int d1 = array.length;
        int d2 = array[0].length;
        int d3 = array[0][0].length;
        int d4 = array[0][0][0].length;
        float[] flat = new float[d1 * d2 * d3 * d4];
        int index = 0;
        for (int i = 0; i < d1; i++) {
            for (int j = 0; j < d2; j++) {
                for (int k = 0; k < d3; k++) {
                     System.arraycopy(array[i][j][k], 0, flat, index, d4);
                     index += d4;
                }
            }
        }
        return flat;
    }
    
    private OnnxTensor floatBufferToOnnxTensor(FloatBuffer buffer, long[] shape) throws OrtException {
        return OnnxTensor.createTensor(this.env, buffer, shape);
    }

    private float[][][][] onnxValueTo4DSpec(OnnxValue onnxValue, int dim_f, int dim_t) throws OrtException {
        // Expected shape [1][4][dim_f][dim_t]
        float[][][][] result = new float[1][4][dim_f][dim_t];
        if (onnxValue instanceof OnnxTensor) {
            OnnxTensor tensor = (OnnxTensor) onnxValue;
            FloatBuffer buffer = tensor.getFloatBuffer();
            int expectedElements = 1 * 4 * dim_f * dim_t;
            if (buffer.remaining() != expectedElements) {
                throw new OrtException("Tensor shape mismatch. Expected " + expectedElements + " elements, got " + buffer.remaining());
            }
            for (int i = 0; i < 1; i++) { // batch
                for (int j = 0; j < 4; j++) { // channels (LRe,LIm,RRe,RIm)
                    for (int k = 0; k < dim_f; k++) { // freq
                         buffer.get(result[i][j][k], 0, dim_t); // time
                    }
                }
            }
        } else {
            throw new IllegalArgumentException("OnnxValue is not an OnnxTensor.");
        }
        return result;
    }

    @Override
    public void close() {
        logger.info("Closing AudioSeparator resources.");
        if (this.session != null) {
            try {
                this.session.close();
                logger.debug("OrtSession closed.");
            } catch (OrtException e) {
                logger.error("Error closing OrtSession", e);
            }
            this.session = null;
        }
        if (this.env != null) {
            // OrtEnvironment is a singleton and typically closed when the application shuts down.
            // Closing it here might affect other users of the ONNX runtime in the same JVM.
            // this.env.close(); // Consider if this is truly instance-specific or application-wide
            this.env = null; 
        }
    }
}
