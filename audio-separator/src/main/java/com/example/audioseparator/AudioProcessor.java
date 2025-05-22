package com.example.audioseparator;

import org.jtransforms.fft.FloatFFT_1D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Arrays;

public class AudioProcessor {
    private static final Logger logger = LoggerFactory.getLogger(AudioProcessor.class);

    private final int n_fft;
    private final int hop_length;
    private final int dim_f; // Number of frequency bins to keep/expect
    private final int dim_t; // Number of time frames expected by the model chunk
    private final FloatFFT_1D fft;
    private final float[] hannWindow;
    private final int stftPadding; // Renamed from reflectPadding for clarity

    public AudioProcessor(int n_fft, int hop_length, int dim_f, int dim_t) {
        this.n_fft = n_fft;
        this.hop_length = hop_length;
        this.dim_f = dim_f;
        this.dim_t = dim_t;
        this.fft = new FloatFFT_1D(n_fft);
        this.hannWindow = computeHannWindow(n_fft);
        this.stftPadding = n_fft / 2; // Standard padding for center=true
        logger.info("AudioProcessor initialized: n_fft={}, hop_length={}, dim_f={}, dim_t={}", n_fft, hop_length, dim_f, dim_t);

        if (dim_f > n_fft / 2 + 1) {
            throw new IllegalArgumentException("dim_f cannot be greater than n_fft / 2 + 1");
        }
    }

    private float[] computeHannWindow(int length) {
        float[] window = new float[length];
        for (int i = 0; i < length; i++) {
            window[i] = (float) (0.5 * (1 - Math.cos(2 * Math.PI * i / (length - 1))));
        }
        return window;
    }

    private void applyWindow(float[] frame, float[] window) {
        if (frame.length != window.length) {
            throw new IllegalArgumentException("Frame and window must have the same length.");
        }
        for (int i = 0; i < frame.length; i++) {
            frame[i] *= window[i];
        }
    }

    private float[] padSignal(float[] audioSignal, int paddingAmount) {
        // Pads `paddingAmount` of zeros to the beginning and end of the audioSignal.
        // This is used for 'center=true' STFT style padding.
        float[] paddedSignal = new float[audioSignal.length + 2 * paddingAmount];
        System.arraycopy(audioSignal, 0, paddedSignal, paddingAmount, audioSignal.length);
        // Zeros are already there by default from array initialization for the padded regions.
        return paddedSignal;
    }


