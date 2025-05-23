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
    private final String outputContent; // To store "vocals" or "both"

    private static final Logger logger = LoggerFactory.getLogger(AudioSeparator.class);

    public AudioSeparator(Map<String, Object> args, AudioProcessor audioProcessor) {
        this.args = args;
        this.audioProcessor = audioProcessor;

        this.sampleRate = ((Number) this.args.getOrDefault("sample_rate", 44100.0f)).floatValue();
        // Default margin of 1.0 second * sampleRate, cast to int
        this.marginSamples = ((Number) this.args.getOrDefault("margin", 1.0f * this.sampleRate)).intValue();
        this.chunkSeconds = ((Number) this.args.getOrDefault("chunks", 45)).intValue(); // Default to 45s, matching python GUI
        this.denoiseEnabled = (boolean) this.args.getOrDefault("denoise", false); // Default to false, matching python CLI
        this.outputContent = (String) this.args.getOrDefault("output_content", "both");

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
        
        }
        
        // processSegmentedMix now returns List<float[][][][]>
        // Index 0: Vocals Spectrogram, Index 1: Accompaniment Spectrogram (or null)
        List<float[][][][]> fullSpectrograms = processSegmentedMix(segmentedMix, totalOriginalSamples, currentProcessingChunkSize);
        float[][][][] fullVocalsSpectrogram = fullSpectrograms.get(0);
        float[][][][] fullAccompanimentSpectrogram = fullSpectrograms.get(1); // This can be null

        logger.info("Performing ISTFT on full vocals spectrogram...");
        float[][] vocalsAudio = audioProcessor.istft(fullVocalsSpectrogram, totalOriginalSamples);
        
        float[][] accompanimentAudio = null;
        if ("both".equals(this.outputContent) && fullAccompanimentSpectrogram != null) {
            logger.info("Performing ISTFT on full accompaniment spectrogram...");
            accompanimentAudio = audioProcessor.istft(fullAccompanimentSpectrogram, totalOriginalSamples);
        } else {
            logger.info("Accompaniment processing skipped as per output_content setting or missing spectrogram.");
            // Create empty/silent audio if not processing accompaniment, to maintain structure
            accompanimentAudio = new float[numChannels][totalOriginalSamples]; 
        }

        List<float[][]> result = new ArrayList<>();
        result.add(vocalsAudio);
        result.add(accompanimentAudio);
        logger.info("Audio separation process completed.");
        return result;
    }

    private List<float[][][][]> processSegmentedMix(Map<Long, float[][]> segmentedMix, int totalOriginalSamples, int processingChunkSize) {
        logger.debug("Processing {} audio segments in spectrogram domain.", segmentedMix.size());

        final int n_fft = audioProcessor.getN_fft();
        final int hop_length = audioProcessor.getHop_length();
        final int dim_f = audioProcessor.getDim_f();
        final int numChannels = 2; // Assuming stereo
        final int numComplex = 2;  // Real and Imaginary

        // Calculate total time frames for the full output spectrogram
        // This should correspond to the STFT of totalOriginalSamples
        final int totalTimeFrames = (totalOriginalSamples - n_fft) / hop_length + 1;
        if (totalTimeFrames <= 0) {
            logger.warn("Total time frames calculated to be {} based on totalOriginalSamples {}. Returning empty spectrograms.", totalTimeFrames, totalOriginalSamples);
            List<float[][][][]> emptyResult = new ArrayList<>();
            emptyResult.add(initializeSpectrogram(numChannels, numComplex, dim_f, 0));
            emptyResult.add(initializeSpectrogram(numChannels, numComplex, dim_f, 0));
            return emptyResult;
        }


        float[][][][] fullVocalsSpectrogram = initializeSpectrogram(numChannels, numComplex, dim_f, totalTimeFrames);
        float[][][][] fullAccompSpectrogram = null; // Initialize to null
        if ("both".equals(this.outputContent)) {
            fullAccompSpectrogram = initializeSpectrogram(numChannels, numComplex, dim_f, totalTimeFrames);
        }
        float[] sumSquareWindow = new float[totalTimeFrames]; // 1D for frame-wise normalization factor

        // Precompute a Hann window for overlap-add blending if marginSamples > 0
        // The window length should cover the margin on one side.
        // For overlap-add, a (symmetric) window of 2*marginFrames is often used, applied to each side of the overlap.
        // Here, we'll use a Hann window of length (marginFrames * 2)
        final int marginFrames = (marginSamples > 0) ? (marginSamples / hop_length) : 0;
        float[] hannWindowForOverlap = (marginFrames > 0) ? audioProcessor.computeHannWindow(marginFrames * 2) : null;

        int segmentCount = segmentedMix.size();
        int currentSegmentNum = 0;

        for (Map.Entry<Long, float[][]> entry : segmentedMix.entrySet()) {
            currentSegmentNum++;
            System.out.println("Processing segment " + currentSegmentNum + " of " + segmentCount + " (spectrogram domain)...");

            long segmentOriginalStartSample = entry.getKey(); // Original start in samples, without left margin
            float[][] segmentAudioWithMargin = entry.getValue(); // Audio data *including* margins

            // This is the length of the original audio segment *before* any margins were added for *this specific segment*.
            int originalLengthOfThisSegmentAudio = (int) Math.min(processingChunkSize, totalOriginalSamples - segmentOriginalStartSample);
            
            logger.debug("Processing segment: originalAudioStartSample={}, originalAudioLength={}, audioSegmentWithMarginLength={}", 
                segmentOriginalStartSample, originalLengthOfThisSegmentAudio, segmentAudioWithMargin[0].length);

            // demixChunk now returns List<float[][][][]> (vocals_spec, accomp_spec or null)
            List<float[][][][]> segmentSpectrograms = demixChunk(segmentAudioWithMargin); 
            float[][][][] vocalsSegmentSpec = segmentSpectrograms.get(0); // [2][2][dim_f][segment_frames]
            float[][][][] accompSegmentSpec = segmentSpectrograms.get(1); // This can be null if outputContent is "vocals"
            
            int segmentTotalFrames = vocalsSegmentSpec[0][0][0].length; // Number of time frames in the current segment's spectrogram

            // Calculate frame indices for copying and overlap-add
            // Start frame in the full spectrogram where this segment's content should begin
            int writeToStartFrameFull = (int) (segmentOriginalStartSample / hop_length); 
            
            // Frame index in the segment's spectrogram from where we start copying (after left margin)
            int readFromFrameInSegment = (segmentOriginalStartSample == 0) ? 0 : marginFrames;

            // Number of frames in the segment's spectrogram that correspond to the original content (excluding margins)
            // This is (originalLengthOfThisSegmentAudio - n_fft) / hop_length + 1, but segment spec is already for segmentAudioWithMargin
            // So, we need to determine how many frames of the *processed* segment spec to actually use.
            // The segmentSpectrograms from demixChunk are for `segmentAudioWithMargin`.
            // We need to copy the part that corresponds to `originalLengthOfThisSegmentAudio`.

            // Number of frames to copy from the processed segment spectrogram.
            // This is the number of frames corresponding to originalLengthOfThisSegmentAudio.
            int contentFramesInOriginalSegment = (originalLengthOfThisSegmentAudio - n_fft) / hop_length + 1;
            if (originalLengthOfThisSegmentAudio < n_fft) contentFramesInOriginalSegment = 1; // Ensure at least 1 frame for short segments

            // Effective frames to copy from the *middle* of segment spectrogram (after accounting for its internal margins)
            int effectiveFramesToCopy = segmentTotalFrames - ((segmentOriginalStartSample == 0) ? 0 : marginFrames);
            if (segmentOriginalStartSample + originalLengthOfThisSegmentAudio < totalOriginalSamples) {
                 effectiveFramesToCopy -= marginFrames; // Subtract right margin if not the last overall segment
            }
            effectiveFramesToCopy = Math.max(0, effectiveFramesToCopy);


            logger.debug("Segment {}: totalFramesInSegmentSpec={}, writeToStartFrameFull={}, readFromFrameInSegment={}, effectiveFramesToCopy={}",
                         currentSegmentNum, segmentTotalFrames, writeToStartFrameFull, readFromFrameInSegment, effectiveFramesToCopy);
            
            if (effectiveFramesToCopy <= 0) {
                logger.warn("Segment {} resulted in <=0 effective frames to copy. Skipping.", currentSegmentNum);
                continue;
            }

            // Apply overlap-add to the spectrograms
            for (int t_seg = 0; t_seg < effectiveFramesToCopy; ++t_seg) {
                int t_full = writeToStartFrameFull + t_seg; // Frame index in the full spectrogram
                int t_read = readFromFrameInSegment + t_seg; // Frame index in the current segment's spectrogram

                if (t_full >= totalTimeFrames || t_read >= segmentTotalFrames) {
                    logger.warn("Frame index out of bounds. t_full={}, totalTimeFrames={}, t_read={}, segmentTotalFrames={}", 
                                t_full, totalTimeFrames, t_read, segmentTotalFrames);
                    continue; 
                }

                float windowVal = 1.0f;
                if (hannWindowForOverlap != null) {
                    // Determine if we are in a left or right margin overlap region FOR THIS SEGMENT
                    boolean isLeftMargin = (segmentOriginalStartSample != 0 && t_seg < marginFrames);
                    boolean isRightMargin = ( (segmentOriginalStartSample + originalLengthOfThisSegmentAudio < totalOriginalSamples) && 
                                             (t_seg >= effectiveFramesToCopy - marginFrames) );
                    
                    if (isLeftMargin) {
                        windowVal = hannWindowForOverlap[t_seg]; // First half of Hann window
                    } else if (isRightMargin) {
                        // Index into the second half of the Hann window
                        windowVal = hannWindowForOverlap[marginFrames + (t_seg - (effectiveFramesToCopy - marginFrames))];
                    }
                }

                for (int ch = 0; ch < numChannels; ++ch) {
                    for (int cpl = 0; cpl < numComplex; ++cpl) {
                        for (int f = 0; f < dim_f; ++f) {
                            fullVocalsSpectrogram[ch][cpl][f][t_full] += vocalsSegmentSpec[ch][cpl][f][t_read] * windowVal;
                            if (accompSegmentSpec != null && fullAccompSpectrogram != null) {
                                fullAccompSpectrogram[ch][cpl][f][t_full] += accompSegmentSpec[ch][cpl][f][t_read] * windowVal;
                            }
                        }
                    }
                }
                sumSquareWindow[t_full] += windowVal * windowVal;
            }
             System.out.println("Segment " + currentSegmentNum + " processed (spectrogram domain).");
        }

        // Normalize the overlap-added regions
        for (int t = 0; t < totalTimeFrames; ++t) {
            if (sumSquareWindow[t] > 1e-8f) { // Avoid division by zero or very small numbers
                for (int ch = 0; ch < numChannels; ++ch) {
                    for (int cpl = 0; cpl < numComplex; ++cpl) {
                        for (int f = 0; f < dim_f; ++f) {
                            fullVocalsSpectrogram[ch][cpl][f][t] /= sumSquareWindow[t];
                            if (fullAccompSpectrogram != null) {
                                fullAccompSpectrogram[ch][cpl][f][t] /= sumSquareWindow[t];
                            }
                        }
                    }
                }
            }
        }
        
        List<float[][][][]> resultSpectrograms = new ArrayList<>();
        resultSpectrograms.add(fullVocalsSpectrogram);
        resultSpectrograms.add(fullAccompSpectrogram); // This will be null if outputContent was "vocals"
        return resultSpectrograms;
    }
    
    // Returns a list containing two spectrograms: 0 = vocals, 1 = accompaniment
    private List<float[][][][]> demixChunk(float[][] segmentWithMargin) { // Signature already changed
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
        logger.debug("DemixChunk: segment_len={}, pythonGenSize={}, padForPythonGenSize={}, loop_input_len={}, model_proc_chunk_size={}",
            currentSamplesInSegment, pythonGenSize, padForPythonGenSize, paddedInputLengthForLoop, stftModelProcessingChunkSize);

        List<float[][][][]> vocalSubChunkSpectrograms = new ArrayList<>();
        List<float[][][][]> accompanimentSubChunkSpectrograms = new ArrayList<>(); // Might remain empty

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

            // resultSpec is the model's direct output for vocals (after potential denoising)
            // its shape is [1][4][dim_f_model][dim_t_model]
            // We need to convert it to [2][2][dim_f_model][dim_t_model] for consistency
            float[][][][] vocalSubChunkSpec = modelOutputToIstftInput(resultSpec, dim_f_model, dim_t_model);
            
            // mix_sub_chunk_spectrogram (named 'spectrogram' earlier in the loop)
            // also needs to be [2][2][dim_f_model][dim_t_model]
            // The stft method already returns this format.
            float[][][][] mixSubChunkSpec = spectrogram; // This is the STFT of the current subChunkForStft

            vocalSubChunkSpectrograms.add(vocalSubChunkSpec);

            if ("both".equals(this.outputContent)) {
                float[][][][] accompanimentSubChunkSpec = subtractSpectrograms(mixSubChunkSpec, vocalSubChunkSpec);
                accompanimentSubChunkSpectrograms.add(accompanimentSubChunkSpec);
            }
            // totalFramesProcessed += dim_t_model; // Not strictly needed anymore
        }
        
        logger.debug("Processed {} sub-chunks.", vocalSubChunkSpectrograms.size());

        // Concatenate sub-chunk spectrograms
        // The effective number of frames in each sub-chunk after ISTFT and trimming would be (pythonGenSize / hop_length)
        // However, the spectrograms themselves (before ISTFT) have dim_t_model frames.
        // The concatenation logic in python for audio is:
        //   waves = [self.istft(s) for s in self.stft_secondary_outputs]
        //   waves = [wave[:, :, self.trim:-self.trim] for wave in waves]
        //   res = np.concatenate(waves, axis=-1)[:, :, :mix_waves_length]
        // This means each sub-chunk spectrogram contributes (pythonGenSize / hop_length) frames to the final *time-domain* signal.
        // For spectrogram domain concatenation, we need to be careful.
        // Each sub-chunk spec is dim_t_model long. The step was pythonGenSize.
        // Let's assume for now that we are concatenating the full dim_t_model frames from each spec,
        // and then trimming at the very end based on currentSamplesInSegment.
        
        int expectedTotalFrames = (currentSamplesInSegment + padForPythonGenSize) / hop_length + 1; 
        // This is an estimate; more accurately, it's (padded_input_length_for_loop - n_fft)/hop_length + 1,
        // if we consider the signal that was actually looped over for STFTs.
        // Or, simpler: sum of (dim_t_model - 2 * (stftInternalTrim / hop_length)) if overlap-add was done in spec domain.
        // Given the python code concatenates in time domain *after* ISTFT and trimming each sub-chunk,
        // we should replicate that by trimming each sub-chunk spectrogram before concatenating.
        // Each sub-chunk spec (dim_t_model frames) corresponds to stftModelProcessingChunkSize audio samples.
        // After ISTFT and trimming stftInternalTrim, it becomes pythonGenSize audio samples.
        // Number of frames for pythonGenSize: (pythonGenSize - n_fft) / hop_length + 1 IF n_fft is subtracted.
        // But STFT output is already for the full stftModelProcessingChunkSize.
        // The effective non-overlapping part of each spec is pythonGenSize worth of audio.
        // Number of frames corresponding to pythonGenSize: pythonGenSize / hop_length (if pythonGenSize is multiple of hop_length)
        
        // Let's adjust the sub-chunk spectrograms to represent the "trimmed" audio part (pythonGenSize)
        // Number of frames for stftInternalTrim: stftInternalTrim / hop_length
        int framesToTrimFromSubChunkSpec = stftInternalTrim / hop_length;
        List<float[][][][]> trimmedVocalSpecs = new ArrayList<>();
        for(float[][][][] spec : vocalSubChunkSpectrograms) {
            trimmedVocalSpecs.add(trimSpectrogramFrames(spec, framesToTrimFromSubChunkSpec, framesToTrimFromSubChunkSpec));
        }
        float[][][][] fullVocalsSpectrogram = concatenateSpectrograms(trimmedVocalSpecs, dim_f_model);
        
        float[][][][] fullAccompSpectrogram = null;
        if ("both".equals(this.outputContent)) {
            List<float[][][][]> trimmedAccompSpecs = new ArrayList<>();
            for(float[][][][] spec : accompanimentSubChunkSpectrograms) {
                trimmedAccompSpecs.add(trimSpectrogramFrames(spec, framesToTrimFromSubChunkSpec, framesToTrimFromSubChunkSpec));
            }
            fullAccompSpectrogram = concatenateSpectrograms(trimmedAccompSpecs, dim_f_model);
        }

        // Final trim based on `padForPythonGenSize` (padding added to make original segment multiple of pythonGenSize)
        int totalFramesBeforeFinalTrim = fullVocalsSpectrogram[0][0][0].length;
        int framesToTrimEnd = (padForPythonGenSize > 0) ? (padForPythonGenSize / hop_length) : 0;
        // This might not be perfectly accurate if padForPythonGenSize is not a multiple of hop_length.
        // The python code trims in time domain: `[:, :, :mix_waves_length]`.
        // So, the target number of frames is roughly `currentSamplesInSegment / hop_length`.
        int targetTotalFrames = (currentSamplesInSegment - n_fft) / hop_length + 1;


        fullVocalsSpectrogram = trimSpectrogramFrames(fullVocalsSpectrogram, 0, totalFramesBeforeFinalTrim - targetTotalFrames);
        if (fullAccompSpectrogram != null) {
            fullAccompSpectrogram = trimSpectrogramFrames(fullAccompSpectrogram, 0, totalFramesBeforeFinalTrim - targetTotalFrames);
        }
        
        logger.debug("Demixing chunk finished. Spectrograms ready. Target frames: {}, Actual frames: {}", 
                     targetTotalFrames, fullVocalsSpectrogram[0][0][0].length);
        
        List<float[][][][]> resultSpectrograms = new ArrayList<>();
        resultSpectrograms.add(fullVocalsSpectrogram);
        resultSpectrograms.add(fullAccompSpectrogram); // Will be null if not "both"
        return resultSpectrograms;
    }

    private float[][][][] subtractSpectrograms(float[][][][] spec1, float[][][][] spec2) {
        // Assumes spec1 and spec2 have the same dimensions [2][2][dim_f][dim_t]
        int numChannels = spec1.length; // Should be 2 (L, R)
        int numComplex = spec1[0].length; // Should be 2 (Real, Imag)
        int dim_f = spec1[0][0].length;
        int dim_t = spec1[0][0][0].length;

        float[][][][] result = new float[numChannels][numComplex][dim_f][dim_t];

        for (int ch = 0; ch < numChannels; ch++) {
            for (int cpl = 0; cpl < numComplex; cpl++) {
                for (int f = 0; f < dim_f; f++) {
                    for (int t = 0; t < dim_t; t++) {
                        result[ch][cpl][f][t] = spec1[ch][cpl][f][t] - spec2[ch][cpl][f][t];
                    }
                }
            }
        }
        return result;
    }

    private float[][][][] concatenateSpectrograms(List<float[][][][]> subChunkSpectrograms, int dim_f) {
        if (subChunkSpectrograms.isEmpty()) {
            return new float[2][2][dim_f][0]; // Return empty spectrogram if list is empty
        }
        // Calculate total time frames
        int total_dim_t = 0;
        for (float[][][][] spec : subChunkSpectrograms) {
            total_dim_t += spec[0][0][0].length;
        }

        float[][][][] concatenated = new float[2][2][dim_f][total_dim_t];
        int currentTimeFrameOffset = 0;
        for (float[][][][] spec : subChunkSpectrograms) {
            int currentSpecDimT = spec[0][0][0].length;
            for (int ch = 0; ch < 2; ch++) {
                for (int cpl = 0; cpl < 2; cpl++) {
                    for (int f = 0; f < dim_f; f++) {
                        System.arraycopy(spec[ch][cpl][f], 0, concatenated[ch][cpl][f], currentTimeFrameOffset, currentSpecDimT);
                    }
                }
            }
            currentTimeFrameOffset += currentSpecDimT;
        }
        return concatenated;
    }

    private float[][][][] trimSpectrogramFrames(float[][][][] spectrogram, int framesToTrimStart, int framesToTrimEnd) {
        int numChannels = spectrogram.length;
        int numComplex = spectrogram[0].length;
        int dim_f = spectrogram[0][0].length;
        int original_dim_t = spectrogram[0][0][0].length;

        int new_dim_t = original_dim_t - framesToTrimStart - framesToTrimEnd;
        if (new_dim_t <= 0) {
            logger.warn("Spectrogram trimming results in non-positive time frames ({}). Returning empty or original.", new_dim_t);
            return (new_dim_t == 0) ? new float[numChannels][numComplex][dim_f][0] : spectrogram; // Or throw error
        }

        float[][][][] trimmed = new float[numChannels][numComplex][dim_f][new_dim_t];
        for (int ch = 0; ch < numChannels; ch++) {
            for (int cpl = 0; cpl < numComplex; cpl++) {
                for (int f = 0; f < dim_f; f++) {
                    System.arraycopy(spectrogram[ch][cpl][f], framesToTrimStart, trimmed[ch][cpl][f], 0, new_dim_t);
                }
            }
        }
        return trimmed;
    }
    
    private float[][][][] spectrogramToArrayForModel(float[][][][] spec, int dim_f, int dim_t) { // Unchanged
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
    
    private float[][][][] negateSpectrogram(float[][][][] spec) { // Unchanged
        float[][][][] negated = new float[spec.length][spec[0].length][spec[0][0].length][spec[0][0][0].length];
        for(int i=0; i<spec.length; ++i) for(int j=0; j<spec[0].length; ++j) for(int k=0; k<spec[0][0].length; ++k) for(int l=0; l<spec[0][0][0].length; ++l) {
            negated[i][j][k][l] = -spec[i][j][k][l];
        }
        return negated;
    }


    private OnnxTensor spectrogramToOnnxTensor(float[][][][] spec, int dim_f, int dim_t) { // Unchanged
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


    private float[] flatten4DArray(float[][][][] array) { // Unchanged
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
    
    private OnnxTensor floatBufferToOnnxTensor(FloatBuffer buffer, long[] shape) throws OrtException { // Unchanged
        return OnnxTensor.createTensor(this.env, buffer, shape);
    }

    private float[][][][] onnxValueTo4DSpec(OnnxValue onnxValue, int dim_f, int dim_t) throws OrtException { // Unchanged
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

    private float[][][][] initializeSpectrogram(int channels, int complex, int freqBins, int timeFrames) {
        return new float[channels][complex][freqBins][timeFrames];
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