    public float[][][][] stft(float[][] audioChunk) {
        int numChannels = audioChunk.length;
        if (numChannels != 2) {
            // For this implementation, we strictly expect 2 channels.
            throw new IllegalArgumentException("Audio chunk must have 2 channels for STFT. Got: " + numChannels);
        }
        // The input audioChunk's length for each channel is expected to be (dim_t - 1) * hop_length
        // This ensures that after STFT (with padding), we get exactly dim_t frames.
        // Example: if dim_t = 128, hop_length = 1024, then chunk_size = 127 * 1024 = 130048
        // Padded length = 130048 + n_fft
        // Number of frames = (130048 + n_fft - n_fft) / 1024 + 1 = 130048 / 1024 + 1 = 127 + 1 = 128 = dim_t.

        float[][][][] stftOutput = new float[numChannels][2][dim_f][dim_t]; // [channel][real/imag][freq_bin][time_frame]

        for (int channelIdx = 0; channelIdx < numChannels; channelIdx++) {
            float[] channelData = audioChunk[channelIdx];
            float[] paddedSignal = padSignal(channelData, stftPadding);

            // padded_length = channelData.length + 2 * stftPadding = channelData.length + n_fft
            // (channelData.length + n_fft - n_fft) / hop_length + 1 = channelData.length / hop_length + 1
            // So, dim_t = channelData.length / hop_length + 1, which means
            // channelData.length = (dim_t - 1) * hop_length. This is the expected input chunk_size.

            for (int t = 0; t < dim_t; t++) {
                int frameStart = t * hop_length;
                float[] frame = new float[n_fft];
                System.arraycopy(paddedSignal, frameStart, frame, 0, n_fft);

                applyWindow(frame, hannWindow);
                fft.realForward(frame); // In-place FFT

                // Unpack FFT data
                // JTransforms realForward output: a[0]=Re(0), a[1]=Re(n/2), a[2k]=Re(k), a[2k+1]=Im(k) for 1<=k<n/2
                stftOutput[channelIdx][0][0][t] = frame[0]; // DC real
                stftOutput[channelIdx][1][0][t] = 0.0f;     // DC imag

                for (int f = 1; f < dim_f; f++) {
                    if (f < n_fft / 2) {
                        stftOutput[channelIdx][0][f][t] = frame[2 * f];     // Re(f)
                        stftOutput[channelIdx][1][f][t] = frame[2 * f + 1]; // Im(f)
                    } else if (f == n_fft / 2) { // Nyquist frequency (only if n_fft is even and dim_f includes it)
                        stftOutput[channelIdx][0][f][t] = frame[1];         // Re(n/2)
                        stftOutput[channelIdx][1][f][t] = 0.0f;             // Im(n/2)
                    } else {
                        // This case should not be reached if dim_f <= n_fft/2 + 1
                        // If dim_f is smaller, then we've already stopped.
                        // If dim_f is larger, it's an error handled in constructor.
                        stftOutput[channelIdx][0][f][t] = 0.0f;
                        stftOutput[channelIdx][1][f][t] = 0.0f;
                    }
                }
            }
        }
        logger.debug("STFT completed. Output shape: [{}, 2, {}, {}]", numChannels, dim_f, dim_t);
        return stftOutput;
    }

    public float[][] istft(float[][][][] spectrogram, int originalChunkLength) {
        int numChannels = spectrogram.length;
        if (numChannels != 2) {
             throw new IllegalArgumentException("Input spectrogram must have 2 channels for ISTFT.");
        }
        int numFreqBinsInSpectrogram = spectrogram[0][0].length; // Should be dim_f
        int numFramesInSpectrogram = spectrogram[0][0][0].length; // Should be dim_t

        if (numFreqBinsInSpectrogram != dim_f || numFramesInSpectrogram != dim_t) {
            logger.warn("Spectrogram dimensions [{},{},{},{}] do not match configured dim_f={} and dim_t={}",
                    numChannels, 2, numFreqBinsInSpectrogram, numFramesInSpectrogram, dim_f, dim_t);
            // Potentially throw an error or try to adapt, but for now, assume they match.
        }

        float[][] outputAudio = new float[numChannels][originalChunkLength];
        // Total length of the signal after overlap-add, before trimming padding
        int synthesizedSignalLength = (dim_t - 1) * hop_length + n_fft;


        for (int channelIdx = 0; channelIdx < numChannels; channelIdx++) {
            float[] currentChannelFull = new float[synthesizedSignalLength];
            float[] packedFftFrame = new float[n_fft]; // JTransforms works in-place

            for (int t = 0; t < dim_t; t++) {
                // Reconstruct packed FFT frame from spectrogram data (real and imag parts)
                // And pad with zeros if dim_f < n_fft/2 + 1
                packedFftFrame[0] = spectrogram[channelIdx][0][0][t]; // DC real
                // Imaginary part of DC is 0, not explicitly set as realInverse expects it.

                for (int f = 1; f < n_fft / 2; f++) {
                    if (f < dim_f) {
                        packedFftFrame[2 * f] = spectrogram[channelIdx][0][f][t];     // Re(f)
                        packedFftFrame[2 * f + 1] = spectrogram[channelIdx][1][f][t]; // Im(f)
                    } else {
                        packedFftFrame[2 * f] = 0.0f;     // Pad with zero
                        packedFftFrame[2 * f + 1] = 0.0f; // Pad with zero
                    }
                }

                if (n_fft % 2 == 0) { // Nyquist frequency for even n_fft
                    if (dim_f == (n_fft / 2 + 1)) { // if dim_f includes Nyquist
                         packedFftFrame[1] = spectrogram[channelIdx][0][n_fft / 2][t]; // Re(n/2)
                    } else {
                         packedFftFrame[1] = 0.0f; // Pad Nyquist if not in dim_f
                    }
                }
                // else: if n_fft is odd, there's no a[1] for Nyquist.

                fft.realInverse(packedFftFrame, true); // In-place IFFT, true for scaling by 1/n_fft
                applyWindow(packedFftFrame, hannWindow); // Apply synthesis window

                // Overlap-add
                int frameStartOutput = t * hop_length;
                for (int i = 0; i < n_fft; i++) {
                    if (frameStartOutput + i < synthesizedSignalLength) {
                        currentChannelFull[frameStartOutput + i] += packedFftFrame[i];
                    }
                }
            }

            // Trim the padding
            int startIndex = stftPadding; // n_fft / 2
            // The length of the signal segment that corresponds to the originalChunkLength before padding
            int effectiveSignalLength = (dim_t - 1) * hop_length;

            int lengthToCopy = originalChunkLength;
            if (originalChunkLength > effectiveSignalLength) {
                // This case means the originalChunkLength was longer than what this STFT/ISTFT process naturally produces without its own padding.
                // This shouldn't happen if originalChunkLength is derived from the input to STFT.
                // We can only reconstruct up to effectiveSignalLength from the spectrogram.
                logger.warn("originalChunkLength {} is greater than the effective signal length {} reconstructable from the spectrogram. Output will be truncated to {}.",
                            originalChunkLength, effectiveSignalLength, effectiveSignalLength);
                lengthToCopy = effectiveSignalLength;
            }
            
            // Ensure we don't try to copy more than available in currentChannelFull (after removing padding)
            // or more than fits into outputAudio[channelIdx]
            int availableAfterTrim = synthesizedSignalLength - 2 * stftPadding;
            lengthToCopy = Math.min(lengthToCopy, availableAfterTrim);
            lengthToCopy = Math.min(lengthToCopy, outputAudio[channelIdx].length);


            if (lengthToCopy > 0 && startIndex < synthesizedSignalLength && (startIndex + lengthToCopy) <= synthesizedSignalLength) {
                 System.arraycopy(currentChannelFull, startIndex, outputAudio[channelIdx], 0, lengthToCopy);
                 if (lengthToCopy < originalChunkLength) {
                     // This means the output is shorter than originalChunkLength, pad the rest with zeros.
                     // (This part of the output array is already zeros by default initialization if originalChunkLength was used for allocation)
                     logger.warn("ISTFT output for channel {} (length {}) is shorter than originalChunkLength ({}) after trimming and available data. The rest is zero-padded.",
                                 channelIdx, lengthToCopy, originalChunkLength);
                 }
            } else if (lengthToCopy == 0) {
                 logger.warn("ISTFT trimming results in zero length to copy for channel {}. Output will be silent.", channelIdx);
            }
            else {
                logger.error("Error in ISTFT trimming logic: startIndex={}, lengthToCopy={}, originalChunkLength={}, synthesizedSignalLength={}. Output for channel {} might be incorrect or empty.",
                startIndex, lengthToCopy, originalChunkLength, synthesizedSignalLength, channelIdx);
            }
        }
        logger.debug("ISTFT completed. Output audio chunk length: {}", originalChunkLength);
        return outputAudio;
    }

    // Getters for parameters needed by AudioSeparator
    public int getN_fft() {
        return n_fft;
    }

    public int getHop_length() {
        return hop_length;
    }

    public int getDim_f() {
        return dim_f;
    }

    public int getDim_t() {
        return dim_t;
    }
}
